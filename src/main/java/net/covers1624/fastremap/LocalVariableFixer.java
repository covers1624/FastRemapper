package net.covers1624.fastremap;

import org.objectweb.asm.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by covers1624 on 17/1/23.
 */
public class LocalVariableFixer extends ClassVisitor {

    // Lambda target method -> Outer owning method
    private final Map<String, String> lambdaMap = new HashMap<>();

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
        String lambda = lambdaMap.get(name + desc);
        if (lambda != null && (access & Opcodes.ACC_SYNTHETIC) != 0) {
            parentDepth = remapper.getMethodDepth(cName, lambda);
        } else if (outerMethodDepth != null) {
            parentDepth = outerMethodDepth;
        } else {
            parentDepth = 0; // Ugh, effectively final if it's in an else.
        }
        String mName = name;
        String mDesc = desc;
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {

            public String uniqueLocal(String name) {
                if (parentDepth != 0) {
                    if (parentDepth == 1) return "l_" + name;
                    return "l" + parentDepth + "_" + name;
                }
                return name;
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                if (index == 0 && !isStatic) {
                    name = "this";
                } else if (index < (isStatic ? paramWidth : paramWidth + 1)) {
                    name = uniqueLocal("param" + (index - (isStatic ? 0 : 1)));
                } else {
                    name = "var" + index;
//                    name = uniqueLocal("var" + index);
                }

                super.visitLocalVariable(name, descriptor, signature, start, end, index);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                if (ASMClassRemapper.LAMBDA_META_FACTORIES.contains(bootstrapMethodHandle)) {
                    Handle targetHandle = ((Handle) bootstrapMethodArguments[1]);
                    if (targetHandle.getOwner().equals(cName)) {
                        String tName = targetHandle.getName();
                        String tDesc = targetHandle.getDesc();
                        lambdaMap.put(tName + tDesc, mName + mDesc);
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
}
