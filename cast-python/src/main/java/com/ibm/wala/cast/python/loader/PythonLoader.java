package com.ibm.wala.cast.python.loader;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.ibm.wala.cast.ir.translator.AstTranslator.AstLexicalInformation;
import com.ibm.wala.cast.ir.translator.AstTranslator.WalkContext;
import com.ibm.wala.cast.ir.translator.TranslatorToIR;
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.cast.loader.CAstAbstractModuleLoader;
import com.ibm.wala.cast.python.global.SystemPath;
import com.ibm.wala.cast.python.ir.PythonCAstToIRTranslator;
import com.ibm.wala.cast.python.ir.PythonLanguage;
import com.ibm.wala.cast.python.module.PyLibURLModule;
import com.ibm.wala.cast.python.module.PyScriptModule;
import com.ibm.wala.cast.python.types.PythonTypes;
import com.ibm.wala.cast.python.util.PathUtil;
import com.ibm.wala.cast.tree.*;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.impl.CAstImpl;
import com.ibm.wala.cast.tree.impl.CAstTypeDictionaryImpl;
import com.ibm.wala.cast.types.AstMethodReference;
import com.ibm.wala.cast.util.CAstPattern;
import com.ibm.wala.cast.util.CAstPattern.Segments;
import com.ibm.wala.cfg.AbstractCFG;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.*;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.annotations.Annotation;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.strings.Atom;

import static com.ibm.wala.cast.python.types.PythonTypes.EmptyPyScript;
import static com.ibm.wala.cast.python.types.PythonTypes.UnknownObject;

public abstract class PythonLoader extends CAstAbstractModuleLoader {

    public class DynamicMethodBody extends DynamicCodeBody {
        private final IClass container;
        private final Collection<Annotation> annotations;

        public DynamicMethodBody(TypeReference codeName, TypeReference parent, IClassLoader loader,
                                 Position sourcePosition, CAstEntity entity, WalkContext context, IClass container) {
            super(codeName, parent, loader, sourcePosition, entity, context);
            this.container = container;
            this.annotations = new HashSet<>();
            for (CAstAnnotation astAnnotation : entity.getAnnotations()) {
                if (astAnnotation.getType().equals(PythonTypes.cAstDynamicAnnotation)) {
                    CAstNode node = (CAstNode) astAnnotation.getArguments().get("dynamicAnnotation");
                    // FIXME 现在只处理无参的annotation
                    if (node.getChild(0).getValue() instanceof String) {
                        String annotation = (String) node.getChild(0).getValue();
                        TypeReference typeReference = TypeReference.findOrCreate(PythonTypes.pythonLoader,
                                TypeName.findOrCreate("L" + annotation));
                        annotations.add(Annotation.make(typeReference));
                    }
                }
            }
        }

        public IClass getContainer() {
            return container;
        }

        @Override
        public Collection<Annotation> getAnnotations() {
            return annotations;
        }
    }

    public class PythonClass extends CoreClass {
        java.util.Set<IField> staticFields = HashSetFactory.make();
        java.util.Set<MethodReference> methodTypes = HashSetFactory.make();
        private java.util.Set<TypeReference> innerTypes = HashSetFactory.make();

        public PythonClass(TypeName name, TypeName superName, IClassLoader loader, Position sourcePosition) {
            super(name, superName, loader, sourcePosition);
            if (name.toString().lastIndexOf('/') > 0) {
                String maybeOuterName = name.toString().substring(0, name.toString().lastIndexOf('/'));
                TypeName maybeOuter = TypeName.findOrCreate(maybeOuterName);
                if (types.containsKey(maybeOuter)) {
                    IClass cls = types.get(maybeOuter);
                    if (cls instanceof PythonClass) {
                        ((PythonClass) cls).innerTypes.add(this.getReference());
                    }
                }
            }
        }

        @Override
        public Collection<IField> getDeclaredStaticFields() {
            return staticFields;
        }

        public Collection<MethodReference> getMethodReferences() {
            return methodTypes;
        }

        public Collection<TypeReference> getInnerReferences() {
            return innerTypes;
        }
    }

    protected final CAstTypeDictionaryImpl<String> typeDictionary = new CAstTypeDictionaryImpl<String>();
    private final CAst Ast = new CAstImpl();
    protected final CAstPattern sliceAssign = CAstPattern.parse("<top>ASSIGN(CALL(VAR(\"slice\"),<args>**),<value>*)");

    private Class<?> moduleClass;

