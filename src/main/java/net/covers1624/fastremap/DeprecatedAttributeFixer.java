package net.covers1624.fastremap;

import net.covers1624.quack.collection.FastStream;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Created by covers1624 on 2/21/26.
 */
public class DeprecatedAttributeFixer extends ClassVisitor {

    private final FileData.ClassFileData classData;

    public DeprecatedAttributeFixer(ClassVisitor cv, FileData.ClassFileData classData) {
        super(Opcodes.ASM9, cv);
        this.classData = classData;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if (classData.hasDeprecated()) {
            access |= Opcodes.ACC_DEPRECATED;
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        var data = FastStream.of(classData.fields())
                .filter(e->e.name().equals(name) && e.desc().toString().equals(descriptor))
                .firstOrDefault();
        if (data != null && data.hasDeprecated()) {
            access |= Opcodes.ACC_DEPRECATED;
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        var data = FastStream.of(classData.methods())
                .filter(e->e.name().equals(name) && e.desc().toString().equals(descriptor))
                .firstOrDefault();
        if (data != null && data.hasDeprecated()) {
            access |= Opcodes.ACC_DEPRECATED;
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
