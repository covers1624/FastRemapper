/*
 * This file is part of Fast Remapper and is Licensed under the MIT License.
 *
 * Copyright (c) 2018-2021 covers1624 <https://github.com/covers1624>
 */
package net.covers1624.fastremap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.minecraftforge.srgutils.IMappingFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

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
            LOGGER.error("Expected '--output' not exist or be a file.");
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

        Predicate<String> remapFilter = e -> {
            for (String exclude : excludes) {
                if (e.startsWith(exclude)) return false;
            }
            return true;
        };

        boolean flipMappings = optSet.has(flipMappingsOpt);

        boolean verbose = optSet.has(verboseOpt);

        LOGGER.info("Fast Remapper.");
        LOGGER.info(" Input   : " + inputPath.toAbsolutePath());
        LOGGER.info(" Output  : " + outputPath.toAbsolutePath());
        LOGGER.info(" Mappings: " + mappingsPath.toAbsolutePath());
        LOGGER.info("");

        LOGGER.info("Loading mappings..");
        IMappingFile mappings;
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
            Path fromRoot = inputJar.getPath("/");
            Path toRoot = outJar.getPath("/");
            RemappingFileVisitor visitor = new RemappingFileVisitor(verbose, fromRoot, toRoot, mappings, remapFilter);
            Files.walkFileTree(fromRoot, visitor);
            remapCount = visitor.getRemapCount();
        }
        long end = System.nanoTime();
        LOGGER.info("Done. Remapped {} classes in {}", remapCount, formatDuration(end - start));

        return 0;
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
