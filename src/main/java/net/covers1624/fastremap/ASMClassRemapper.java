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

    public static final List<Handle> LAMBDA_META_FACTORIES = Arrays.asList(
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false),
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false)
    );
    private final ASMRemapper asmRemapper;

    public ASMClassRemapper(ClassVisitor cv, FastRemapper remapper) {
        super(Opcodes.ASM9, cv, remapper.getAsmRemapper());
        asmRemapper = remapper.getAsmRemapper();
    }

    @Override
    protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
        return new MethodRemapper(api, methodVisitor, asmRemapper) {

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                if (LAMBDA_META_FACTORIES.contains(bootstrapMethodHandle)) {
                    String owner = Type.getReturnType(descriptor).getInternalName();
                    String desc = ((Type) bootstrapMethodArguments[0]).getDescriptor();
                    name = remapper.mapMethodName(owner, name, desc);
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }
        };
    }
}
