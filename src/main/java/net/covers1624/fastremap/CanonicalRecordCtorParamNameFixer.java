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

        // Copy the names into a list of 'slots', where wide types take up 2 slots.
        // It's particularly annoying to work backwards when processing locals to find the parameter index, from the slot.
        List<String> slottedNames = new ArrayList<>();
        slottedNames.add("this"); // Unused, marker, spacer.
        int idx = 0;
        for (Type type : types) {
            slottedNames.add(names.get(idx));
            if (type.getSize() == 2) {
                slottedNames.add(names.get(idx));
            }
            idx++;
        }

        return new MethodVisitor(Opcodes.ASM9, mv) {
            private int pIndex = 0;

            @Override
            public void visitParameter(String name, int access) {
                super.visitParameter(names.get(pIndex++), access);
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                // Index here includes wide local slots.
                if (index != 0 && index < slottedNames.size()) {
                    name = slottedNames.get(index);
                }
                super.visitLocalVariable(name, descriptor, signature, start, end, index);
            }
        };
    }
}
