package net.covers1624.fastremap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Created by covers1624 on 16/1/23.
 */
public class SourceAttributeFixer extends ClassVisitor {

    private String cName;

    public SourceAttributeFixer(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        cName = name;
    }

    @Override
    public void visitEnd() {
        assert cName != null;

        String name = cName;
        int lastSlash = cName.lastIndexOf('/');
        if (lastSlash != -1) {
            name = name.substring(lastSlash + 1);
        }
        int firstDollar = name.indexOf('$');
        if (firstDollar != -1) {
            name = name.substring(0, firstDollar);
        }
        name += ".java";
        visitSource(name, null);
        super.visitEnd();
    }
}
