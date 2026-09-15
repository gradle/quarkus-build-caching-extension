package com.gradle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Orders the {@code native-image} configuration Quarkus generates inside the runner jar, so that the cache key of the
 * native image generation stops moving on its own.
 *
 * <p>Quarkus writes {@code META-INF/native-image/*.json} from unordered collections: two builds of untouched sources
 * emit the same registrations in a different order. Since the jar is the cache key, each ordering is a separate entry
 * and an unchanged project keeps re-running {@code native-image} until every variant has been seen. Sorting those files
 * makes the key depend on what was registered rather than on the order it came out in.
 *
 * <p>This is sound where ignoring the files would not be: a registration added or removed still changes the key. What
 * is discarded is only the ordering, which those files do not give any meaning to.
 *
 * <p>Only {@code target/native-sources} is touched, which the second execution does not consume — it re-runs the
 * augmentation and builds its own jar — so this cannot change the executable that is produced.
 */
final class NativeImageConfigNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeImageConfigNormalizer.class);

    private static final String NATIVE_IMAGE_CONFIG_PREFIX = "META-INF/native-image/";
    private static final String JSON_SUFFIX = ".json";

    private NativeImageConfigNormalizer() {
    }

    /**
     * Rewrites every jar of {@code nativeSourcesDir} whose {@code native-image} configuration is not already ordered.
     */
    static void normalize(File nativeSourcesDir) {
        File[] jars = nativeSourcesDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return;
        }
        for (File jar : jars) {
            try {
                List<String> normalized = normalizeJar(jar);
                if (!normalized.isEmpty()) {
                    LOGGER.info(QuarkusExtensionUtil.getLogMessage("Ordered the native-image configuration of " + jar.getName() + ": " + String.join(", ", normalized)));
                }
            } catch (Exception e) {
                // the key is then as unstable as Quarkus left it, which is worse than this having worked but is not a
                // reason to fail the build
                LOGGER.warn(QuarkusExtensionUtil.getLogMessage("Unable to order the native-image configuration of " + jar + ", the native image cache key may move between builds"), e);
            }
        }
    }

    private static List<String> normalizeJar(File jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        List<String> normalized = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jar)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) {
                    entries.put(entry.getName(), null);
                    times.put(entry.getName(), entry.getTime());
                    continue;
                }
                byte[] content = readAll(zip.getInputStream(entry));
                if (isNativeImageConfig(entry.getName())) {
                    byte[] ordered = order(content, entry.getName());
                    if (ordered != null && !java.util.Arrays.equals(content, ordered)) {
                        content = ordered;
                        normalized.add(entry.getName().substring(NATIVE_IMAGE_CONFIG_PREFIX.length()));
                    }
                }
                entries.put(entry.getName(), content);
                times.put(entry.getName(), entry.getTime());
            }
        }

        if (normalized.isEmpty()) {
            return normalized;
        }

        Path rewritten = Files.createTempFile(jar.getParentFile().toPath(), jar.getName(), ".ordered");
        try (OutputStream out = Files.newOutputStream(rewritten);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry written = new ZipEntry(entry.getKey());
                written.setTime(times.get(entry.getKey()));
                zipOut.putNextEntry(written);
                if (entry.getValue() != null) {
                    zipOut.write(entry.getValue());
                }
                zipOut.closeEntry();
            }
        }
        Files.move(rewritten, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return normalized;
    }

    private static boolean isNativeImageConfig(String entryName) {
        return entryName.startsWith(NATIVE_IMAGE_CONFIG_PREFIX) && entryName.endsWith(JSON_SUFFIX);
    }

    /**
     * @return the ordered form, or {@code null} when the content is not JSON this can make sense of, in which case it
     *         is left exactly as Quarkus wrote it
     */
    private static byte[] order(byte[] content, String entryName) {
        try {
            String ordered = Json.canonical(Json.parse(new String(content, StandardCharsets.UTF_8)));
            return ordered.getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            LOGGER.debug(QuarkusExtensionUtil.getLogMessage("Leaving " + entryName + " as it is: " + e.getMessage()));
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

}