    /**
     * 目前只支持SourceURLModules和SourceFileModules, 不支持嵌套
     *
     * @param modules
     */
    @Override
    public void init(final List<Module> modules) {

        Path rootPath = null;
        for (Module module : modules) {
            if (module instanceof PyScriptModule) {
                Class<?> thisModuleClass = module.getClass();
                if (moduleClass == null) {
                    moduleClass = thisModuleClass;
                } else if (!moduleClass.equals(thisModuleClass)) {
                    System.err.println("[WARN] module type doesn't match. To ensure project root, plz use PyScriptModule");
                }
                for (Iterator<? extends ModuleEntry> it = module.getEntries(); it.hasNext(); ) {
                    ModuleEntry moduleEntry = it.next();
                    // 获取项目主目录
                    Path modulePath = PathUtil.getPath(moduleEntry.getName());
                    if (rootPath == null || rootPath.toString().length() > modulePath.getParent().toString().length()) {
                        rootPath = modulePath.getParent();
                    }
                }
            } else if (module instanceof PyLibURLModule) {
                continue;
            } else {
                System.err.println("[Error] Not PyScriptModule or PyLibURLModule");
                System.exit(1);
            }
        }

        SystemPath.getInstance().setAppPath(rootPath);

        for (Module module : modules) {
            for (Iterator<? extends ModuleEntry> it = module.getEntries(); it.hasNext(); ) {
                ModuleEntry moduleEntry = it.next();
                String path = moduleEntry.getName();
                TypeName moduleName = TypeName.string2TypeName("Lscript " + path);
                CoreClass tempPyScript = new CoreClass(moduleName, EmptyPyScript.getName(), this, null);
                types.put(moduleName, tempPyScript);
            }
        }

        super.init(modules);
    }


    @Override
    public ClassLoaderReference getReference() {
        return PythonTypes.pythonLoader;
    }

    @Override
    public Language getLanguage() {
        return PythonLanguage.Python;
    }

    @Override
    public SSAInstructionFactory getInstructionFactory() {
        return getLanguage().instructionFactory();
    }

    protected final CAstPattern sliceAssignOp = CAstPattern.parse("<top>ASSIGN_POST_OP(CALL(VAR(\"slice\"),<args>**),<value>*,<op>*)");
    final CoreClass Root = new CoreClass(PythonTypes.rootTypeName, null, this, null);
    final CoreClass Exception = new CoreClass(PythonTypes.Exception.getName(), PythonTypes.rootTypeName, this, null);

    protected CAstNode rewriteSubscriptAssign(Segments s) {
        int i = 0;
        CAstNode[] args = new CAstNode[s.getMultiple("args").size() + 1];
        for (CAstNode arg : s.getMultiple("args")) {
            args[i++] = arg;
        }
        args[i++] = s.getSingle("value");

        return Ast.makeNode(CAstNode.CALL, Ast.makeNode(CAstNode.VAR, Ast.makeConstant("slice")), args);
    }

    protected CAstNode rewriteSubscriptAssignOp(Segments s) {
        int i = 0;
        CAstNode[] args = new CAstNode[s.getMultiple("args").size() + 1];
        for (CAstNode arg : s.getMultiple("args")) {
            args[i++] = arg;
        }
        args[i++] = s.getSingle("value");

        return Ast.makeNode(CAstNode.CALL, Ast.makeNode(CAstNode.VAR, Ast.makeConstant("slice")), args);
    }

    @Override
    protected boolean shouldTranslate(CAstEntity entity) {
        return true;
    }

    @Override
    protected TranslatorToIR initTranslator() {
        return new PythonCAstToIRTranslator(this);
    }

    // add built-in object in classloader
    final CoreClass CodeBody = new CoreClass(PythonTypes.CodeBody.getName(), PythonTypes.rootTypeName, this, null);
    final CoreClass lambda = new CoreClass(PythonTypes.lambda.getName(), PythonTypes.CodeBody.getName(), this, null);
    final CoreClass filter = new CoreClass(PythonTypes.filter.getName(), PythonTypes.CodeBody.getName(), this, null);
    final CoreClass comprehension = new CoreClass(PythonTypes.comprehension.getName(), PythonTypes.CodeBody.getName(), this, null);
    final CoreClass object = new CoreClass(PythonTypes.object.getName(), PythonTypes.rootTypeName, this, null);
    final CoreClass list = new CoreClass(PythonTypes.list.getName(), PythonTypes.object.getName(), this, null);
    final CoreClass set = new CoreClass(PythonTypes.set.getName(), PythonTypes.object.getName(), this, null);
    final CoreClass dict = new CoreClass(PythonTypes.dict.getName(), PythonTypes.object.getName(), this, null);
    final CoreClass tuple = new CoreClass(PythonTypes.tuple.getName(), PythonTypes.object.getName(), this, null);
    final CoreClass string = new CoreClass(PythonTypes.string.getName(), PythonTypes.object.getName(), this, null);
    final CoreClass trampoline = new CoreClass(PythonTypes.trampoline.getName(), PythonTypes.CodeBody.getName(), this, null);
    final CoreClass superfun = new CoreClass(PythonTypes.superfun.getName(), PythonTypes.CodeBody.getName(), this, null);
    final CoreClass unknownObject = new CoreClass(UnknownObject.getName(), object.getName(), this, null);

    public PythonLoader(IClassHierarchy cha, IClassLoader parent) {
        super(cha, parent);
    }

    public PythonLoader(IClassHierarchy cha) {
        super(cha);
    }


