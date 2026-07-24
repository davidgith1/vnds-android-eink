package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Opt-in real-sample test: automatically skipped (not failed) when no local sample pack is
 * present -- see .gitignore.
 *
 * <p>Verifies {@link NsArchiveReader} against real sample game archives -- the format was reverse-
 * engineered by examining a real game's "arc.nsa" exact bytes, so
 * re-checking against it (not just a hand-built fixture) is what actually confirms the
 * implementation matches reality; a second sample ("WanderersInTheSky") separately verifies {@link
 * NsArchiveReader#find}'s forward-slash tolerance (see {@link
 * #findNormalizesForwardSlashesToMatchBackslashStoredEntries}). These tests are skipped
 * automatically when the sample packs aren't present locally (see .gitignore).
 */
public class NsArchiveReaderTest {

    private static final File REAL_SAMPLE = findRealSample("a_dream_of_summer");
    private static final File FORWARD_SLASH_SAMPLE = findRealSample("WanderersInTheSky");

    private static File findRealSample(String packName) {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "Onscripter examples/" + packName + "/arc.nsa");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    public void parsesRealArchiveHeaderAndFirstEntries() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);

        NsArchiveReader.Entry first = reader.find("dat\\bg\\16.jpg");
        assertNotNull(first);
        assertEquals(0, first.type);
        assertEquals(0, first.offset);
        assertEquals(180742, first.compressedSize);
        assertEquals(180742, first.originalSize);

        // Verified by hand: entry 2's offset exactly equals entry 1's compressed size, i.e.
        // uncompressed entries are packed back-to-back with no gaps.
        NsArchiveReader.Entry second = reader.find("dat\\bg\\17.jpg");
        assertNotNull(second);
        assertEquals(first.compressedSize, second.offset);
    }

    @Test
    public void readsRawBytesThatAreAGenuineJpeg() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);
        NsArchiveReader.Entry entry = reader.find("dat\\bg\\16.jpg");
        byte[] data = reader.read(entry);
        assertEquals(180742, data.length);
        // JFIF/JPEG magic: FF D8 FF ...
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                java.util.Arrays.copyOf(data, 3));
    }

    @Test
    public void lookupIsCaseInsensitiveFallback() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);
        assertNotNull(reader.find("DAT\\BG\\16.JPG"));
    }

    @Test
    public void unknownPathReturnsNull() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);
        assertNull(reader.find("dat\\bg\\does_not_exist.jpg"));
    }

    @Test
    public void compressedEntryTypeThrowsRatherThanGuessing() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);
        NsArchiveReader.Entry compressed = reader.find("dat\\ef\\effect01.bmp");
        assertNotNull(compressed);
        assertEquals(2, compressed.type);
        assertThrows(UnsupportedOperationException.class, () -> reader.read(compressed));
    }

    @Test
    public void findNormalizesForwardSlashesToMatchBackslashStoredEntries() throws IOException {
        // Verified against a real sample game ("WanderersInTheSky"): its own script consistently
        // references every asset with a forward slash ("bg \"image/36.jpg\",3,500"), but the .nsa
        // archive itself stores the same entry backslash-separated ("image\36.jpg", built on
        // Windows) -- a bare exact/case-insensitive match on the un-normalized query always missed,
        // silently breaking every background/sprite/sound load in the whole game.
        assumeTrue("Real sample archive not found relative to the test working directory", FORWARD_SLASH_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(FORWARD_SLASH_SAMPLE);
        NsArchiveReader.Entry entry = reader.find("image/36.jpg");
        assertNotNull(entry);
        assertEquals(0, entry.type);
    }

    @Test
    public void lastEntryOffsetPlusSizeReachesExactlyEndOfFile() throws IOException {
        assumeTrue("Real sample archive not found relative to the test working directory", REAL_SAMPLE != null);
        NsArchiveReader reader = NsArchiveReader.open(REAL_SAMPLE);
        NsArchiveReader.Entry last = reader.find("dat\\voice\\9_2016.ogg");
        assertNotNull(last);
        byte[] data = reader.read(last);
        assertEquals(last.compressedSize, data.length);
    }

    @Test
    public void readsShiftJisEncodedNonAsciiFilename() throws IOException {
        // The real sample game's own archive happens to be all-ASCII, so it can't exercise this
        // path -- hand-build a minimal one-entry .nsa fixture with a Japanese filename instead.
        // Regression test for readCString decoding entry names byte-by-byte as Latin-1 instead of
        // Shift-JIS (the same encoding NsScriptSource already uses for script text/filenames),
        // which mangled any non-ASCII entry name into mojibake that could never match a script-side
        // lookup string.
        String path = "dat\\bg\\背景.jpg";
        byte[] content = {1, 2, 3, 4};
        File archive = buildSyntheticNsa(path, content);

        NsArchiveReader reader = NsArchiveReader.open(archive);
        NsArchiveReader.Entry entry = reader.find(path);
        assertNotNull(entry);
        assertArrayEquals(content, reader.read(entry));
    }

    private File buildSyntheticNsa(String entryPath, byte[] content) throws IOException {
        byte[] nameBytes = entryPath.getBytes(java.nio.charset.Charset.forName("Shift_JIS"));
        java.io.ByteArrayOutputStream entryBytes = new java.io.ByteArrayOutputStream();
        entryBytes.write(nameBytes);
        entryBytes.write(0); // null terminator
        entryBytes.write(0); // type: uncompressed
        writeUnsignedInt(entryBytes, 0); // offset, relative to base offset
        writeUnsignedInt(entryBytes, content.length);
        writeUnsignedInt(entryBytes, content.length);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, 1); // entry count
        int baseOffset = 6 + entryBytes.size(); // count(2) + baseOffset field(4) + entry bytes
        writeUnsignedInt(out, baseOffset);
        out.write(entryBytes.toByteArray());
        out.write(content);

        File file = File.createTempFile("ns_archive_reader_test", ".nsa");
        file.deleteOnExit();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
        return file;
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
