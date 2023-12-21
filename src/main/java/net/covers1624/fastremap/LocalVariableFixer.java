package net.covers1624.fastremap;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 17/1/23.
 */
public final class LocalVariableFixer extends ClassVisitor {

    // Lambda target method -> Outer owning method
    private final Map<String, OuterLambdaScope> lambdaMap = new HashMap<>();

    private final FastRemapper remapper;

    private String cName;
    private Integer outerMethodDepth;

    public LocalVariableFixer(ClassVisitor cv, FastRemapper remapper) {
        super(Opcodes.ASM9, cv);
        this.remapper = remapper;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        cName = name;
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
        super.visitOuterClass(owner, name, desc);
        if (name != null) {
            outerMethodDepth = remapper.getMethodDepth(owner, name + desc);
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        int paramWidth = getParamWidth(desc);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int parentDepth;
        OuterLambdaScope lambda = lambdaMap.get(name + desc);
        if (lambda != null && (access & Opcodes.ACC_SYNTHETIC) != 0) {
            parentDepth = remapper.getMethodDepth(cName, lambda.method);
        } else if (outerMethodDepth != null) {
            parentDepth = outerMethodDepth;
        } else {
            parentDepth = 0; // Ugh, effectively final if it's in an else.
        }
        String mName = name;
        String mDesc = desc;
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {

            private final List<String> localNames = new ArrayList<>();

            public String uniqueLocal(String name) {
                if (parentDepth != 0) {
                    if (parentDepth == 1) return "l_" + name;
                    return "l" + parentDepth + "_" + name;
                }
                return name;
            }

            private String nameLocal(int index) {
                int instanceOffset = isStatic ? 0 : 1;
                if (index == 0 && !isStatic) return "this";
                // If we are inside a lambda, we can use captured scope vars.
                //  This list will contain a 'this' param at index 0 for instance lambdas.
                //  This is ignored as per the above if block.
                //  This relies on the fact that lambdas have all captured variables declared first,
                //  prior to any parameters from their interface implementation.
                if (lambda != null && lambda.scopeVars.size() > index) return lambda.scopeVars.get(index);
                if (index < paramWidth + instanceOffset) return uniqueLocal("param" + (index - instanceOffset));
                return uniqueLocal("var" + index);
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                super.visitLocalVariable(nameLocal(index), descriptor, signature, start, end, index);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                    // Push all local variable load names into this list.
                    localNames.add(nameLocal(var));
                }
                super.visitVarInsn(opcode, var);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                if (ASMClassRemapper.LAMBDA_META_FACTORIES.contains(bootstrapMethodHandle)) {
                    Handle targetHandle = ((Handle) bootstrapMethodArguments[1]);
                    if (targetHandle.getOwner().equals(cName)) {
                        String tName = targetHandle.getName();
                        String tDesc = targetHandle.getDesc();
                        // The bytecode stack descriptor for the indy will contain the number of arguments bound to the lambda.
                        //  One of these will be a 'ALOAD 0' (this) for instance lambdas, we don't bother stripping that here.
                        //  Lambda indy will always load captured vars onto the stack from locals prior to the indy call.
                        int nArgs = Type.getArgumentTypes(descriptor).length;
                        List<String> vars = localNames.subList(localNames.size() - nArgs, localNames.size());
                        lambdaMap.put(tName + tDesc, new OuterLambdaScope(mName + mDesc, List.copyOf(vars)));
                    }
                }
            }

            @Override
            public void visitEnd() {
                remapper.storeMethodDepth(cName, name, desc, parentDepth + 1);
                super.visitEnd();
            }
        };
    }

    private static int getParamWidth(String desc) {
        int width = 0;
        for (Type arg : Type.getMethodType(desc).getArgumentTypes()) {
            width += arg.getSize();
        }
        return width;
    }

    private record OuterLambdaScope(String method, List<String> scopeVars) { }
}
