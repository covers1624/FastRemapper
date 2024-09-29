package net.covers1624.fastremap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.List.of;

/**
 * Created by covers1624 on 16/9/21.
 */
public final class FastRemapper {

    public static void main(String[] args) throws Throwable {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(of("h", "help"), "Prints this help").forHelp();

        OptionSpec<Path> inputOpt = parser.acceptsAll(of("i", "input"), "Sets the input jar.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> outputOpt = parser.acceptsAll(of("o", "output"), "Sets the output jar.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> mappingsOpt = parser.acceptsAll(of("m", "mappings"), "The mappings to use. [Proguard,SRG,TSRG,TSRGv2,Tiny,Tinyv2]")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Void> flipMappingsOpt = parser.acceptsAll(of("f", "flip"), "Flip the input mappings. (Useful for proguard logs)");

        OptionSpec<String> excludeOpt = parser.acceptsAll(of("e", "exclude"), "Excludes a class or package from being remapped. Comma separated. Example: 'com.google.,org.apache.'")
                .withRequiredArg()
                .withValuesSeparatedBy(",");
        OptionSpec<String> stripOpt = parser.acceptsAll(of("s", "strip"), "Strip files from the output. Comma separated. Example: 'com/google,org/apache/,some/file.txt'")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<Void> mcBundleOpt = parser.acceptsAll(of("mc-bundle"), "Handle Modern Minecraft server bundles.");

        OptionSpec<Void> fixLocalsOpt = parser.acceptsAll(of("fix-locals"), "Restores the LocalVariable table, giving each local names again.");
        OptionSpec<Void> fixSourceOpt = parser.acceptsAll(of("fix-source"), "Recomputes source attributes.");
        OptionSpec<Void> fixParamAnnotations = parser.acceptsAll(of("fix-ctor-anns"), "Fixes constructor parameter annotation indexes from Proguard. WARN: This may break annotations if they have not been processed by Proguard.");
        OptionSpec<Void> fixStrippedCtors = parser.acceptsAll(of("fix-stripped-ctors"), "Restores constructors for classes with final fields, who's Constructors have been stripped by proguard.");

        OptionSpec<Void> verboseOpt = parser.acceptsAll(of("v", "verbose"), "Enables verbose logging.");

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            return -1;
        }

        Path inputPath = optSet.valueOf(inputOpt);
        if (Files.notExists(inputPath)) {
            System.err.println("Expected '--input' path to exist.");
            parser.printHelpOn(System.err);
            return -1;
        }
        if (!Files.isRegularFile(inputPath)) {
            System.err.println("Expected '--input' path to be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }

        Path outputPath = optSet.valueOf(outputOpt);
        if (Files.exists(outputPath) && !Files.isRegularFile(outputPath)) {
            System.err.println("Expected '--output' to not exist or be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }
        Files.deleteIfExists(outputPath);

        Path mappingsPath = optSet.valueOf(mappingsOpt);
        if (Files.notExists(mappingsPath)) {
            System.err.println("Expected '--mappings' path to exist.");
            parser.printHelpOn(System.err);
            return -1;
        }
        if (!Files.isRegularFile(mappingsPath)) {
            System.err.println("Expected '--mappings' path to be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }

        FastRemapper remapper = new FastRemapper(
                System.err,
                optSet.valuesOf(excludeOpt),
                optSet.valuesOf(stripOpt),
                optSet.has(flipMappingsOpt),
                optSet.has(verboseOpt),
                optSet.has(mcBundleOpt),
                optSet.has(fixLocalsOpt),
                optSet.has(fixSourceOpt),
                optSet.has(fixParamAnnotations),
                optSet.has(fixStrippedCtors)
        );

        remapper.run(inputPath, outputPath, mappingsPath);
        return 0;
    }

    private final PrintStream logger;

    private final List<String> excludes;
    private final List<String> strips;
    private final boolean flipMappings;
    private final boolean verbose;
    private final boolean mcBundle;
    private final boolean fixLocals;
    private final boolean fixSource;
    private final boolean fixParamAnns;
    private final boolean fixStrippedCtors;

    private final Map<String, byte[]> inputZip = new LinkedHashMap<>();

    private final Map<String, Integer> methodDepth = new HashMap<>();
    private final Map<String, Type[]> ctorParams = new HashMap<>();

    private int remapCount;

    public FastRemapper(PrintStream logger,
            List<String> excludes, List<String> strips,
            boolean flipMappings, boolean verbose, boolean mcBundle,
            boolean fixLocals, boolean fixSource, boolean fixParamAnns, boolean fixStrippedCtors) {
        this.logger = logger;
        this.excludes = new ArrayList<>(excludes);
        this.strips = new ArrayList<>(strips);
        this.flipMappings = flipMappings;
        this.verbose = verbose;
        this.mcBundle = mcBundle;
        this.fixLocals = fixLocals;
        this.fixSource = fixSource;
        this.fixParamAnns = fixParamAnns;
        this.fixStrippedCtors = fixStrippedCtors;
    }

    public void run(Path inputPath, Path outputPath, Path mappingsPath) throws IOException {
        logger.println("Fast Remapper.");
        logger.println(" Input   : " + inputPath.toAbsolutePath());
        logger.println(" Output  : " + outputPath.toAbsolutePath());
        logger.println(" Mappings: " + mappingsPath.toAbsolutePath());
        logger.println();

        logger.println("Loading mappings..");

        ASMRemapper remapper;
        try (InputStream is = Files.newInputStream(mappingsPath)) {
            IMappingFile mappings = IMappingFile.load(is);
            if (flipMappings) {
                mappings = mappings.reverse();
            }
            remapper = new ASMRemapper(this, mappings);
        }

        if (!mcBundle) {
            loadInput(Files.newInputStream(inputPath));
            byte[] remappedZip = doRemapping(remapper);

            logger.println("Writing zip..");
            Files.write(outputPath, remappedZip);
            logger.println("Done.");
        } else {
            String[] segs;
            logger.println("Opening bundle jar..");
            try (ZipFile zFile = new ZipFile(inputPath.toFile())) {
                ZipEntry listEntry = zFile.getEntry("META-INF/versions.list");
                if (listEntry == null) throw new RuntimeException("Jar is not a Minecraft server bundle.");

                String line = new String(zFile.getInputStream(listEntry).readAllBytes(), StandardCharsets.UTF_8).trim();
                segs = line.split("\t");
                if (segs.length != 3) throw new RuntimeException("More than one version?");

                ZipEntry serverJar = zFile.getEntry("META-INF/versions/" + segs[2]);
                if (serverJar == null) throw new RuntimeException("Server jar does not exists in bundle?");

                loadInput(zFile.getInputStream(serverJar));
            }

            byte[] output = doRemapping(remapper);
            segs[0] = Hashing.sha256(output);

            logger.println("Writing bundle har..");
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(inputPath));
                 ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(outputPath))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    zout.putNextEntry(new ZipEntry(entry.getName()));
                    if (entry.getName().equals("META-INF/versions.list")) {
                        zout.write(String.join("\t", segs).getBytes(StandardCharsets.UTF_8));
                    } else if (entry.getName().equals("META-INF/versions/" + segs[2])) {
                        zout.write(output);
                    } else {
                        zin.transferTo(zout);
                    }
                    zout.closeEntry();
                }
            }
            logger.println("Done.");
        }
    }

    private void loadInput(InputStream is) throws IOException {
        logger.println("Loading input zip..");
        try (ZipInputStream zin = new ZipInputStream(is)) {
            ZipEntry entry;
            ByteArrayOutputStream obuf = new ByteArrayOutputStream(32 * 1024 * 1024); // 32k
            while ((entry = zin.getNextEntry()) != null) {
                zin.transferTo(obuf);
                inputZip.put(entry.getName(), obuf.toByteArray());
                obuf.reset();
            }
        }
    }

    private byte[] doRemapping(ASMRemapper remapper) throws IOException {
        logger.println("Remapping...");
        long start = System.nanoTime();
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream outputZip = new ZipOutputStream(zipOut)) {
            for (Map.Entry<String, byte[]> entry : inputZip.entrySet()) {
                processEntry(remapper, entry.getKey(), entry.getValue(), outputZip);
            }
        }

        long end = System.nanoTime();
        logger.printf("Remapped %d classes in %s\n", remapCount, formatDuration(end - start));
        return zipOut.toByteArray();
    }

    private void processEntry(ASMRemapper remapper, String name, byte[] data, ZipOutputStream outputZip) throws IOException {
        // Strip signing data and any additional files.
        if (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || isStripped(name)) return;

        if (name.equals("META-INF/MANIFEST.MF")) {
            processManifest(name, data, outputZip);
            return;
        }

        if (!name.endsWith(".class") || isExcluded(name.replace('/', '.'))) {
            writeEntry(outputZip, name, data);
            return;
        }

        String cName;
        ClassWriter cw = new ClassWriter(0);
        ClassReader reader = new ClassReader(data);
        cName = reader.getClassName();
        remapper.collectDirectSupertypes(reader);

        ClassVisitor cv = buildTransformTree(remapper, cw);
        reader.accept(cv, 0);
        String mapped = remapper.mapType(cName);
        if (verbose) {
            logger.printf("Mapping %s -> %s\n", cName, mapped);
        }
        writeEntry(outputZip, mapped + ".class", cw.toByteArray());
        remapCount++;
    }

    @VisibleForTesting
    ClassVisitor buildTransformTree(ASMRemapper remapper, ClassVisitor cv) {
        // Applied in reverse order to what's shown here, remapper is always first.
        if (fixSource) {
            cv = new SourceAttributeFixer(cv);
        }
        if (fixParamAnns) {
            cv = new CtorAnnotationFixer(cv);
        }
        cv = new ASMClassRemapper(cv, remapper);
        // Both of these need to load classes in some cases, thus must be run before the remapper.
        if (fixStrippedCtors) {
            cv = new StrippedCtorFixer(cv, this, remapper, false);
        }
        if (fixLocals) {
            cv = new LocalVariableFixer(cv, this);
        }
        return cv;
    }

    private static void processManifest(String name, byte[] data, ZipOutputStream outputZip) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(data));
        // Yeet signing data.
        manifest.getEntries().clear();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        manifest.write(bos);
        writeEntry(outputZip, name, bos.toByteArray());
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private boolean isExcluded(String path) {
        for (String exclude : excludes) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStripped(String path) {
        for (String exclude : strips) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    public byte @Nullable [] getClassBytes(String cName) {
        return inputZip.get(cName + ".class");
    }

    public void storeMethodDepth(String owner, String name, String desc, int depth) {
        methodDepth.put(owner + "." + name + desc, depth);
    }

    public int getMethodDepth(String owner, String method) {
        String key = owner + "." + method;
        Integer depth = methodDepth.get(key);
        if (depth == null) {
            depth = computeMethodDepth(owner, method);
        }
        return depth;
    }

    private int computeMethodDepth(String owner, String method) {
        byte[] bytes = getClassBytes(owner);
        if (bytes == null) {
            logger.println("Unable to compute used locals for missing class+method: " + owner + "." + method);
            return 1;
        }

        ClassReader reader = new ClassReader(bytes);
        // Tell the LocalVariableFixer to visit the class, this will trigger it to update the methodDepth for each method.
        reader.accept(new LocalVariableFixer(null, this), 0);
        return methodDepth.getOrDefault(owner + "." + method, 1);
    }

    public void storeCtorParams(String owner, Type[] types) {
        ctorParams.put(owner, types);
    }

    public Type[] getCtorParams(String owner) {
        Type[] params = ctorParams.get(owner);
        if (params == null) {
            params = computeCtorParams(owner);
        }
        return params;
    }

    private Type[] computeCtorParams(String owner) {
        // Just yeets some logging, we can in theory make the JRE resolvable if we _really_ wanted to.
        if (owner.startsWith("java/lang/Object")) return new Type[0];

        byte[] bytes = getClassBytes(owner);
        if (bytes == null) {
            logger.println("Unable to compute ctor params for missing class: " + owner);
            return new Type[0];
        }

        ClassReader reader = new ClassReader(bytes);
        // Tell the StrippedCtorFixer to visit the class, this will trigger it to update the ctorParams cache.
        reader.accept(new StrippedCtorFixer(null, this, null, true), 0);
        return ctorParams.getOrDefault(owner, new Type[0]);
    }

    private static String formatDuration(long elapsedTimeInNs) {
        StringBuilder result = new StringBuilder();
        if (elapsedTimeInNs > 3600000000000L) {
            result.append(elapsedTimeInNs / 3600000000000L).append("h ");
        }

        if (elapsedTimeInNs > 60000000000L) {
            result.append(elapsedTimeInNs % 3600000000000L / 60000000000L).append("m ");
        }

        if (elapsedTimeInNs >= 1000000000L) {
            result.append(elapsedTimeInNs % 60000000000L / 1000000000L).append("s ");
        }

        if (elapsedTimeInNs >= 1000000L) {
            result.append(elapsedTimeInNs % 1000000000L / 1000000L).append("ms");
        }

        return result.toString();
    }
}
