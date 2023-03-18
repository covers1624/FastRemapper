package net.covers1624.fastremap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.minecraftforge.srgutils.IMappingFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
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
public class FastRemapper {

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
        List<String> excludes = optSet.valuesOf(excludeOpt);
        List<String> strips = optSet.valuesOf(stripOpt);

        boolean flipMappings = optSet.has(flipMappingsOpt);

        boolean verbose = optSet.has(verboseOpt);

        boolean fixLocals = optSet.has(fixLocalsOpt);
        boolean fixSource = optSet.has(fixSourceOpt);
        boolean fixParamAnns = optSet.has(fixParamAnnotations);

        FastRemapper remapper = new FastRemapper(
                inputPath,
                outputPath,
                mappingsPath
        );
        remapper.excludes(excludes);
        remapper.strips(strips);
        remapper.flipMappings(flipMappings);
        remapper.verbose(verbose);
        remapper.fixLocals(fixLocals);
        remapper.fixSource(fixSource);
        remapper.fixParamAnns(fixParamAnns);
        remapper.fixStrippedCtors(optSet.has(fixStrippedCtors));

        remapper.run();
        return 0;
    }

    private final Path inputPath;

    private final Path outputPath;
    private final Path mappingsPath;

    private final List<String> excludes = new LinkedList<>();
    private final List<String> strips = new LinkedList<>();
    private boolean flipMappings;
    private boolean verbose;
    private boolean fixLocals;
    private boolean fixSource;
    private boolean fixParamAnns;
    private boolean fixStrippedCtors;

    private int remapCount;

    @Nullable
    private IMappingFile mappings;

    private final Map<String, byte[]> inputZip = new LinkedHashMap<>();

    @Nullable
    private ASMRemapper asmRemapper;

    private final Map<String, Integer> methodDepth = new HashMap<>();
    private final Map<String, Type[]> ctorParams = new HashMap<>();

    public FastRemapper(Path inputPath, Path outputPath, Path mappingsPath) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.mappingsPath = mappingsPath;
    }

    public void run() throws IOException {
        LOGGER.info("Fast Remapper.");
        LOGGER.info(" Input   : " + inputPath.toAbsolutePath());
        LOGGER.info(" Output  : " + outputPath.toAbsolutePath());
        LOGGER.info(" Mappings: " + mappingsPath.toAbsolutePath());
        LOGGER.info("");

        LOGGER.info("Loading mappings..");
        try (InputStream is = Files.newInputStream(mappingsPath)) {
            mappings = IMappingFile.load(is);
            if (flipMappings) {
                mappings = mappings.reverse();
            }
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

        asmRemapper = new ASMRemapper(this);

        LOGGER.info("Remapping...");
        long start = System.nanoTime();
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream outputZip = new ZipOutputStream(zipOut)) {
            for (Map.Entry<String, byte[]> entry : inputZip.entrySet()) {
                processEntry(entry.getKey(), entry.getValue(), outputZip);
            }
        }

        // We write the zip into ram as its faster for compression.
        LOGGER.info("Writing zip..");
        Files.write(outputPath, zipOut.toByteArray());
        long end = System.nanoTime();
        LOGGER.info("Done. Remapped {} classes in {}", remapCount, formatDuration(end - start));
    }

    public void processEntry(String name, byte[] data, ZipOutputStream outputZip) throws IOException {
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
        asmRemapper.collectDirectSupertypes(reader);

        ClassVisitor cv = cw;
        // Applied in reverse order to what's shown here, remapper is always first.
        if (fixSource) {
            cv = new SourceAttributeFixer(cv);
        }
        if (fixParamAnns) {
            cv = new CtorAnnotationFixer(cv);
        }
        cv = new ASMClassRemapper(cv, this);
        // Both of these need to load classes in some cases, thus must be run before the remapper.
        if (fixStrippedCtors) {
            cv = new StrippedCtorFixer(cv, this, false);
        }
        if (fixLocals) {
            cv = new LocalVariableFixer(cv, this);
        }
        reader.accept(cv, 0);
        String mapped = mappings.remapClass(cName);
        if (verbose) {
            LOGGER.info("Mapping {} -> {}", cName, mapped);
        }
        writeEntry(outputZip, mapped + ".class", cw.toByteArray());
        remapCount++;
    }

    private void processManifest(String name, byte[] data, ZipOutputStream outputZip) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(data));
        // Yeet signing data.
        manifest.getEntries().clear();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        manifest.write(bos);
        writeEntry(outputZip, name, bos.toByteArray());
    }

    private void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    public final boolean isExcluded(String path) {
        for (String exclude : excludes) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    public final boolean isStripped(String path) {
        for (String exclude : strips) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    // @formatter:off
    public final IMappingFile getMappings() { return Objects.requireNonNull(mappings); }
    public final ASMRemapper getAsmRemapper() { return Objects.requireNonNull(asmRemapper); }
    public final FastRemapper excludes(List<String> excludes) { this.excludes.addAll(excludes); return this; }
    public final FastRemapper strips(List<String> strips) { this.strips.addAll(strips); return this; }
    public final FastRemapper flipMappings(boolean flipMappings) { this.flipMappings = flipMappings; return this; }
    public final FastRemapper verbose(boolean verbose) { this.verbose = verbose; return this; }
    public final FastRemapper fixLocals(boolean fixLocals) { this.fixLocals = fixLocals; return this; }
    public final FastRemapper fixSource(boolean fixSource) { this.fixSource = fixSource; return this; }
    public final FastRemapper fixParamAnns(boolean fixParamAnns) { this.fixParamAnns = fixParamAnns; return this; }
    public final FastRemapper fixStrippedCtors(boolean fixStrippedCtors) { this.fixStrippedCtors = fixStrippedCtors; return this; }
    // @formatter:on

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
            asmRemapper.collectDirectSupertypes(reader); // May as well whilst we are here, can't hurt.
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
            asmRemapper.collectDirectSupertypes(reader);
            // Tell the StrippedCtorFixer to visit the class, this will trigger it to update the ctorParams cache.
            reader.accept(new StrippedCtorFixer(null, this, true), 0);
        } catch (IOException ex) {
            System.err.println("Failed to compute ctor params for: " + owner);
            return new Type[0];
        }
        return ctorParams.getOrDefault(owner, new Type[0]);
    }

    public static String formatDuration(long elapsedTimeInNs) {
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
