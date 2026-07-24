package com.example.vndsandroideink.nscripter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a script-referenced asset path (e.g. "dat\bg\bg01_1.jpg", written with backslashes the
 * way real scripts do) to a real {@link File} the host's {@code VnEngine.Listener} callbacks can
 * hand to {@code BitmapFactory}/{@code MediaPlayer} -- checking a loose file under {@code vnDir}
 * first, then falling back to an ".nsa" archive if one is present (see {@link NsArchiveReader}).
 * An archive-only asset is extracted to a small on-disk cache the first time it's requested, since
 * the {@code Listener} contract is File-based, not byte-array-based.
 */
public final class NsAssetResolver {

    /** One parsed archive index per VN directory, kept for the process's lifetime. Cheap to keep
     * around: {@link NsArchiveReader} holds no open file handle (see its class doc), just an
     * in-memory path-to-entry map, so the only cost of not evicting this across many different VNs
     * visited in one process is a modest amount of retained memory, not leaked file descriptors. */
    private static final Map<String, NsArchiveReader> ARCHIVE_CACHE = new ConcurrentHashMap<>();

    private NsAssetResolver() {
    }

    public static File resolve(File vnDir, String scriptPath) {
        File loose = new File(vnDir, scriptPath.replace('\\', '/'));
        if (loose.exists()) {
            return loose;
        }

        NsArchiveReader archive = archiveFor(vnDir);
        if (archive != null) {
            NsArchiveReader.Entry entry = archive.find(scriptPath);
            if (entry != null && entry.type == 0) {
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
        } catch (IOException | UnsupportedOperationException e) {
            // Extraction failure (disk full, unsupported compression type, ...): fall back to the
            // loose-file guess, tolerated the same way a genuinely missing asset already is.
            return null;
        }
    }

    private static NsArchiveReader archiveFor(File vnDir) {
        String key = vnDir.getAbsolutePath();
        NsArchiveReader cached = ARCHIVE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        File nsa = findNsaFile(vnDir);
        if (nsa == null) {
            return null;
        }
        try {
            NsArchiveReader reader = NsArchiveReader.open(nsa);
            ARCHIVE_CACHE.put(key, reader);
            return reader;
        } catch (IOException e) {
            return null;
        }
    }

    private static File findNsaFile(File vnDir) {
        File[] files = vnDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nsa"));
        return files != null && files.length > 0 ? files[0] : null;
    }
}
