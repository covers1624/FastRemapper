package net.covers1624.fastremap;

import net.covers1624.fastremap.FileData.ClassFileData.FieldData;
import net.covers1624.quack.collection.ColUtils;
import net.covers1624.quack.collection.FastStream;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Created by covers1624 on 18/3/23.
 */
public final class StrippedCtorFixer extends ClassVisitor {

    private final FastRemapper fastRemapper;
    @Nullable
    private final ASMRemapper remapper;
    private final FileData.ClassFileData data;

    private final List<FieldData> finalFields;
    private final boolean ctorNeeded;
    private boolean hasInserted = false;

    public StrippedCtorFixer(ClassVisitor classVisitor, FastRemapper fastRemapper, @Nullable ASMRemapper remapper, FileData.ClassFileData data) {
        super(ASM9, classVisitor);
        this.fastRemapper = fastRemapper;
        this.remapper = remapper;
        this.data = data;

        finalFields = FastStream.of(data.fields())
                .filter(e -> (e.access() & ACC_STATIC) == 0 && (e.access() & ACC_FINAL) != 0 && !e.hasConstantValue())
                .toList();
        boolean hasCtor = ColUtils.anyMatch(data.methods(), e -> e.name().equals("<init>"));
        if (hasCtor) finalFields.clear(); // There are no fields to insert if we have a ctor of any kind.

        ctorNeeded = !hasCtor // If we dont have any constructors.
                     && (data.access() & ACC_SYNTHETIC) == 0 // Synthetic classes should never have constructors.
                     && (data.access() & ACC_INTERFACE) == 0; // The class must also not be an interface.
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        // We know there are no other constructors for the class, so to emit at the top of the class,
        // we just try to emit when we first see a method.
        emitConstructor();
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        // Try again to insert the constructor, perhaps the class doesn't have any other methods, just fields.
        emitConstructor();
        super.visitEnd();
    }

    private void emitConstructor() {
        if (!ctorNeeded) return;
        if (hasInserted) return;
        hasInserted = true;
        Type[] fieldTypes = FastStream.of(finalFields)
                .map(FieldData::desc)
                .toArray(new Type[0]);
        Type[] superParams = fastRemapper.getCtorParams(data.superType());

        Type[] params = new Type[superParams.length + fieldTypes.length];
        System.arraycopy(superParams, 0, params, 0, superParams.length);
        System.arraycopy(fieldTypes, 0, params, superParams.length, fieldTypes.length);

        MethodVisitor mv = super.visitMethod(
                ACC_PUBLIC,
                "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE, params),
                null,
                null
        );

        Label start = new Label();
        Label end = new Label();
        int localIdx = 0;

        mv.visitCode();
        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, localIdx++);
        for (Type pType : superParams) {
            mv.visitVarInsn(pType.getOpcode(ILOAD), localIdx);
            localIdx += pType.getSize();
        }
        mv.visitMethodInsn(INVOKESPECIAL, data.superType(), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, superParams), false);

        for (var fNode : finalFields) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(fNode.desc().getOpcode(ILOAD), localIdx);
            mv.visitFieldInsn(PUTFIELD, data.cName(), fNode.name(), fNode.desc().getDescriptor());
            localIdx += fNode.desc().getSize();
        }
        mv.visitInsn(RETURN);
        mv.visitLabel(end);

        // Emit a local variable table for the parameters. Unfortunately we cant emit these interleaved, as start/end labels need to already be emitted.
        int lvtIdx = 0;
        mv.visitLocalVariable("this", Type.getObjectType(data.cName()).getDescriptor(), null, start, end, lvtIdx++);
        for (Type pType : superParams) {
            mv.visitLocalVariable("super_param_" + lvtIdx, pType.getDescriptor(), null, start, end, lvtIdx);
            lvtIdx += pType.getSize();
        }

        for (var fNode : finalFields) {
            String name = remapper.mapFieldName(data.cName(), fNode.name(), fNode.desc().getDescriptor());
            mv.visitLocalVariable("p_" + name, fNode.desc().getDescriptor(), null, start, end, lvtIdx);
            lvtIdx += fNode.desc().getSize();
        }

        // We don't have frame computation or max computation turned on for speed.
        int maxStack = Math.max(
                1 + superParams.length,        // Max locals is this + all params for super ctor call.
                !finalFields.isEmpty() ? 2 : 1 // Max stack is either 1 when no fields, or 2 when fields are present.
        );
        mv.visitMaxs(maxStack, localIdx);
        mv.visitEnd();
    }
}
