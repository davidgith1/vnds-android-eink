package io.github.davidgith1.vndsandroideink.nscripter;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads NScripter's ".nsa" and ".sar" asset archive formats -- {@link #open} for the former,
 * {@link #openSar} for the latter; both feed the same {@link Entry}/{@link #find}/{@link #read}
 * surface, since a caller resolving a script asset by path doesn't care which container format it
 * came from. Reverse-engineered by black-box analysis of real archives (one real NSA archive:
 * 2918 entries, 115MB; one real SAR archive: 827 entries, 99MB) -- examining each archive's own
 * bytes directly (entry count, computed offsets, and JPEG/OGG/RIFF signatures at the computed
 * positions all lining up) rather than reading any reference engine's source, consistent with this
 * project's clean-room approach.
 *
 * <p>".nsa" layout:
 * <pre>
 * [2 bytes, big-endian]  entry count
 * [4 bytes, big-endian]  base offset (== byte offset where entry data starts; equals the total
 *                         header size, confirmed against a real archive)
 * for each entry:
 *   [null-terminated string]  path, backslash-separated (e.g. "dat\bg\bg01_1.jpg")
 *   [1 byte]                  compression type: 0 = none (verified via JPEG/OGG signatures at
 *                              the computed offset); 1 and 2 are real compressed variants seen in
 *                              the sample (used only for a handful of UI-chrome/transition-effect
 *                              bitmaps, never backgrounds/sprites/voice/music) whose algorithms
 *                              aren't reverse-engineered here -- {@link #read} throws for those
 *                              rather than guessing at a decompression scheme.
 *   [4 bytes, big-endian]     offset, relative to the base offset
 *   [4 bytes, big-endian]     compressed size (== original size when type is 0)
 *   [4 bytes, big-endian]     original (decompressed) size
 * </pre>
 *
 * <p>".sar" layout -- an older, simpler sibling format (real ONScripter-EN's SarReader.cpp is the
 * base every archive reader, NSA included, is built on): same entry count/base-offset header shape,
 * but each entry omits the compression-type byte and the separate original-size field entirely --
 * confirmed against a real archive (its stated entry count times a plausible per-entry size lines
 * up exactly with the stated base offset, and RIFF/JPEG signatures land exactly at each entry's
 * computed absolute offset):
 * <pre>
 * [2 bytes, big-endian]  entry count
 * [4 bytes, big-endian]  base offset
 * for each entry:
 *   [null-terminated string]  path, backslash-separated
 *   [4 bytes, big-endian]     offset, relative to the base offset
 *   [4 bytes, big-endian]     size (both compressed and original -- no separate field for real
 *                              ONScripter's rare ".nbz"-compressed entries either; {@link #read}
 *                              throws for those the same way it does for an unsupported .nsa type)
 * </pre>
 *
 * <p>Deliberately holds no open file handle between calls -- only the parsed entry index (a plain
 * in-memory map). {@link #read} opens, seeks, reads, and closes the archive file fresh each time.
 * {@link io.github.davidgith1.vndsandroideink.engine.VnEngine} has no lifecycle/dispose hook an instance
 * field holding a long-lived {@code RandomAccessFile} could be released from, and asset reads
 * happen once per {@code bg}/{@code ld}/{@code wave} command rather than in a hot per-frame loop,
 * so the extra open/close per read is a fair trade against a real file-handle leak.
 */
public final class NsArchiveReader {

    public static final class Entry {
        public final String path;
        public final int type;
        public final long offset;
        public final long compressedSize;
        public final long originalSize;

        Entry(String path, int type, long offset, long compressedSize, long originalSize) {
            this.path = path;
            this.type = type;
            this.offset = offset;
            this.compressedSize = compressedSize;
            this.originalSize = originalSize;
        }
    }

    private final File archiveFile;
    private final long baseOffset;
    private final Map<String, Entry> entriesByPath;
    private final Map<String, Entry> entriesByLowerPath;

    private NsArchiveReader(File archiveFile, long baseOffset,
                             Map<String, Entry> entriesByPath, Map<String, Entry> entriesByLowerPath) {
        this.archiveFile = archiveFile;
        this.baseOffset = baseOffset;
        this.entriesByPath = entriesByPath;
        this.entriesByLowerPath = entriesByLowerPath;
    }

    public static NsArchiveReader open(File archiveFile) throws IOException {
        Map<String, Entry> byPath = new HashMap<>();
        Map<String, Entry> byLowerPath = new HashMap<>();
        long baseOffset;
        try (RandomAccessFile raf = new RandomAccessFile(archiveFile, "r")) {
            int count = readUnsignedShort(raf);
            baseOffset = readUnsignedInt(raf);
            for (int i = 0; i < count; i++) {
                String path = readCString(raf);
                int type = raf.read();
                long offset = readUnsignedInt(raf);
                long compressedSize = readUnsignedInt(raf);
                long originalSize = readUnsignedInt(raf);
                Entry entry = new Entry(path, type, offset, compressedSize, originalSize);
                byPath.put(path, entry);
                byLowerPath.putIfAbsent(path.toLowerCase(java.util.Locale.ROOT), entry);
            }
        }
        return new NsArchiveReader(archiveFile, baseOffset, byPath, byLowerPath);
    }

    /** Opens a ".sar" archive -- see the class doc's layout note. Entries always have {@code
     * originalSize == compressedSize} since the format has no separate field for it; a ".nbz"-named
     * entry gets a nonzero {@code type} (real compression this reader doesn't decode) so {@link
     * #read} throws for it the same way an unsupported .nsa compression type does, rather than
     * silently handing back compressed bytes as if they were the real asset. */
    public static NsArchiveReader openSar(File archiveFile) throws IOException {
        Map<String, Entry> byPath = new HashMap<>();
        Map<String, Entry> byLowerPath = new HashMap<>();
        long baseOffset;
        try (RandomAccessFile raf = new RandomAccessFile(archiveFile, "r")) {
            int count = readUnsignedShort(raf);
            baseOffset = readUnsignedInt(raf);
            for (int i = 0; i < count; i++) {
                String path = readCString(raf);
                long offset = readUnsignedInt(raf);
                long size = readUnsignedInt(raf);
                // 4 = real ONScripter-EN's own NBZ_COMPRESSION enum value (see BaseReader.h) --
                // NOT 1 (that's SPB_COMPRESSION, a different, now-decoded format; see
                // NsArchiveCompression's class doc). A ".nbz" entry's real compression is inferred
                // purely from its own filename suffix, the same way real ONScripter-EN's own
                // getRegisteredCompressionType does for a SAR archive (which -- unlike NSA -- has no
                // per-entry compression-type byte to read one from at all).
                int type = path.toLowerCase(java.util.Locale.ROOT).endsWith(".nbz") ? 4 : 0;
                Entry entry = new Entry(path, type, offset, size, size);
                byPath.put(path, entry);
                byLowerPath.putIfAbsent(path.toLowerCase(java.util.Locale.ROOT), entry);
            }
        }
        return new NsArchiveReader(archiveFile, baseOffset, byPath, byLowerPath);
    }

    /** Looks up an entry by its stored path (backslash-separated -- these archives are always
     * built on Windows), exact match first, then case-insensitive -- the same tolerance
     * vnds.ScriptEngine.resolveAsset applies for real files, since archives are commonly authored
     * on case-insensitive filesystems too. {@code path} is normalized to backslashes first: a real
     * game's script may reference an asset with a forward slash ("image/36.jpg") for
     * an entry actually stored as "image\36.jpg", which a real ONScripter build tolerates (it
     * normalizes either separator to its own platform's), so a bare exact-match lookup here would
     * otherwise always miss it. */
    public Entry find(String path) {
        String normalized = path.replace('/', '\\');
        Entry exact = entriesByPath.get(normalized);
        if (exact != null) {
            return exact;
        }
        return entriesByLowerPath.get(normalized.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean contains(String path) {
        return find(path) != null;
    }

    /** Reads one entry's raw bytes, opening the archive file fresh for this call (see the class
     * doc), decoding real SPB ({@code type} 1) or LZSS ({@code type} 2) compression if present --
     * see {@link NsArchiveCompression}'s own class doc for what those are and why they matter (a
     * real game's whole title screen, in one observed case). {@code type} 4 (NBZ, a real bzip2-
     * based scheme, rare in practice -- seen only on ".nbz"-suffixed SAR entries) still isn't
     * decoded; {@link #read} throws for that one rather than guessing at it. */
    public byte[] read(Entry entry) throws IOException {
        byte[] data = new byte[(int) entry.compressedSize];
        try (RandomAccessFile raf = new RandomAccessFile(archiveFile, "r")) {
            raf.seek(baseOffset + entry.offset);
            raf.readFully(data);
        }
        switch (entry.type) {
            case 0:
                return data;
            case 1:
                return NsArchiveCompression.decodeSpb(data);
            case 2:
                return NsArchiveCompression.decodeLzss(data, (int) entry.originalSize);
            default:
                throw new UnsupportedOperationException(
                        "\"" + entry.path + "\" uses archive compression type " + entry.type
                                + ", which isn't reverse-engineered yet (only types 0/1/2 are supported).");
        }
    }

    private static int readUnsignedShort(RandomAccessFile f) throws IOException {
        int b0 = f.read();
        int b1 = f.read();
        if (b0 < 0 || b1 < 0) {
            throw new IOException("Unexpected end of file reading .nsa header");
        }
        return (b0 << 8) | b1;
    }

    private static long readUnsignedInt(RandomAccessFile f) throws IOException {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int b = f.read();
            if (b < 0) {
                throw new IOException("Unexpected end of file reading .nsa header");
            }
            v = (v << 8) | b;
        }
        return v;
    }

    /** Archive entry names are script-authored filenames, subject to the same Shift-JIS encoding
     * as script text itself (see {@code NsScriptSource}'s own Shift-JIS decoding) -- one real
     * game's archive happened to be all-ASCII (Shift-JIS and Latin-1 agree there), but a
     * non-ASCII (e.g. Japanese) entry name needs the whole accumulated byte run decoded together as
     * one multi-byte sequence, not char-by-char, to come out matching a script-side lookup string. */
    private static String readCString(RandomAccessFile f) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = f.read()) > 0) {
            buf.write(b);
        }
        if (b < 0) {
            throw new IOException("Unexpected end of file reading .nsa entry name");
        }
        return new String(buf.toByteArray(), java.nio.charset.Charset.forName("Shift_JIS"));
    }
}
