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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Nullable
    private IMappingFile mappings;
    @Nullable
    private Path inputRoot;
    @Nullable
    private Path outputRoot;

    @Nullable
    private ASMRemapper asmRemapper;

    private final Map<String, Integer> methodDepth = new HashMap<>();

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

        LOGGER.info("Remapping...");
        long start = System.nanoTime();
        int remapCount;
        try (FileSystem inputJar = IOUtils.getJarFileSystem(inputPath, true);
             FileSystem outJar = IOUtils.getJarFileSystem(outputPath, true)) {
            inputRoot = inputJar.getPath("/");
            outputRoot = outJar.getPath("/");
            asmRemapper = new ASMRemapper(inputRoot, mappings);

            RemappingFileVisitor visitor = new RemappingFileVisitor(this);
            Files.walkFileTree(inputRoot, visitor);
            remapCount = visitor.getRemapCount();
        }
        long end = System.nanoTime();
        LOGGER.info("Done. Remapped {} classes in {}", remapCount, formatDuration(end - start));
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
    public final Path getInputRoot() { return Objects.requireNonNull(inputRoot); }
    public final Path getOutputRoot() { return Objects.requireNonNull(outputRoot); }
    public final ASMRemapper getAsmRemapper() { return Objects.requireNonNull(asmRemapper); }
    public final boolean isVerbose() { return verbose; }
    public final boolean isFixLocals() { return fixLocals; }
    public final boolean isFixSource() { return fixSource; }
    public final boolean isFixParamAnns() { return fixParamAnns; }
    public final FastRemapper excludes(List<String> excludes) { this.excludes.addAll(excludes); return this; }
    public final FastRemapper strips(List<String> strips) { this.strips.addAll(strips); return this; }
    public final FastRemapper flipMappings(boolean flipMappings) { this.flipMappings = flipMappings; return this; }
    public final FastRemapper verbose(boolean verbose) { this.verbose = verbose; return this; }
    public final FastRemapper fixLocals(boolean fixLocals) { this.fixLocals = fixLocals; return this; }
    public final FastRemapper fixSource(boolean fixSource) { this.fixSource = fixSource; return this; }
    public final FastRemapper fixParamAnns(boolean fixParamAnn) { this.fixParamAnns = fixParamAnn; return this; }
    // @formatter:on

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
        try (InputStream is = Files.newInputStream(inputRoot.resolve(owner + ".class"))) {
            ClassReader reader = new ClassReader(is);
            asmRemapper.collectDirectSupertypes(reader); // May as well whilst we are here, can't hurt.
            // Tell the LocalVariableFixer to visit the class, this will trigger it to update the methodDepth for each method.
            reader.accept(new LocalVariableFixer(null, this), 0);
        } catch(IOException ex) {
            System.err.println("Failed to compute used locals for: " + owner + "." + method);
            ex.printStackTrace();
            return 1;
        }
        return methodDepth.getOrDefault(owner + "." + method, 1);
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
