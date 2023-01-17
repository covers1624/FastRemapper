package net.covers1624.fastremap;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;

import java.util.Arrays;
import java.util.List;

/**
 * Created by covers1624 on 17/9/21.
 */
public class ASMClassRemapper extends ClassRemapper {

    private static final List<Handle> META_FACTORIES = Arrays.asList(
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false),
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false)
    );
    private final FastRemapper remapper;
    private final ASMRemapper asmRemapper;

    public ASMClassRemapper(ClassVisitor cv, FastRemapper remapper) {
        super(Opcodes.ASM9, cv, remapper.getAsmRemapper());
        this.remapper = remapper;
        this.asmRemapper = remapper.getAsmRemapper();
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        return methodVisitor == null ? null : createMethodRemapper(descriptor, access, methodVisitor);
    }

    @Override
    protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
        return methodVisitor;
    }

    private MethodVisitor createMethodRemapper(String desc, int access, MethodVisitor methodVisitor) {
        int paramWidth = getParamWidth(desc);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        return new MethodRemapper(api, methodVisitor, asmRemapper) {

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                if (index == 0 && !isStatic) {
                    name = "this";
                } else if (index < (isStatic ? paramWidth : paramWidth + 1)) {
                    name = "p_" + ASMClassRemapper.this.remapper.nextParam();
                } else {
                    name = "var" + index;
                }

                super.visitLocalVariable(name, descriptor, signature, start, end, index);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                if (META_FACTORIES.contains(bootstrapMethodHandle)) {
                    String owner = Type.getReturnType(descriptor).getInternalName();
                    String desc = ((Type) bootstrapMethodArguments[0]).getDescriptor();
                    name = remapper.mapMethodName(owner, name, desc);
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
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
