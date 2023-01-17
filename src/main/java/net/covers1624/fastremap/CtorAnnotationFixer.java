package net.covers1624.fastremap;

import org.objectweb.asm.*;

/**
 * Created by covers1624 on 16/1/23.
 */
public class CtorAnnotationFixer extends ClassVisitor {

    private boolean isEnum;
    private String cName;
    private String outerScope;

    public CtorAnnotationFixer(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        isEnum = (access & Opcodes.ACC_ENUM) != 0;
        cName = name;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        super.visitInnerClass(name, outerName, innerName, access);

        if ((access & Opcodes.ACC_STATIC) != 0) return; // Static classes are ignored.
        if (innerName == null) return; // Anon classes are ignored.

        // Relies on the fact that inner classes have an InnerClass attribute for themselves.
        assert outerScope == null;
        if (name.equals(cName)) {
            if (outerName != null) {
                outerScope = outerName;
            } else {
                // Local class, try and de-mangle the name.
                int idx = cName.lastIndexOf("$");
                if (idx != -1) {
                    outerScope = cName.substring(0, idx);
                }
            }
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        // We only care about constructors of either Enum classes, or inner/local classes.
        if (!name.equals("<init>") || !isEnum && outerScope == null) return mv;

        Type[] params = Type.getArgumentTypes(desc);
        int nSynthetic;
        if (isEnum) {
            if (params.length <= 2) return mv; // Ctor does not have any user-specified params, thus no annotations.
            if (!params[0].getInternalName().equals("java/lang/String")) return mv; // Must start with java/lang/String for enum constant name.
            if (params[1] != Type.INT_TYPE) return mv; // Second param must be enum constant id.
            nSynthetic = 2;
        } else {
            if (params.length <= 1) return mv; // Ctor does not have any user specified params, thus no annotations.
            if (!params[0].getInternalName().equals(outerScope)) return mv; // First param must be outer-scope.
            nSynthetic = 1;
        }

        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
                super.visitAnnotableParameterCount(parameterCount - nSynthetic, visible);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                if (parameter < nSynthetic) return null;
                return super.visitParameterAnnotation(parameter - nSynthetic, descriptor, visible);
            }
        };
    }
}
