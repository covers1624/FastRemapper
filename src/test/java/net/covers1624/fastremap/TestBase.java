package net.covers1624.fastremap;

import net.minecraftforge.srgutils.IMappingBuilder;
import net.minecraftforge.srgutils.IMappingFile;
import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static net.covers1624.fastremap.TestBase.Flags.STRIP_CLASS_ATTRS;
import static net.covers1624.fastremap.TestBase.Flags.STRIP_FIELDS;

/**
 * Created by covers1624 on 21/12/23.
 */
class TestBase {

    protected static final IMappingFile NONE = IMappingBuilder.create("left", "right").build().getMap("left", "right");

    protected static String transform(Class<?> clazz, FastRemapper remapper, Flags... flags) {
        return transform(clazz, remapper, NONE, flags);
    }

    protected static String transform(Class<?> clazz, FastRemapper remapper, IMappingFile mappings, Flags... flags) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ClassReader cr = new ClassReader(getBytes(clazz));
        cr.accept(remapper.buildTransformTree(new ASMRemapper(remapper, mappings), cr, new FlagVisitor(new TraceClassVisitor(pw), List.of(flags))), 0);
        return sw.toString();
    }

    private static byte[] getBytes(Class<?> clazz) {
        try (InputStream is = TestBase.class.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class")) {
            return is.readAllBytes();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to get bytes for class.", ex);
        }
    }

    public enum Flags {
        STRIP_FIELDS,
        STRIP_CTOR,
        STRIP_CLASS_ATTRS,
        STRIP_LINE_NUMBERS,
    }

    private static class FlagVisitor extends ClassVisitor {

        private final List<Flags> flags;

        public FlagVisitor(ClassVisitor classVisitor, List<Flags> flags) {
            super(Opcodes.ASM9, classVisitor);
            this.flags = flags;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (flags.contains(Flags.STRIP_CTOR) && name.equals("<init>")) return null;

            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                @Override
                public void visitLineNumber(int line, Label start) {
                    if (flags.contains(Flags.STRIP_LINE_NUMBERS)) return;

                    super.visitLineNumber(line, start);
                }
            };
        }

        // @formatter:off
        @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) { return !flags.contains(STRIP_FIELDS) ? super.visitField(a, n, d, s, v) : null; }
        @Override public void visitSource(String s, String d) { if (!flags.contains(STRIP_CLASS_ATTRS)) super.visitSource(s, d); }
        @Override public void visitNestMember(String n) { if (!flags.contains(STRIP_CLASS_ATTRS)) super.visitNestMember(n); }
        @Override public void visitNestHost(String n) { if (!flags.contains(STRIP_CLASS_ATTRS)) super.visitNestHost(n); }
        @Override public void visitInnerClass(String n, String o, String i, int a) { if (!flags.contains(STRIP_CLASS_ATTRS)) super.visitInnerClass(n, o, i, a); }
        // @formatter:on
    }

}
