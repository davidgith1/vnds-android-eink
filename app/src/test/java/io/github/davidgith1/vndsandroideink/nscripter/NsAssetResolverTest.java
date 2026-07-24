package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Opt-in real-sample test: automatically skipped (not failed) when no local sample pack is
 * present -- see .gitignore.
 *
 * <p>Verifies {@link NsAssetResolver} pulls an asset out of a real NScripter game's ".nsa" archive
 * when no loose file exists, since that's the
 * common case for a real game pack -- backgrounds/sprites/voice are archived, not loose files.
 * Skipped automatically when the sample pack isn't present locally (see .gitignore -- these
 * sample packs are kept locally for manual testing and aren't part of the repo).
 */
public class NsAssetResolverTest {

    private static final File VN_DIR = findVnDir();

    private static File findVnDir() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "Onscripter examples/a_dream_of_summer");
            if (new File(candidate, "arc.nsa").isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** This test writes into the real sample pack's own directory (the ".nsa_cache" location
     * production code would actually use) rather than a copy, since copying its 115MB archive
     * just to test extraction would be wasteful -- clean up afterward so the sample pack is left
     * exactly as found. */
    @After
    public void cleanUpExtractedCache() {
        if (VN_DIR != null) {
            deleteRecursively(new File(VN_DIR, ".nsa_cache"));
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }

    @Test
    public void extractsAnArchiveOnlyAssetToACacheFileWithMatchingContent() throws IOException {
        assumeTrue("Real sample pack not found relative to the test working directory", VN_DIR != null);
        File loose = new File(VN_DIR, "dat/bg/16.jpg");
        assumeTrue("This asset is expected to NOT exist as a loose file in the sample pack", !loose.exists());

        File resolved = NsAssetResolver.resolve(VN_DIR, "dat\\bg\\16.jpg");
        assertTrue("expected an extracted cache file to exist: " + resolved, resolved.exists());

        byte[] head = readHead(resolved, 3);
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, head);

        NsArchiveReader archive = NsArchiveReader.open(new File(VN_DIR, "arc.nsa"));
        NsArchiveReader.Entry entry = archive.find("dat\\bg\\16.jpg");
        assertEquals(entry.originalSize, resolved.length());
    }

    @Test
    public void secondResolveReusesTheCachedExtractionWithoutReExtracting() throws IOException {
        assumeTrue("Real sample pack not found relative to the test working directory", VN_DIR != null);
        File first = NsAssetResolver.resolve(VN_DIR, "dat\\bg\\17.jpg");
        long firstModified = first.lastModified();
        File second = NsAssetResolver.resolve(VN_DIR, "dat\\bg\\17.jpg");
        assertEquals(first.getAbsolutePath(), second.getAbsolutePath());
        assertEquals(firstModified, second.lastModified()); // not rewritten on the second call
    }

    private static byte[] readHead(File f, int n) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[n];
            int off = 0, r;
            while (off < n && (r = in.read(buf, off, n - off)) >= 0) {
                off += r;
            }
            return buf;
        }
    }
}
