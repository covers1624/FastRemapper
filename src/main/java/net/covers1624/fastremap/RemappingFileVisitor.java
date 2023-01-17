package net.covers1624.fastremap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.Manifest;

/**
 * Created by covers1624 on 16/9/21.
 */
public class RemappingFileVisitor extends SimpleFileVisitor<Path> {

    private static final Logger LOGGER = LogManager.getLogger();

    private final FastRemapper remapper;
    private final Path inputRoot;
    private final Path outputRoot;
    private final ASMRemapper asmRemapper;
    private int remapCount = 0;

    public RemappingFileVisitor(FastRemapper remapper) {
        this.remapper = remapper;
        inputRoot = remapper.getInputRoot();
        outputRoot = remapper.getOutputRoot();
        asmRemapper = remapper.getAsmRemapper();
    }

    @Override
    public FileVisitResult visitFile(Path inFile, BasicFileAttributes attrs) throws IOException {
        String rel = inputRoot.relativize(inFile).toString();
        // Strip Signing data.
        if (rel.endsWith(".SF") || rel.endsWith(".DSA") || rel.endsWith(".RSA") || remapper.isStripped(rel)) return FileVisitResult.CONTINUE;

        if (rel.endsWith("META-INF/MANIFEST.MF")) {
            return processManifest(inFile, rel);
        }

        if (!rel.endsWith(".class") || remapper.isExcluded(rel.replace('/', '.'))) {
            return copyRaw(inFile, rel);
        }

        String cName = rel.replace(".class", "");
        if (cName.startsWith("/")) {
            cName = cName.substring(1);
        }
        String mapped = remapper.getMappings().remapClass(cName);

        ClassWriter writer = new ClassWriter(0);
        try (InputStream is = Files.newInputStream(inFile)) {
            ClassReader reader = new ClassReader(is);
            asmRemapper.collectDirectSupertypes(reader);
            ClassVisitor cv = writer;
            // Applied in reverse order to what's shown here, remapper is always first.
            if (remapper.isFixSource()) {
                cv = new SourceAttributeFixer(cv);
            }
            if (remapper.isFixParamAnns()) {
                cv = new CtorAnnotationFixer(cv);
            }
            cv = new ASMClassRemapper(cv, remapper);
            if (remapper.isFixLocals()) {
                cv = new LocalVariableFixer(cv, remapper);
            }
            reader.accept(cv, 0);
        }

        if (remapper.isVerbose()) {
            LOGGER.info("Mapping {} -> {}", cName, mapped);
        }
        Path outFile = outputRoot.resolve(mapped + ".class");
        Files.createDirectories(outFile.getParent());
        try (OutputStream os = Files.newOutputStream(outFile)) {
            os.write(writer.toByteArray());
            os.flush();
        }

        remapCount++;
        return FileVisitResult.CONTINUE;
    }

    public int getRemapCount() {
        return remapCount;
    }

    private FileVisitResult processManifest(Path inFile, String rel) throws IOException {
        Path outFile = outputRoot.resolve(rel);
        Files.createDirectories(outFile.getParent());
        try (InputStream is = Files.newInputStream(inFile);
             OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE)) {
            Manifest manifest = new Manifest(is);
            // Clear signing info.
            manifest.getEntries().clear();
            manifest.write(os);
            os.flush();
        }
        return FileVisitResult.CONTINUE;
    }

    private FileVisitResult copyRaw(Path inFile, String rel) throws IOException {
        Path outFile = outputRoot.resolve(rel);
        Files.createDirectories(outFile.getParent());
        Files.copy(inFile, outFile);
        return FileVisitResult.CONTINUE;
    }
}
