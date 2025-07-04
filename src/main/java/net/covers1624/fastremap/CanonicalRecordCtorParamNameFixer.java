package net.covers1624.fastremap;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 7/4/25.
 */
public class CanonicalRecordCtorParamNameFixer extends ClassVisitor {

    private final List<String> names = new ArrayList<>();
    private final List<Type> types = new ArrayList<>();
    private @Nullable Type ctorType;

    public CanonicalRecordCtorParamNameFixer(ClassVisitor parent) {
        super(Opcodes.ASM9, parent);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if ((access & Opcodes.ACC_STATIC) == 0) {
            names.add(name);
            types.add(Type.getType(descriptor));
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!name.equals("<init>")) return mv;
        if (names.isEmpty()) return mv;

        if (ctorType == null) {
            ctorType = Type.getMethodType(Type.VOID_TYPE, types.toArray(Type[]::new));
        }
        // Canonical record constructors have the exact same parameters as the non-static fields in the class.
        if (!Type.getMethodType(descriptor).equals(ctorType)) {
            return mv;
        }

        return new MethodVisitor(Opcodes.ASM9, mv) {
            private int pIndex = 0;

            @Override
            public void visitParameter(String name, int access) {
                super.visitParameter(names.get(pIndex++), access);
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                var pIdx = index - 1;
                if (pIdx >= 0 && pIdx < names.size()) {
                    name = names.get(pIdx);
                }
                super.visitLocalVariable(name, descriptor, signature, start, end, index);
            }
        };
    }
}
