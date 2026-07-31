package io.github.davidgith1.vndsandroideink.nscripter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a script-referenced asset path (e.g. "dat\bg\bg01_1.jpg", written with backslashes the
 * way real scripts do) to a real {@link File} the host's {@code VnEngine.Listener} callbacks can
 * hand to {@code BitmapFactory}/{@code MediaPlayer} -- checking a loose file under {@code vnDir}
 * first, then falling back to any archives present (see {@link NsArchiveReader}). An archive-only
 * asset is extracted to a small on-disk cache the first time it's requested, since the {@code
 * Listener} contract is File-based, not byte-array-based.
 *
 * <p>A real game commonly ships several archives at once, not just one: real ONScripter-EN's own
 * NsaReader::processArchives opens "arc.nsa" plus any numbered "arc1.nsa"/"arc2.nsa"/...
 * (override/patch archives, checked in that order, name-numbered contiguously from 1 -- the search
 * stops at the first missing number), and finally "arc.sar" as a base/fallback archive checked only
 * once every .nsa-family archive has missed. A pack that splits its assets this way (e.g. the bulk
 * of the art in "arc.sar" with a handful of overriding/patched files in "arc.nsa") needs every one
 * of these searched in this exact order -- picking just one archive file arbitrarily (as an earlier
 * version of this class did, via a single "first .nsa {@code listFiles} happens to return") could
 * easily land on the wrong one and silently miss most of the game's own art/audio.
 */
public final class NsAssetResolver {

    /** One parsed archive index list per VN directory, kept for the process's lifetime. Cheap to
     * keep around: {@link NsArchiveReader} holds no open file handle (see its class doc), just an
     * in-memory path-to-entry map, so the only cost of not evicting this across many different VNs
     * visited in one process is a modest amount of retained memory, not leaked file descriptors. */
    private static final Map<String, List<NsArchiveReader>> ARCHIVE_CACHE = new ConcurrentHashMap<>();

    /** Bumped whenever {@link NsArchiveCompression}'s own decode logic changes in a way that could
     * produce DIFFERENT bytes for the same compressed entry (e.g. a real bug fix to the SPB
     * decoder's bit-consumption) -- otherwise such a fix is invisible to any VN that already
     * extracted the affected assets under the OLDER, buggier decoder: {@link #tryExtract}'s
     * cache-hit check only compares the cached file's length against {@link
     * NsArchiveReader.Entry#originalSize}, a property of the ARCHIVE itself that a decoder fix never
     * changes, so a stale cached file's length still "matches" even though its actual CONTENT is
     * now known-wrong -- it would otherwise be served forever, never re-extracted. See {@link
     * #ensureCacheVersion}. */
    private static final int CACHE_FORMAT_VERSION = 1;

    /** {@code vnDir} absolute paths already version-checked this process lifetime -- see {@link
     * #ensureCacheVersion}; avoids re-stat'ing the marker file on every single asset resolve. */
    private static final Set<String> CACHE_VERSION_CHECKED = ConcurrentHashMap.newKeySet();

    private NsAssetResolver() {
    }

    public static File resolve(File vnDir, String scriptPath) {
        ensureCacheVersion(vnDir);
        File loose = new File(vnDir, scriptPath.replace('\\', '/'));
        if (loose.exists()) {
            return loose;
        }
        File looseCi = resolveLooseCaseInsensitive(vnDir, scriptPath);
        if (looseCi != null) {
            return looseCi;
        }

        for (NsArchiveReader archive : archivesFor(vnDir)) {
            NsArchiveReader.Entry entry = archive.find(scriptPath);
            if (entry != null) {
                // Not gated on entry.type here: NsArchiveReader.read() itself now decodes real SPB
                // (1) and LZSS (2) compression (see NsArchiveCompression), and throws
                // UnsupportedOperationException for whatever it genuinely can't (e.g. NBZ) -- which
                // tryExtract already catches and falls through from, the same tolerance a missing
                // asset gets.
                File extracted = tryExtract(vnDir, archive, entry);
                if (extracted != null) {
                    return extracted;
                }
            }
        }
        return loose; // not found anywhere: same "hand back the guessed path" tolerance
        // vnds.ScriptEngine.resolveAsset already relies on (BitmapFactory/MediaPlayer fail
        // gracefully on a nonexistent path rather than the engine layer needing to know).
    }

    /** Walks {@code scriptPath} one path segment at a time, matching each segment against the real
     * directory's children case-insensitively, and returns the real on-disk file if every segment
     * resolved -- null otherwise. Real NScripter packs are Windows-authored, where the filesystem
     * itself is case-insensitive, so a script routinely references a path in different case than
     * however the pack's own folders/files actually ended up named once extracted onto a
     * case-sensitive filesystem (Linux/Android) -- e.g. a real Tsukihime script's "play \"*8\""
     * (see NsCommandDispatcher's "play" handler) resolves to "cd\track08.ogg", but the pack's own
     * folder on disk is named "CD", not "cd"; a plain case-sensitive {@link File#exists()} check
     * alone would miss it even though the real asset is right there. Mirrors the case-insensitive
     * fallback {@link NsArchiveReader#find} already gives archive entries. */
    private static File resolveLooseCaseInsensitive(File vnDir, String scriptPath) {
        String[] segments = scriptPath.replace('\\', '/').split("/");
        File current = vnDir;
        for (String segment : segments) {
            File match = listingFor(current).get(segment.toLowerCase(java.util.Locale.ROOT));
            if (match == null) {
                return null;
            }
            current = match;
        }
        return current.isFile() ? current : null;
    }

