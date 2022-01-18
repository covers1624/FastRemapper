package net.covers1624.fastremap;

import net.minecraftforge.srgutils.IMappingFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Predicate;
import java.util.jar.Manifest;

/**
 * Created by covers1624 on 16/9/21.
 */
public class RemappingFileVisitor extends SimpleFileVisitor<Path> {

    private static final Logger LOGGER = LogManager.getLogger();

    private final boolean verbose;
    private final Path fromRoot;
    private final Path toRoot;
    private final IMappingFile mappings;
    private final Predicate<String> remapFilter;
    private final ASMRemapper remapper;
    private int remapCount = 0;

    public RemappingFileVisitor(boolean verbose, Path fromRoot, Path toRoot, IMappingFile mappings, Predicate<String> remapFilter) {
        this.verbose = verbose;
        this.fromRoot = fromRoot;
        this.toRoot = toRoot;
        this.mappings = mappings;
        this.remapFilter = remapFilter;
        remapper = new ASMRemapper(fromRoot, mappings);
    }

    @Override
    public FileVisitResult visitFile(Path inFile, BasicFileAttributes attrs) throws IOException {
        String rel = fromRoot.relativize(inFile).toString();
        // Strip Signing data.
        if (rel.endsWith(".SF") || rel.endsWith(".DSA") || rel.endsWith(".RSA")) return FileVisitResult.CONTINUE;

        if (rel.endsWith("META-INF/MANIFEST.MF")) {
            return processManifest(inFile, rel);
        }

        if (!rel.endsWith(".class") || !remapFilter.test(rel.replace('/', '.'))) {
            return copyRaw(inFile, rel);
        }

        String cName = rel.replace(".class", "");
        if (cName.startsWith("/")) {
            cName = cName.substring(1);
        }
        String mapped = mappings.remapClass(cName);

        ClassWriter writer = new ClassWriter(0);
        try (InputStream is = Files.newInputStream(inFile)) {
            ClassReader reader = new ClassReader(is);
            remapper.collectDirectSupertypes(reader);
            ClassRemapper remapper = new ASMClassRemapper(writer, this.remapper);
            reader.accept(remapper, 0);
        }

        if (verbose) {
            LOGGER.info("Mapping {} -> {}", cName, mapped);
        }
        Path outFile = toRoot.resolve(mapped + ".class");
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
        Path outFile = toRoot.resolve(rel);
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
        Path outFile = toRoot.resolve(rel);
        Files.createDirectories(outFile.getParent());
        Files.copy(inFile, outFile);
        return FileVisitResult.CONTINUE;
    }
}
