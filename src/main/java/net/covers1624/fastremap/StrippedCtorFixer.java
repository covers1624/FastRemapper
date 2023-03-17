package net.covers1624.fastremap;

import net.covers1624.quack.collection.FastStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;

import java.util.LinkedList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Created by covers1624 on 18/3/23.
 */
public class StrippedCtorFixer extends ClassVisitor {

    private static final Logger LOGGER = LogManager.getLogger();

    private final FastRemapper fastRemapper;
    // Flag used to know if we need are re-entering another class to compute.
    private final boolean forceCompute;

    private final List<FieldNode> finalFields = new LinkedList<>();
    private boolean hasCtors = false;

    private String cName;
    private String sName;

    public StrippedCtorFixer(ClassVisitor classVisitor, FastRemapper fastRemapper, boolean forceCompute) {
        super(ASM9, classVisitor);
        this.fastRemapper = fastRemapper;
        this.forceCompute = forceCompute;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        cName = name;
        sName = superName;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if ((access & ACC_STATIC) == 0 && (access & ACC_FINAL) != 0 && value == null) {
            finalFields.add(new FieldNode(name, Type.getType(descriptor)));
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (!hasCtors && name.equals("<init>")) {
            hasCtors = true;
            finalFields.clear();

            if (forceCompute) {
                fastRemapper.storeCtorParams(cName, Type.getArgumentTypes(descriptor));
            }
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        if (!hasCtors && !finalFields.isEmpty()) {
            Type[] fieldTypes = FastStream.of(finalFields)
                    .map(e -> e.desc)
                    .toArray(new Type[0]);
            Type[] superParams = fastRemapper.getCtorParams(sName);

            Type[] params = new Type[superParams.length + fieldTypes.length];
            System.arraycopy(superParams, 0, params, 0, superParams.length);
            System.arraycopy(fieldTypes, 0, params, superParams.length, fieldTypes.length);

            fastRemapper.storeCtorParams(cName, params);

            MethodVisitor mv = super.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    Type.getMethodDescriptor(Type.VOID_TYPE, params),
                    null,
                    null
            );
            if (mv == null) return;

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
            mv.visitMethodInsn(INVOKESPECIAL, sName, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, superParams), false);

            for (FieldNode fNode : finalFields) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(fNode.desc.getOpcode(ILOAD), localIdx);
                mv.visitFieldInsn(PUTFIELD, cName, fNode.name, fNode.desc.getDescriptor());
                localIdx += fNode.desc.getSize();
            }
            mv.visitInsn(RETURN);
            mv.visitLabel(end);

            // Emit a local variable table for the parameters. Unfortunately we cant emit these interleaved, as start/end labels need to already be emitted.
            int lvtIdx = 0;
            mv.visitLocalVariable("this", Type.getObjectType(cName).getDescriptor(), null, start, end, lvtIdx++);
            for (Type pType : superParams) {
                mv.visitLocalVariable("super_param_" + lvtIdx, pType.getDescriptor(), null, start, end, lvtIdx);
                lvtIdx += pType.getSize();
            }

            for (FieldNode fNode : finalFields) {
                String name = fastRemapper.getAsmRemapper().mapFieldName(cName, fNode.name, fNode.desc.getDescriptor());
                mv.visitLocalVariable("p_" + name, fNode.desc.getDescriptor(), null, start, end, lvtIdx);
                lvtIdx += fNode.desc.getSize();
            }

            // We don't have frame computation or max computation turned on for speed.
            int maxStack = Math.max(
                    1 + superParams.length, // Mac stack for super ctor call.
                    2                       // Max stack for field loads.
            );
            mv.visitMaxs(maxStack, localIdx);
            mv.visitEnd();
        }

        super.visitEnd();
    }

    private static class FieldNode {

        public final String name;
        public final Type desc;

        private FieldNode(String name, Type desc) {
            this.name = name;
            this.desc = desc;
        }
    }
}
