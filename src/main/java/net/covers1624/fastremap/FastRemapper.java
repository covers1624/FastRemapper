package net.covers1624.fastremap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.minecraftforge.srgutils.IMappingFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.Arrays.asList;

/**
 * Created by covers1624 on 16/9/21.
 */
public final class FastRemapper {

    private static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) throws Throwable {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help").forHelp();

        OptionSpec<Path> inputOpt = parser.acceptsAll(asList("i", "input"), "Sets the input jar.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> outputOpt = parser.acceptsAll(asList("o", "output"), "Sets the output jar.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> mappingsOpt = parser.acceptsAll(asList("m", "mappings"), "The mappings to use. [Proguard,SRG,TSRG,TSRGv2,Tiny,Tinyv2]")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Void> flipMappingsOpt = parser.acceptsAll(asList("f", "flip"), "Flip the input mappings. (Useful for proguard logs)");

        OptionSpec<String> excludeOpt = parser.acceptsAll(asList("e", "exclude"), "Excludes a class or package from being remapped. Comma separated. Example: 'com.google.,org.apache.'")
                .withRequiredArg()
                .withValuesSeparatedBy(",");
        OptionSpec<String> stripOpt = parser.acceptsAll(asList("s", "strip"), "Strip files from the output. Comma separated. Example: 'com/google,org/apache/,some/file.txt'")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<Void> fixLocalsOpt = parser.acceptsAll(asList("fix-locals"), "Restores the LocalVariable table, giving each local names again.");
        OptionSpec<Void> fixSourceOpt = parser.acceptsAll(asList("fix-source"), "Recomputes source attributes.");
        OptionSpec<Void> fixParamAnnotations = parser.acceptsAll(asList("fix-ctor-anns"), "Fixes constructor parameter annotation indexes from Proguard. WARN: This may break annotations if they have not been processed by Proguard.");
        OptionSpec<Void> fixStrippedCtors = parser.acceptsAll(asList("fix-stripped-ctors"), "Restores constructors for classes with final fields, who's Constructors have been stripped by proguard.");

        OptionSpec<Void> verboseOpt = parser.acceptsAll(asList("v", "verbose"), "Enables verbose logging.");

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            return -1;
        }

        Path inputPath = optSet.valueOf(inputOpt);
        if (Files.notExists(inputPath)) {
            LOGGER.error("Expected '--input' path to exist.");
            parser.printHelpOn(System.err);
            return -1;
        }
        if (!Files.isRegularFile(inputPath)) {
            LOGGER.error("Expected '--input' path to be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }

        Path outputPath = optSet.valueOf(outputOpt);
        if (Files.exists(outputPath) && !Files.isRegularFile(outputPath)) {
            LOGGER.error("Expected '--output' to not exist or be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }
        Files.deleteIfExists(outputPath);

        Path mappingsPath = optSet.valueOf(mappingsOpt);
        if (Files.notExists(mappingsPath)) {
            LOGGER.error("Expected '--mappings' path to exist.");
            parser.printHelpOn(System.err);
            return -1;
        }
        if (!Files.isRegularFile(mappingsPath)) {
            LOGGER.error("Expected '--mappings' path to be a file.");
            parser.printHelpOn(System.err);
            return -1;
        }

        FastRemapper remapper = new FastRemapper(
                inputPath,
                outputPath,
                mappingsPath,
                optSet.valuesOf(excludeOpt),
                optSet.valuesOf(stripOpt),
                optSet.has(flipMappingsOpt),
                optSet.has(verboseOpt),
                optSet.has(fixLocalsOpt),
                optSet.has(fixSourceOpt),
                optSet.has(fixParamAnnotations),
                optSet.has(fixStrippedCtors)
        );

        remapper.run();
        return 0;
    }

    private final Path inputPath;

    private final Path outputPath;
    private final Path mappingsPath;

    private final List<String> excludes;
    private final List<String> strips;
    private final boolean flipMappings;
    private final boolean verbose;
    private final boolean fixLocals;
    private final boolean fixSource;
    private final boolean fixParamAnns;
    private final boolean fixStrippedCtors;

    private final Map<String, byte[]> inputZip = new LinkedHashMap<>();

    private final Map<String, Integer> methodDepth = new HashMap<>();
    private final Map<String, Type[]> ctorParams = new HashMap<>();

    private int remapCount;

    public FastRemapper(Path inputPath, Path outputPath, Path mappingsPath,
            List<String> excludes, List<String> strips,
            boolean flipMappings, boolean verbose, boolean fixLocals, boolean fixSource, boolean fixParamAnns, boolean fixStrippedCtors) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.mappingsPath = mappingsPath;
        this.excludes = new ArrayList<>(excludes);
        this.strips = new ArrayList<>(strips);
        this.flipMappings = flipMappings;
        this.verbose = verbose;
        this.fixLocals = fixLocals;
        this.fixSource = fixSource;
        this.fixParamAnns = fixParamAnns;
        this.fixStrippedCtors = fixStrippedCtors;
    }

    public void run() throws IOException {
        LOGGER.info("Fast Remapper.");
        LOGGER.info(" Input   : " + inputPath.toAbsolutePath());
        LOGGER.info(" Output  : " + outputPath.toAbsolutePath());
        LOGGER.info(" Mappings: " + mappingsPath.toAbsolutePath());
        LOGGER.info("");

        LOGGER.info("Loading mappings..");

        ASMRemapper remapper;
        try (InputStream is = Files.newInputStream(mappingsPath)) {
            IMappingFile mappings = IMappingFile.load(is);
            if (flipMappings) {
                mappings = mappings.reverse();
            }
            remapper = new ASMRemapper(this, mappings);
        }

        LOGGER.info("Loading input zip..");
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(inputPath))) {
            ZipEntry entry;
            ByteArrayOutputStream obuf = new ByteArrayOutputStream(32 * 1024 * 1024); // 32k
            while ((entry = zin.getNextEntry()) != null) {
                IOUtils.copy(zin, obuf);
                inputZip.put(entry.getName(), obuf.toByteArray());
                obuf.reset();
            }
        }

        LOGGER.info("Remapping...");
        long start = System.nanoTime();
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream outputZip = new ZipOutputStream(zipOut)) {
            for (Map.Entry<String, byte[]> entry : inputZip.entrySet()) {
                processEntry(remapper, entry.getKey(), entry.getValue(), outputZip);
            }
        }

        // We write the zip into ram as its faster for compression.
        LOGGER.info("Writing zip..");
        Files.write(outputPath, zipOut.toByteArray());
        long end = System.nanoTime();
        LOGGER.info("Done. Remapped {} classes in {}", remapCount, formatDuration(end - start));
    }

    public void processEntry(ASMRemapper remapper, String name, byte[] data, ZipOutputStream outputZip) throws IOException {
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

        ClassVisitor cv = cw;
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
        reader.accept(cv, 0);
        String mapped = remapper.mapType(cName);
        if (verbose) {
            LOGGER.info("Mapping {} -> {}", cName, mapped);
        }
        writeEntry(outputZip, mapped + ".class", cw.toByteArray());
        remapCount++;
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

    public InputStream openInputClass(String cName) throws IOException {
        byte[] data = inputZip.get(cName + ".class");
        if (data == null) throw new FileNotFoundException(cName);

        return new ByteArrayInputStream(data);
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
        try (InputStream is = openInputClass(owner)) {
            ClassReader reader = new ClassReader(is);
            // Tell the LocalVariableFixer to visit the class, this will trigger it to update the methodDepth for each method.
            reader.accept(new LocalVariableFixer(null, this), 0);
        } catch (IOException ex) {
            System.err.println("Failed to compute used locals for: " + owner + "." + method);
            ex.printStackTrace();
            return 1;
        }
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

        try (InputStream is = openInputClass(owner)) {
            ClassReader reader = new ClassReader(is);
            // Tell the StrippedCtorFixer to visit the class, this will trigger it to update the ctorParams cache.
            reader.accept(new StrippedCtorFixer(null, this, null, true), 0);
        } catch (IOException ex) {
            System.err.println("Failed to compute ctor params for: " + owner);
            return new Type[0];
        }
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