    public IClass makeMethodBodyType(String name, TypeReference P, CAstSourcePositionMap.Position sourcePosition, CAstEntity entity, WalkContext context, IClass container) {
        return new DynamicMethodBody(TypeReference.findOrCreate(PythonTypes.pythonLoader, TypeName.string2TypeName(name)), P, this,
                sourcePosition, entity, context, container);
    }

    public IClass makeCodeBodyType(String name, TypeReference P, CAstSourcePositionMap.Position sourcePosition, CAstEntity entity, WalkContext context) {
        return new DynamicCodeBody(TypeReference.findOrCreate(PythonTypes.pythonLoader, TypeName.string2TypeName(name)), P, this,
                sourcePosition, entity, context);
    }

    public IClass defineFunctionType(String name, CAstSourcePositionMap.Position pos, CAstEntity entity, WalkContext context) {
        CAstType st = entity.getType().getSupertypes().iterator().next();
        return makeCodeBodyType(name, lookupClass(TypeName.findOrCreate("L" + st.getName())).getReference(), pos, entity, context);
    }

    public IClass defineMethodType(String name, CAstSourcePositionMap.Position pos, CAstEntity entity, TypeName typeName, WalkContext context) {
        PythonClass self = (PythonClass) types.get(typeName);
        IClass fun = makeMethodBodyType(name, PythonTypes.CodeBody, pos, entity, context, self);

        assert types.containsKey(typeName);

        // staticmethod和classmethod也可以被调用
//        if (entity.getArgumentCount() > 0 && "self".equals(entity.getArgumentNames()[1])) {
//            MethodReference me = MethodReference.findOrCreate(fun.getReference(), Atom.findOrCreateUnicodeAtom(entity.getType().getName()), AstMethodReference.fnDesc);
//            self.methodTypes.add(me);
//        }
        MethodReference me = MethodReference.findOrCreate(fun.getReference(), Atom.findOrCreateUnicodeAtom(entity.getType().getName()), AstMethodReference.fnDesc);
        self.methodTypes.add(me);

        return fun;
    }

    public IMethod defineCodeBodyCode(String clsName, AbstractCFG<?, ?> cfg, SymbolTable symtab, boolean hasCatchBlock, Map<IBasicBlock<SSAInstruction>, TypeReference[]> caughtTypes, boolean hasMonitorOp,
                                      AstLexicalInformation lexicalInfo, DebuggingInformation debugInfo, int defaultArgs) {
        DynamicCodeBody C = (DynamicCodeBody) lookupClass(clsName, cha);
        assert C != null : clsName;
        return C.setCodeBody(makeCodeBodyCode(cfg, symtab, hasCatchBlock, caughtTypes, hasMonitorOp, lexicalInfo, debugInfo, C, defaultArgs));
    }

    public DynamicMethodObject makeCodeBodyCode(AbstractCFG<?, ?> cfg, SymbolTable symtab, boolean hasCatchBlock, Map<IBasicBlock<SSAInstruction>, TypeReference[]> caughtTypes, boolean hasMonitorOp, AstLexicalInformation lexicalInfo,
                                                DebuggingInformation debugInfo, IClass C, int defaultArgs) {
        return new DynamicMethodObject(C, Collections.emptySet(), cfg, symtab, hasCatchBlock, caughtTypes, hasMonitorOp, lexicalInfo,
                debugInfo) {
            @Override
            public int getNumberOfDefaultParameters() {
                return defaultArgs;
            }
        };
    }

    public void defineType(TypeName cls, TypeName parent, Position sourcePosition) {
        new PythonClass(cls, parent, this, sourcePosition);
    }

    public void defineField(TypeName cls, CAstEntity field) {
        assert types.containsKey(cls);
        ((PythonClass) types.get(cls)).staticFields.add(new IField() {
            @Override
            public String toString() {
                return "field:" + getName();
            }

            @Override
            public IClass getDeclaringClass() {
                return types.get(cls);
            }

            @Override
            public Atom getName() {
                return Atom.findOrCreateUnicodeAtom(field.getName());
            }

            @Override
            public Collection<Annotation> getAnnotations() {
                return Collections.emptySet();
            }

            @Override
            public IClassHierarchy getClassHierarchy() {
                return cha;
            }

            @Override
            public TypeReference getFieldTypeReference() {
                return PythonTypes.Root;
            }

            @Override
            public FieldReference getReference() {
                return FieldReference.findOrCreate(getDeclaringClass().getReference(), getName(), getFieldTypeReference());
            }

            @Override
            public boolean isFinal() {
                return false;
            }

            @Override
            public boolean isPrivate() {
                return false;
            }

            @Override
            public boolean isProtected() {
                return false;
            }

            @Override
            public boolean isPublic() {
                return true;
            }

            @Override
            public boolean isStatic() {
                return true;
            }

            @Override
            public boolean isVolatile() {
                return false;
            }
        });
    }


    // FIXME relpath/name
    @Override
    public IClass lookupClass(TypeName className) {
        return super.lookupClass(className);
    }
}