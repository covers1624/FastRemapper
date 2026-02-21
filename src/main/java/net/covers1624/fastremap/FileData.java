package net.covers1624.fastremap;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 2/15/26.
 */
public sealed interface FileData {

    static FileData create(String fName, byte[] data) {
        if (fName.endsWith(".class")) {
            return ClassFileData.create(data);
        }
        return new RegularFileData(data);
    }

    byte[] data();

    record RegularFileData(byte[] data) implements FileData { }

    record ClassFileData(
            int access,
            boolean hasDeprecated,
            String cName,
            @Nullable String superType,
            String @Nullable [] interfaces,
            List<FieldData> fields,
            List<MethodData> methods,
            byte[] data
    ) implements FileData {

        @Override
        public String superType() {
            return requireNonNull(superType, "Doesn't have a supertype.");
        }

        public record FieldData(int access, boolean hasDeprecated, String name, Type desc, boolean hasConstantValue) { }

        public record MethodData(int access, boolean hasDeprecated, String name, Type desc) { }

        public static ClassFileData create(byte[] bytes) {
            class Visitor extends ClassVisitor {

                public int access;
                public boolean hasDeprecated;
                public @Nullable String name;
                public @Nullable String superType;
                public String @Nullable [] interfaces;
                public final List<FieldData> fields = new ArrayList<>();
                public final List<MethodData> methods = new ArrayList<>();

                public Visitor() {
                    super(Opcodes.ASM9);
                }

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    this.access = access;
                    this.name = name;
                    this.superType = superName;
                    this.interfaces = interfaces;
                }

                @Nullable
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.equals("Ljava/lang/Deprecated;")) {
                        hasDeprecated = true;
                    }
                    return null;
                }

                @Nullable
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, @Nullable Object value) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        boolean hasDeprecated = false;

                        @Nullable
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (descriptor.equals("Ljava/lang/Deprecated;")) {
                                hasDeprecated = true;
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            fields.add(new FieldData(access, hasDeprecated, name, Type.getType(descriptor), value != null));
                        }
                    };
                }

                @Nullable
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        boolean hasDeprecated = false;

                        @Nullable
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (descriptor.equals("Ljava/lang/Deprecated;")) {
                                hasDeprecated = true;
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            methods.add(new MethodData(access, hasDeprecated, name, Type.getType(descriptor)));
                        }
                    };
                }
            }
            var reader = new ClassReader(bytes);
            var visitor = new Visitor();
            reader.accept(visitor, ClassReader.SKIP_CODE);
            return new ClassFileData(
                    visitor.access,
                    visitor.hasDeprecated,
                    requireNonNull(visitor.name),
                    visitor.superType,
                    visitor.interfaces,
                    List.copyOf(visitor.fields),
                    List.copyOf(visitor.methods),
                    bytes
            );
        }
    }
}
