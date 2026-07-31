package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

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

    /** No real sample pack needed -- {@code resolve()} checks/wipes the cache version marker
     * unconditionally, before ever touching an archive, so this is fully self-contained against a
     * plain {@link TemporaryFolder}. */
    @Test
    public void aStaleCacheFromAnOlderDecoderVersionIsWipedRatherThanServedForever() throws IOException {
        // A real bug this guards: NsArchiveReader.Entry#originalSize (the only thing an earlier
        // version of tryExtract's cache-hit check compared a cached file's length against) is a
        // property of the ARCHIVE itself, unaffected by a decoder bug fix -- so once an asset was
        // extracted once with an older, buggier NsArchiveCompression (e.g. before the real SPB
        // bit-consumption fix), its stale, WRONG bytes would be served forever afterward, even after
        // upgrading to a build with the fix, since the cached file's length still "matched". A
        // player who imported an affected VN before such a fix would keep seeing the old corruption
        // indefinitely with no way to self-heal short of manually deleting the VN and re-importing.
        File vnDir = tmp.getRoot();
        File staleFile = new File(vnDir, ".nsa_cache/some/stale_asset.bmp");
        staleFile.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(staleFile)) {
            out.write(new byte[]{1, 2, 3});
        }

        NsAssetResolver.resolve(vnDir, "nonexistent.jpg"); // any call: the version check runs first

        assertTrue("stale pre-migration cache entry should have been wiped", !staleFile.exists());
        File versionMarker = new File(vnDir, ".nsa_cache/.cache_version");
        assertTrue("a fresh version marker should have been written", versionMarker.isFile());
        byte[] markerBytes = new byte[(int) versionMarker.length()];
        try (FileInputStream in = new FileInputStream(versionMarker)) {
            in.read(markerBytes);
        }
        assertEquals("1", new String(markerBytes, java.nio.charset.StandardCharsets.US_ASCII));
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

    @Test
    public void arcNsaIsCheckedBeforeANumberedOverrideArchive() throws IOException {
        // Real ONScripter-EN's own NsaReader::processArchives opens "arc.nsa" first, then any
        // numbered "arc1.nsa"/"arc2.nsa"/... in order (see NsAssetResolver's class doc) -- before
        // this was supported, a single arbitrary ".nsa" file (whichever a bare directory listing
        // happened to return first) was picked, so a pack splitting assets across more than one
        // archive could easily have most of its own files silently missed.
        File vnDir = tmp.newFolder("vn");
        buildNsa(new File(vnDir, "arc.nsa"), "foo.jpg", new byte[]{1, 1, 1});
        // "foo.jpg" here is shadowed by arc.nsa's own copy; "onlyinarc1.jpg" only exists here.
        buildNsaMulti(new File(vnDir, "arc1.nsa"),
                new String[]{"foo.jpg", "onlyinarc1.jpg"},
                new byte[][]{{2, 2, 2}, {3, 3, 3}});

        File resolvedFoo = NsAssetResolver.resolve(vnDir, "foo.jpg");
        assertArrayEquals(new byte[]{1, 1, 1}, readHead(resolvedFoo, 3));

        File resolvedOnlyArc1 = NsAssetResolver.resolve(vnDir, "onlyinarc1.jpg");
        assertArrayEquals(new byte[]{3, 3, 3}, readHead(resolvedOnlyArc1, 3));
    }

    @Test
    public void sarArchiveIsOnlyConsultedAfterEveryNsaArchiveMisses() throws IOException {
        File vnDir = tmp.newFolder("vn2");
        buildNsa(new File(vnDir, "arc.nsa"), "foo.jpg", new byte[]{1, 1, 1});
        buildSar(new File(vnDir, "arc.sar"),
                new String[]{"foo.jpg", "onlyinsar.jpg"},
                new byte[][]{{9, 9, 9}, {4, 4, 4}});

        // "foo.jpg" exists in both -- the .nsa copy must win, matching real ONScripter's own
        // NSA-before-SAR precedence.
        File resolvedFoo = NsAssetResolver.resolve(vnDir, "foo.jpg");
        assertArrayEquals(new byte[]{1, 1, 1}, readHead(resolvedFoo, 3));

        // Only in arc.sar -- must still be found, falling through to the base archive.
        File resolvedOnlySar = NsAssetResolver.resolve(vnDir, "onlyinsar.jpg");
        assertArrayEquals(new byte[]{4, 4, 4}, readHead(resolvedOnlySar, 3));
    }

    @Test
    public void looseFileLookupFallsBackToACaseInsensitiveMatch() throws IOException {
        // Real NScripter packs are Windows-authored (case-insensitive filesystem); a script
        // routinely references a path in different case than the pack's own folder ended up named
        // once extracted onto a case-sensitive filesystem (Linux/Android) -- e.g. a real Tsukihime
        // script's "play \"*9\"" resolves to "cd\track09.ogg", but the pack's own folder on disk is
        // named "CD", not "cd".
        File vnDir = tmp.newFolder("vn3");
        File cdDir = new File(vnDir, "CD");
        cdDir.mkdirs();
        File track = new File(cdDir, "track09.ogg");
        try (FileOutputStream out = new FileOutputStream(track)) {
            out.write(new byte[]{5, 5, 5});
        }

        File resolved = NsAssetResolver.resolve(vnDir, "cd\\track09.ogg");
        assertEquals(track.getAbsolutePath(), resolved.getAbsolutePath());
    }

    @Test
    public void caseInsensitiveLookupCachesTheDirectoryListingAcrossCalls() throws IOException {
        // resolveLooseCaseInsensitive previously ran a fresh listFiles() scan per path segment on
        // EVERY call whose exact-path check missed -- for any game shipping assets via ".nsa"/
        // ".sar" (see the class doc), the loose file essentially never exists, so this ran on
        // nearly every single asset resolve. Proven here indirectly (this cache has no test seam
        // to intercept the actual listFiles() call): a file added to the directory AFTER the first
        // resolve -- which populates and caches that directory's listing -- must NOT be found by a
        // second resolve for it, since a real cache wouldn't re-scan; a re-scan-every-time
        // implementation would find it immediately, failing this assertion.
        File vnDir = tmp.newFolder("vn4");
        File cdDir = new File(vnDir, "CD");
        cdDir.mkdirs();

        // First call: nothing in "CD" yet, but this still scans (and caches) its listing.
        assertTrue(!NsAssetResolver.resolve(vnDir, "cd\\missing.ogg").exists());

        File lateFile = new File(cdDir, "late.ogg");
        try (FileOutputStream out = new FileOutputStream(lateFile)) {
            out.write(new byte[]{1});
        }
        File resolved = NsAssetResolver.resolve(vnDir, "cd\\late.ogg");
        // Falls back to the guessed (non-existent, per resolve()'s own tolerance) loose path,
        // proving the stale cached listing (from before "late.ogg" existed) was reused rather than
        // re-scanned.
        assertTrue("should not have found a file added after the directory listing was cached",
                !resolved.exists());
    }

    private static void buildNsa(File file, String entryPath, byte[] content) throws IOException {
        buildNsaMulti(file, new String[]{entryPath}, new byte[][]{content});
    }

    private static void buildNsaMulti(File file, String[] entryPaths, byte[][] contents) throws IOException {
        java.io.ByteArrayOutputStream headerEntries = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < entryPaths.length; i++) {
            headerEntries.write(entryPaths[i].getBytes(java.nio.charset.Charset.forName("Shift_JIS")));
            headerEntries.write(0);
            headerEntries.write(0); // compression type: uncompressed
            long offset = 0;
            for (int j = 0; j < i; j++) {
                offset += contents[j].length;
            }
            writeUnsignedInt(headerEntries, offset);
            writeUnsignedInt(headerEntries, contents[i].length);
            writeUnsignedInt(headerEntries, contents[i].length);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, entryPaths.length);
        writeUnsignedInt(out, 6 + headerEntries.size());
        out.write(headerEntries.toByteArray());
        for (byte[] content : contents) {
            out.write(content);
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
    }

    /** Like {@link #buildNsa}, but for a single entry with an explicit compression {@code type}
     * and {@code originalSize} field -- lets a test build a deliberately malformed type-1/2 entry
     * (a declared {@code originalSize} that doesn't fit in the signed 32-bit int {@link
     * NsArchiveReader#read} casts it to, or compressed bytes too short for {@link
     * NsArchiveCompression#decodeSpb} to even read its own 4-byte width/height header) without
     * needing a real archive sample. */
    private static void buildNsaWithType(File file, String entryPath, byte[] content, int type, long originalSize)
            throws IOException {
        java.io.ByteArrayOutputStream headerEntries = new java.io.ByteArrayOutputStream();
        headerEntries.write(entryPath.getBytes(java.nio.charset.Charset.forName("Shift_JIS")));
        headerEntries.write(0);
        headerEntries.write(type);
        writeUnsignedInt(headerEntries, 0); // offset
        writeUnsignedInt(headerEntries, content.length); // compressedSize
        writeUnsignedInt(headerEntries, originalSize);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, 1);
        writeUnsignedInt(out, 6 + headerEntries.size());
        out.write(headerEntries.toByteArray());
        out.write(content);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
    }

    @Test
    public void malformedLzssEntryDegradesToMissingInsteadOfCrashing() throws IOException {
        // A corrupt/truncated ".nsa" can declare an originalSize that doesn't survive
        // NsArchiveReader.read's cast to int (e.g. 0xFFFFFFFF, a plausible bit-flipped/truncated
        // 32-bit field) -- decodeLzss's very first line, "new byte[originalLength]", then throws
        // NegativeArraySizeException. Before this was caught, that propagated straight out of
        // resolve() and crashed the caller instead of degrading to "asset missing" the way every
        // other unsupported/corrupt case in this class already does.
        File vnDir = tmp.newFolder("vn_malformed_lzss");
        buildNsaWithType(new File(vnDir, "arc.nsa"), "bad.bmp", new byte[]{1, 2, 3},
                2, 0xFFFFFFFFL);

        // Doesn't throw; degrades to resolve()'s own documented "not found anywhere: hand back
        // the guessed (non-existent) path" tolerance, same as a genuinely missing asset.
        File resolved = NsAssetResolver.resolve(vnDir, "bad.bmp");
        assertTrue("guessed path should not actually exist", !resolved.exists());
    }

    @Test
    public void malformedSpbEntryDegradesToMissingInsteadOfCrashing() throws IOException {
        // A real SPB (type 1) entry's own decoder reads a 4-byte width/height header unconditionally
        // before anything else -- compressed bytes shorter than that (a truncated archive) throw
        // ArrayIndexOutOfBoundsException instead of a checked IOException.
        File vnDir = tmp.newFolder("vn_malformed_spb");
        buildNsaWithType(new File(vnDir, "arc.nsa"), "bad.bmp", new byte[]{1, 2}, 1, 2);

        File resolved = NsAssetResolver.resolve(vnDir, "bad.bmp");
        assertTrue("guessed path should not actually exist", !resolved.exists());
    }

    private static void buildSar(File file, String[] entryPaths, byte[][] contents) throws IOException {
        java.io.ByteArrayOutputStream headerEntries = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < entryPaths.length; i++) {
            headerEntries.write(entryPaths[i].getBytes(java.nio.charset.Charset.forName("Shift_JIS")));
            headerEntries.write(0);
            long offset = 0;
            for (int j = 0; j < i; j++) {
                offset += contents[j].length;
            }
            writeUnsignedInt(headerEntries, offset);
            writeUnsignedInt(headerEntries, contents[i].length);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, entryPaths.length);
        writeUnsignedInt(out, 6 + headerEntries.size());
        out.write(headerEntries.toByteArray());
        for (byte[] content : contents) {
            out.write(content);
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
    }

    private static void writeUnsignedShort(java.io.ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeUnsignedInt(java.io.ByteArrayOutputStream out, long v) {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }
}