    /** One directory's children, keyed by lowercased name, for {@link #resolveLooseCaseInsensitive}
     * -- cached per real directory path so a fresh {@code listFiles()} disk scan doesn't run for
     * every path segment on every single asset resolve whose exact-path check misses. For any game
     * shipping assets via ".nsa"/".sar" (see the class doc), the loose file essentially never
     * exists, so this directory walk previously ran uncached immediately before the archive lookup
     * that will actually succeed, on nearly every "bg"/"ld"/"wave" call in a session. Same
     * process-lifetime cache model as {@link #ARCHIVE_CACHE} (and for the same reason: a VN's own
     * on-disk file layout never changes while the app is running, so there's nothing to
     * invalidate) -- reuses {@link NsArchiveReader#byLowerPath}'s own lowercase-index pattern. */
    private static final Map<String, Map<String, File>> DIR_LISTING_CACHE = new ConcurrentHashMap<>();

    private static Map<String, File> listingFor(File dir) {
        String key = dir.getAbsolutePath();
        Map<String, File> cached = DIR_LISTING_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Map<String, File> listing = new java.util.HashMap<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                listing.putIfAbsent(child.getName().toLowerCase(java.util.Locale.ROOT), child);
            }
        }
        DIR_LISTING_CACHE.put(key, listing);
        return listing;
    }

    /** Wipes {@code vnDir}'s whole ".nsa_cache" directory the first time this VN is touched by a
     * process running a newer {@link #CACHE_FORMAT_VERSION} than whatever it was last extracted
     * with (tracked via a small marker file inside the cache dir itself, not a preference or
     * database this class would otherwise have no reason to depend on) -- see {@link
     * #CACHE_FORMAT_VERSION}'s own doc for why a plain length-based cache-hit check alone can't
     * detect this. A missing/corrupt marker (including "never extracted anything before this
     * feature existed at all") is treated the same as a mismatch: always safe, since the whole
     * directory is just a lazily-rebuilt derived cache, never the genuine save/import data. */
    private static void ensureCacheVersion(File vnDir) {
        String key = vnDir.getAbsolutePath();
        if (!CACHE_VERSION_CHECKED.add(key)) {
            return; // already checked this process lifetime
        }
        File cacheDir = new File(vnDir, ".nsa_cache");
        File versionFile = new File(cacheDir, ".cache_version");
        int existing = -1;
        if (versionFile.isFile()) {
            try {
                existing = Integer.parseInt(
                        new String(Files.readAllBytes(versionFile.toPath()), StandardCharsets.US_ASCII).trim());
            } catch (IOException | NumberFormatException e) {
                existing = -1;
            }
        }
        if (existing == CACHE_FORMAT_VERSION) {
            return;
        }
        deleteRecursive(cacheDir);
        if (cacheDir.mkdirs()) {
            try (FileOutputStream out = new FileOutputStream(versionFile)) {
                out.write(String.valueOf(CACHE_FORMAT_VERSION).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // Best-effort marker write: worst case, the next process launch re-checks (and
                // harmlessly re-wipes an already-empty cache dir) instead of remembering this one.
            }
        }
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static File tryExtract(File vnDir, NsArchiveReader archive, NsArchiveReader.Entry entry) {
        File cacheFile = new File(vnDir, ".nsa_cache/" + entry.path.replace('\\', '/'));
        if (cacheFile.exists() && cacheFile.length() == entry.originalSize) {
            return cacheFile; // already extracted in a previous run
        }
        try {
            byte[] data = archive.read(entry);
            File parent = cacheFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                out.write(data);
            }
            return cacheFile;
        } catch (IOException | RuntimeException e) {
            // Extraction failure (disk full, unsupported compression type, a corrupt/truncated
            // entry whose declared size or compressed bytes don't add up -- NsArchiveCompression's
            // decodeSpb/decodeLzss throw unchecked RuntimeExceptions, e.g.
            // NegativeArraySizeException/ArrayIndexOutOfBoundsException, straight from malformed
            // archive data rather than wrapping them in an IOException): fall back to the
            // loose-file guess, tolerated the same way a genuinely missing asset already is,
            // instead of crashing the reader thread over one bad archive entry.
            return null;
        }
    }

    /** Builds (and caches) the archive search list for {@code vnDir}, in real ONScripter-EN's own
     * precedence order -- see the class doc. Missing/unopenable archives are simply skipped rather
     * than aborting the whole list, the same tolerance a single missing/corrupt archive already got
     * before this class supported more than one. */
    private static List<NsArchiveReader> archivesFor(File vnDir) {
        String key = vnDir.getAbsolutePath();
        List<NsArchiveReader> cached = ARCHIVE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<NsArchiveReader> archives = new ArrayList<>();
        addIfOpenable(archives, new File(vnDir, "arc.nsa"), false);
        for (int n = 1; ; n++) {
            File numbered = new File(vnDir, "arc" + n + ".nsa");
            if (!numbered.isFile()) {
                break;
            }
            addIfOpenable(archives, numbered, false);
        }
        addIfOpenable(archives, new File(vnDir, "arc.sar"), true);
        ARCHIVE_CACHE.put(key, archives);
        return archives;
    }

    private static void addIfOpenable(List<NsArchiveReader> archives, File file, boolean isSar) {
        if (!file.isFile()) {
            return;
        }
        try {
            archives.add(isSar ? NsArchiveReader.openSar(file) : NsArchiveReader.open(file));
        } catch (IOException e) {
            // Corrupt/unreadable archive: skip it rather than aborting every other archive's lookup.
        }
    }
}
