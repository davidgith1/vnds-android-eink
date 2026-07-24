package io.github.davidgith1.vndsandroideink;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import androidx.documentfile.provider.DocumentFile;

import io.github.davidgith1.vndsandroideink.nscripter.NsSaveManager;
import io.github.davidgith1.vndsandroideink.nscripter.NsScriptSource;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Imports VNDS story packs into app-private storage (<code>filesDir/vns/&lt;sanitized-name&gt;/</code>)
 * so the reader can use plain File I/O (fast random access, works with MediaPlayer) instead of
 * slow/limited SAF DocumentFile access. Importing only ever happens when the user explicitly asks
 * for it (the library's "+ Import novel" row); {@link #loadLocalLibrary} is what runs on every
 * ordinary app launch, and it never touches SAF at all.
 */
public final class VnImporter {

    public interface Callback {
        void onComplete(List<VnEntry> entries);
        void onError(Exception e);
        /** Optional human-readable progress update (e.g. "Copying archive… 45%") for long-running
         * imports; a large archive's copy+extract can take minutes, and with nothing else on
         * screen an e-ink device shows no sign it's even alive otherwise. Most callers don't care. */
        default void onProgress(String message) {
        }
        /** Called instead of {@link #onError} when the import stopped because the caller requested
         * cancellation (see {@link #importArchive}), not because of a real failure. */
        default void onCancelled() {
        }
    }

    /** Thrown from a {@link ProgressListener} checkpoint to unwind out of a copy/extract loop when
     * the caller has requested cancellation; caught separately from real errors so
     * {@link Callback#onCancelled} fires instead of {@link Callback#onError}. */
    private static final class ImportCancelledException extends IOException {
        ImportCancelledException() {
            super("Import cancelled");
        }
    }

    /** Reports cumulative bytes processed so far; total is fixed per call site (baked into the
     * listener via {@link #throttledProgress}), not passed per-call. Declared to throw so a
     * cancellation checkpoint can unwind the copy/extract loop calling it. */
    private interface ProgressListener {
        void onBytesDone(long done) throws IOException;
    }

    /** The actual background work behind one of this class's public entry points, run on a
     * worker thread by {@link #runAsync}; {@code main} is only needed by {@link #importArchive}
     * to post progress updates mid-task. */
    private interface ImportTask {
        void run(Context appContext, Handler main) throws Exception;
    }

    private VnImporter() {
    }

    /** Runs {@code task} on a background thread against the application context, then always
     * rescans the local library and posts exactly one of {@link Callback#onComplete},
     * {@link Callback#onCancelled} (if the task unwound via {@link ImportCancelledException}), or
     * {@link Callback#onError} back to the main thread. Shared by every public entry point below
     * so the thread/Handler/try-catch scaffolding isn't repeated per method. */
    private static void runAsync(Context context, ImportTask task, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                task.run(appContext, main);
                List<VnEntry> result = scanLocal(appContext);
                main.post(() -> callback.onComplete(result));
            } catch (ImportCancelledException e) {
                main.post(callback::onCancelled);
            } catch (Exception e) {
                main.post(() -> callback.onError(e));
            }
        }).start();
    }

    /** Lists whatever has already been imported, purely from local storage -- no SAF, no copying. */
    public static void loadLocalLibrary(Context context, Callback callback) {
        runAsync(context, (appContext, main) -> {
        }, callback);
    }

    /** Imports every recognizable VN subfolder under a user-picked tree (a folder that may itself
     * contain several story packs), then returns the full updated local library. */
    public static void scanAndImportTree(Context context, Uri treeUri, Callback callback) {
        runAsync(context, (appContext, main) -> importTree(appContext, treeUri), callback);
    }

    /** Imports a single VN from a user-picked tree that is itself that VN's own folder (img.ini
     * etc. sit directly at the tree's root, rather than one level down under subfolders), then
     * returns the full updated local library. */
    public static void scanAndImportSingleFolder(Context context, Uri treeUri, Callback callback) {
        runAsync(context, (appContext, main) -> {
            DocumentFile root = DocumentFile.fromTreeUri(appContext, treeUri);
            if (root == null || !root.isDirectory()) {
                throw new IOException("Could not open the picked folder");
            }
            File vnsRoot = new File(appContext.getFilesDir(), "vns");
            vnsRoot.mkdirs();
            if (!importSingleVnFolder(appContext, root, vnsRoot)) {
                throw new IOException("Not a recognizable VN folder (missing img.ini or script/)");
            }
        }, callback);
    }

    /** Imports a single VN whose entire folder was archived up as one file (zip or 7z), then
     * returns the full updated local library. {@code cancelRequested} is polled periodically
     * during the copy and extraction phases (the only ones slow enough to matter); setting it at
     * any point makes the import unwind and call {@link Callback#onCancelled} instead of
     * completing, with any partial extraction cleaned up first. */
    public static void importArchive(Context context, Uri archiveUri, AtomicBoolean cancelRequested, Callback callback) {
        runAsync(context, (appContext, main) ->
                importArchiveFile(appContext, archiveUri, callback, main, cancelRequested), callback);
    }

    /** Deletes an imported VN's local copy (and its save data) entirely, then returns the updated
     * local library. Irreversible: the caller is expected to have already confirmed with the user. */
    public static void deleteLocal(Context context, VnEntry entry, Callback callback) {
        runAsync(context, (appContext, main) -> {
            deleteRecursive(entry.localDir);
            SaveManager.deleteAll(appContext, entry.localDir.getName());
            VndbManager.clear(appContext, entry.localDir.getName());
            GuideManager.clearProgress(appContext, entry.localDir.getName());
            TitleOverrideManager.clear(appContext, entry.localDir.getName());
        }, callback);
    }

    private static List<VnEntry> scanLocal(Context context) {
        List<VnEntry> result = new ArrayList<>();
        File vnsRoot = new File(context.getFilesDir(), "vns");
        File[] children = vnsRoot.listFiles();
        if (children != null) {
            for (File dir : children) {
                if (dir.isDirectory() && new File(dir, ".imported").exists()) {
                    File icon = new File(dir, "icon.png");
                    String vnKey = dir.getName();
                    VnEntry.EngineType engineType = readEngineMarker(dir);
                    boolean hasResume = engineType == VnEntry.EngineType.NSCRIPTER
                            ? NsSaveManager.hasResume(context, vnKey)
                            : SaveManager.hasResume(context, vnKey);
                    String title = vnKey;
                    String subtitle = null;
                    if (engineType == VnEntry.EngineType.NSCRIPTER) {
                        // NScripter packs never ship an info.txt (that's VNDS-only), so readTitle's
                        // own fallback below would always be the raw folder name -- prefer the
                        // script's own declared title/subtitle instead (see peekTitleInfo's doc).
                        NsScriptSource.NsTitleInfo info = NsScriptSource.peekTitleInfo(dir);
                        if (info.title != null) {
                            title = info.title;
                        }
                        subtitle = info.subtitle;
                    }
                    String displayTitle = readTitle(dir, title);
                    String override = TitleOverrideManager.load(context, vnKey);
                    if (override != null) {
                        displayTitle = override;
                    }
                    result.add(new VnEntry(displayTitle, subtitle, dir, icon.exists() ? icon : null,
                            hasResume, SaveManager.getTotalPlayMillis(context, vnKey),
                            VndbManager.load(context, vnKey), engineType));
                }
            }
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    /** Reads the ".engine" marker written alongside ".imported" at import time (see {@link
     * #writeEngineMarker}); defaults to VNDS for any pack imported before this marker existed
     * (or if it's somehow missing/corrupt) -- VNDS was the only format this app ever supported
     * until now, so that's the only correct default for pre-existing local copies. */
    private static VnEntry.EngineType readEngineMarker(File localDir) {
        File marker = new File(localDir, ".engine");
        if (marker.isFile()) {
            try (FileInputStream in = new FileInputStream(marker)) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[64];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return VnEntry.EngineType.valueOf(buf.toString(StandardCharsets.UTF_8.name()).trim());
            } catch (IOException | IllegalArgumentException e) {
                // Fall through to the VNDS default below.
            }
        }
        return VnEntry.EngineType.VNDS;
    }

    private static void writeEngineMarker(File localDir, VnEntry.EngineType type) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(localDir, ".engine"))) {
            out.write(type.name().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Plain-text/obfuscated NScripter script filenames recognized at a VN folder's root when it
     * has no img.ini (see {@link #importSingleVnFolder}) -- kept in sync with {@code
     * NsScriptSource}'s own candidate lists, but duplicated rather than imported from the
     * nscripter package: detection here only needs filenames, not the package's loading/decoding
     * logic, and VnImporter deliberately never interprets script content either way (see this
     * class's doc). */
    private static final String[] NSCRIPTER_MARKER_FILES = {
            "0.txt", "00.txt", "0.utf.txt", "0.utf", "00.utf.txt", "00.utf",
            "nscript.dat", "nscr_sec.dat", "pscript.dat"
    };

    private static boolean looksLikeNScripterFolder(DocumentFile folder) {
        for (String name : NSCRIPTER_MARKER_FILES) {
            if (folder.findFile(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static void importTree(Context context, Uri treeUri) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.isDirectory()) {
            return;
        }

        File vnsRoot = new File(context.getFilesDir(), "vns");
        vnsRoot.mkdirs();

        for (DocumentFile child : root.listFiles()) {
            if (child.isDirectory()) {
                importSingleVnFolder(context, child, vnsRoot);
            }
        }
    }

    /** Imports {@code folder} as one VN if it's recognizable -- VNDS (img.ini plus either a
     * script/ folder or a script.zip) tried first, falling back to NScripter (a plain-text or
     * obfuscated script file at the root, no img.ini needed; see {@link #looksLikeNScripterFolder})
     * so an ambiguous folder always defaults to VNDS -- returns false without copying anything if
     * neither matches. Shared by both the "tree may hold several packs" scan and the "this folder
     * IS the one VN" entry point. */
    private static boolean importSingleVnFolder(Context context, DocumentFile folder, File vnsRoot) throws IOException {
        DocumentFile imgIni = folder.findFile("img.ini");
        DocumentFile scriptDir = folder.findFile("script");
        DocumentFile scriptZip = folder.findFile("script.zip");
        boolean hasScript = (scriptDir != null && scriptDir.isDirectory()) || scriptZip != null;
        VnEntry.EngineType engineType;
        if (imgIni != null && hasScript) {
            engineType = VnEntry.EngineType.VNDS;
        } else if (looksLikeNScripterFolder(folder)) {
            engineType = VnEntry.EngineType.NSCRIPTER;
        } else {
            return false; // not a recognizable story folder of either format
        }

        String folderName = folder.getName() != null ? folder.getName() : "vn";
        File localDir = new File(vnsRoot, sanitize(folderName));
        File marker = new File(localDir, ".imported");
        if (!marker.exists()) {
            if (localDir.exists()) {
                deleteRecursive(localDir);
            }
            if (!localDir.mkdirs()) {
                return false;
            }
            copyTree(context, folder, localDir);
            extractZipIfPresent(new File(localDir, "script.zip"), localDir);
            extractZipIfPresent(new File(localDir, "background.zip"), localDir);
            extractZipIfPresent(new File(localDir, "foreground.zip"), localDir);
            extractZipIfPresent(new File(localDir, "sound.zip"), localDir);
            writeEngineMarker(localDir, engineType);
            new FileOutputStream(marker).close();
        }
        return true;
    }

    /**
     * Imports a VN whose whole folder was archived as a single file -- zip or 7z, sniffed from
     * the file's own signature rather than trusted from a name/extension, since SAF display names
     * aren't always reliable. Handles both shapes people actually produce when they archive a
     * folder: entries rooted directly at img.ini etc. (they archived the folder's *contents*), or
     * all nested one level under a single top-level directory (they archived the folder *itself*)
     * -- in the latter case that wrapper directory is stripped so the result matches a normal
     * imported VN either way.
     */
    private static void importArchiveFile(Context context, Uri archiveUri, Callback callback, Handler main,
                                           AtomicBoolean cancelRequested) throws IOException {
        File vnsRoot = new File(context.getFilesDir(), "vns");
        vnsRoot.mkdirs();
        cleanupOrphans(context);

        // Sniffed from the source Uri directly (a few bytes, cheap) rather than waiting for the
        // full copy below, so the slow-archive warning below can be prepended to every progress
        // message for the whole import, not just flash by once before the first "Copying… 1%"
        // instantly overwrites it.
        boolean sevenZip = isSevenZip(context, archiveUri);
        String messagePrefix = sevenZip ? context.getString(R.string.importing_sevenzip_warning) + "\n" : "";

        File tempArchive = File.createTempFile("import", ".tmp", context.getCacheDir());
        File localDir = null;
        try {
            copyUriToFile(context, archiveUri, tempArchive, throttledProgress(context, callback, main,
                    R.string.importing_copying_progress, sizeOfUri(context, archiveUri), cancelRequested, messagePrefix));

            List<String> entryNames = sevenZip ? listSevenZEntryNames(tempArchive) : listZipEntryNames(tempArchive);
            String rootPrefix = findImgIniPrefix(entryNames);
            VnEntry.EngineType engineType;
            if (rootPrefix != null && hasScriptUnderPrefix(entryNames, rootPrefix)) {
                engineType = VnEntry.EngineType.VNDS;
            } else {
                rootPrefix = findNScriptPrefix(entryNames);
                if (rootPrefix == null) {
                    throw new IOException("No recognizable VN folder in archive "
                            + "(no img.ini+script/, and no NScripter script file either)");
                }
                engineType = VnEntry.EngineType.NSCRIPTER;
            }

            String folderName = rootPrefix.isEmpty()
                    ? stripArchiveExtension(displayNameOf(context, archiveUri))
                    : rootPrefix.substring(0, rootPrefix.length() - 1); // drop trailing '/'
            localDir = new File(vnsRoot, sanitize(folderName));
            File marker = new File(localDir, ".imported");
            if (marker.exists()) {
                return; // already imported under this name
            }
            if (localDir.exists()) {
                deleteRecursive(localDir);
            }
            if (!localDir.mkdirs()) {
                throw new IOException("Could not create " + localDir);
            }
            long extractSize = sumUncompressedSizeUnderPrefix(tempArchive, sevenZip, rootPrefix);
            ProgressListener extractProgress = throttledProgress(context, callback, main,
                    R.string.importing_extracting_progress, extractSize, cancelRequested, messagePrefix);
            try (ArchiveCursor cursor = sevenZip ? new SevenZCursor(tempArchive) : new ZipCursor(tempArchive)) {
                extractArchive(cursor, rootPrefix, localDir, extractProgress);
            }
            // The outer archive may itself contain a VN authored with these as separate nested
            // zips (same as a loose folder pick) rather than loose script/background/etc.
            // folders -- extraction above just drops them into localDir as-is, so unpack them
            // same as importSingleVnFolder does.
            extractZipIfPresent(new File(localDir, "script.zip"), localDir);
            extractZipIfPresent(new File(localDir, "background.zip"), localDir);
            extractZipIfPresent(new File(localDir, "foreground.zip"), localDir);
            extractZipIfPresent(new File(localDir, "sound.zip"), localDir);
            writeEngineMarker(localDir, engineType);
            new FileOutputStream(marker).close();
        } catch (IOException e) {
            // Whether cancelled or a genuine failure, a localDir without its .imported marker is
            // never valid -- don't leave a partial multi-hundred-MB extraction behind either way.
            if (localDir != null && localDir.exists() && !new File(localDir, ".imported").exists()) {
                deleteRecursive(localDir);
            }
            throw e;
        } finally {
            tempArchive.delete();
        }
    }

    /** Removes leftovers from a previous archive import that never got to clean up after itself
     * -- normally because the whole process was killed partway through (force-stopped, swiped
     * from recents, reinstalled over), which skips {@link #importArchiveFile}'s try/finally
     * entirely (a killed process doesn't run finally blocks). Always safe: a stray import*.tmp in
     * the cache dir, or a {@code vns/} folder without its {@code .imported} marker, can only exist
     * here because some earlier run never finished. Run once before starting a new import rather
     * than only on success/cancel, so a crash doesn't just accumulate dead multi-hundred-MB files
     * indefinitely. */
    private static void cleanupOrphans(Context context) {
        File[] tempFiles = context.getCacheDir().listFiles((dir, name) -> name.startsWith("import") && name.endsWith(".tmp"));
        if (tempFiles != null) {
            for (File f : tempFiles) {
                f.delete();
            }
        }
        File[] vnDirs = new File(context.getFilesDir(), "vns").listFiles();
        if (vnDirs != null) {
            for (File dir : vnDirs) {
                if (dir.isDirectory() && !new File(dir, ".imported").exists()) {
                    deleteRecursive(dir);
                }
            }
        }
    }

    /** Builds a progress listener that only actually notifies {@code callback} when the rounded
     * percentage changes (a raw per-chunk callback would flood the main thread with posts for a
     * multi-hundred-MB file), and is silent entirely if {@code total} is unknown/zero. Also the
     * cancellation checkpoint: every call (throttled or not) checks {@code cancelRequested} first,
     * since this is called from every chunk of both the copy and extraction loops -- the only
     * phases slow enough for a mid-operation cancel to matter. {@code messagePrefix} (e.g. the 7z
     * slow-archive warning) is prepended to every message rather than sent once on its own, so it
     * doesn't just flash by before the very next percentage update overwrites it. */
    private static ProgressListener throttledProgress(Context context, Callback callback, Handler main,
                                                        int messageRes, long total, AtomicBoolean cancelRequested,
                                                        String messagePrefix) {
        int[] lastPercent = {-1};
        return done -> {
            if (cancelRequested != null && cancelRequested.get()) {
                throw new ImportCancelledException();
            }
            if (total <= 0) {
                return;
            }
            int percent = (int) Math.min(100, done * 100 / total);
            if (percent != lastPercent[0]) {
                lastPercent[0] = percent;
                String message = messagePrefix + context.getString(messageRes, percent);
                main.post(() -> callback.onProgress(message));
            }
        };
    }

    /** The picked file's size, or -1 if the provider doesn't report one (progress is then simply
     * not shown for that phase -- see {@link #throttledProgress}). */
    private static long sizeOfUri(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (RuntimeException ignored) {
            // Some providers don't support querying; fall through.
        }
        return -1;
    }

    /** Sums the uncompressed size of every file entry (not directories) under {@code prefix}, for
     * sizing the extraction progress bar -- extraction only ever writes exactly these bytes. */
    private static long sumUncompressedSizeUnderPrefix(File archiveFile, boolean sevenZip, String prefix) throws IOException {
        long total = 0;
        if (sevenZip) {
            try (SevenZFile sevenZ = new SevenZFile(archiveFile)) {
                for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                    String name = normalizeSeparators(entry.getName());
                    if (!entry.isDirectory() && name.startsWith(prefix)) {
                        total += entry.getSize();
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(archiveFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                        total += Math.max(0, entry.getSize());
                    }
                }
            }
        }
        return total;
    }

    /** Sniffs the source's own signature rather than trusting its name/extension -- just the
     * first few bytes, so this is cheap even before the full archive has been copied locally. */
    private static boolean isSevenZip(Context context, Uri uri) throws IOException {
        byte[] header = new byte[12];
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            int n = in.read(header);
            return n > 0 && SevenZFile.matches(header, n);
        }
    }

    private static List<String> listZipEntryNames(File zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static List<String> listSevenZEntryNames(File sevenZFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (SevenZFile sevenZ = new SevenZFile(sevenZFile)) {
            for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                names.add(normalizeSeparators(entry.getName()));
            }
        }
        return names;
    }

    /** 7z archives built on Windows commonly use '\' as the path separator; normalize to '/' so
     * the same prefix/relative-path logic below works regardless of archive format or origin. */
    private static String normalizeSeparators(String name) {
        return name.replace('\\', '/');
    }

    /**
     * Finds the path prefix (possibly empty) that img.ini sits under, so its siblings
     * (script/, background/, etc.) can be extracted relative to the VN root rather than
     * whatever incidental wrapper directory the archive itself used.
     */
    private static String findImgIniPrefix(List<String> entryNames) {
        for (String name : entryNames) {
            if (name.endsWith("img.ini") && !name.endsWith("/")) {
                int slash = name.lastIndexOf('/');
                return slash < 0 ? "" : name.substring(0, slash + 1);
            }
        }
        return null;
    }

    /** Same recognition rule as {@link #importSingleVnFolder}: img.ini alone isn't enough, there
     * also has to be a script/ folder or a script.zip sitting right beside it. */
    private static boolean hasScriptUnderPrefix(List<String> entryNames, String prefix) {
        for (String name : entryNames) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String relative = name.substring(prefix.length());
            if (relative.startsWith("script/") || relative.equals("script.zip")) {
                return true;
            }
        }
        return false;
    }

    /** The archive-entry-name counterpart to {@link #looksLikeNScripterFolder}: finds the path
     * prefix one of NScripter's recognized script filenames sits directly under (not nested
     * further, matching {@link #findImgIniPrefix}'s own "immediate sibling" rule), so its assets
     * can be extracted relative to the VN root the same way an img.ini-rooted archive already is. */
    private static String findNScriptPrefix(List<String> entryNames) {
        for (String name : entryNames) {
            if (name.endsWith("/")) {
                continue;
            }
            int slash = name.lastIndexOf('/');
            String filename = slash < 0 ? name : name.substring(slash + 1);
            for (String marker : NSCRIPTER_MARKER_FILES) {
                if (filename.equals(marker)) {
                    return slash < 0 ? "" : name.substring(0, slash + 1);
                }
            }
        }
        return null;
    }

    /** Sequential view over one archive format's entries, hiding the zip/7z API differences (a
     * fresh {@link InputStream} per entry vs. one stream read incrementally) behind a single
     * shape so {@link #extractArchive} can walk either format identically. */
    private interface ArchiveCursor extends Closeable {
        /** Advances to the next entry and returns its normalized name, or null when exhausted. */
        String nextEntry() throws IOException;
        boolean isDirectory();
        int read(byte[] buf) throws IOException;
    }

    private static final class ZipCursor implements ArchiveCursor {
        private final ZipFile zip;
        private final Enumeration<? extends ZipEntry> entries;
        private ZipEntry current;
        private InputStream currentIn;

        ZipCursor(File file) throws IOException {
            zip = new ZipFile(file);
            entries = zip.entries();
        }

        @Override
        public String nextEntry() throws IOException {
            if (currentIn != null) {
                currentIn.close();
                currentIn = null;
            }
            if (!entries.hasMoreElements()) {
                return null;
            }
            current = entries.nextElement();
            if (!current.isDirectory()) {
                currentIn = zip.getInputStream(current);
            }
            return current.getName();
        }

        @Override
        public boolean isDirectory() {
            return current.isDirectory();
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return currentIn.read(buf);
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class SevenZCursor implements ArchiveCursor {
        private final SevenZFile sevenZ;
        private SevenZArchiveEntry current;

        SevenZCursor(File file) throws IOException {
            sevenZ = new SevenZFile(file);
        }

        @Override
        public String nextEntry() throws IOException {
            current = sevenZ.getNextEntry();
            return current != null ? normalizeSeparators(current.getName()) : null;
        }

        @Override
        public boolean isDirectory() {
            return current.isDirectory();
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return sevenZ.read(buf);
        }

        @Override
        public void close() throws IOException {
            sevenZ.close();
        }
    }

    /** Extracts every entry under {@code rootPrefix} into {@code destRoot}, relative to that
     * prefix; shared by both zip and 7z extraction (see {@link ArchiveCursor}) since the walk,
     * zip-slip guard, and progress reporting are otherwise identical between the two formats. */
    private static void extractArchive(ArchiveCursor cursor, String rootPrefix, File destRoot, ProgressListener progress) throws IOException {
        String destRootPath = destRoot.getCanonicalPath() + File.separator;
        long done = 0;
        byte[] buf = new byte[65536];
        String name;
        while ((name = cursor.nextEntry()) != null) {
            if (!name.startsWith(rootPrefix)) {
                continue; // outside the VN's own root (e.g. a sibling folder in the archive)
            }
            String relative = name.substring(rootPrefix.length());
            if (relative.isEmpty()) {
                continue;
            }
            File outFile = new File(destRoot, relative);
            if (!outFile.getCanonicalPath().startsWith(destRootPath)) {
                continue; // zip-slip guard
            }
            if (cursor.isDirectory()) {
                outFile.mkdirs();
                continue;
            }
            File parent = outFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (OutputStream out = new FileOutputStream(outFile)) {
                int n;
                while ((n = cursor.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    progress.onBytesDone(done);
                }
            }
        }
    }

    private static void copyUriToFile(Context context, Uri uri, File dest, ProgressListener progress) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("Could not open " + uri);
            }
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                progress.onBytesDone(done);
            }
        }
    }

    private static String displayNameOf(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Some providers don't support querying; fall through to the generic name below.
        }
        return "imported_vn";
    }

    private static String stripArchiveExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".zip") || lower.endsWith(".7z")) {
            return name.substring(0, lower.lastIndexOf('.'));
        }
        return name;
    }

    private static void copyTree(Context context, DocumentFile src, File dstDir) throws IOException {
        for (DocumentFile child : src.listFiles()) {
            String name = child.getName();
            if (name == null) {
                continue;
            }
            if (child.isDirectory()) {
                File subDir = new File(dstDir, name);
                subDir.mkdirs();
                copyTree(context, child, subDir);
            } else {
                File outFile = new File(dstDir, name);
                try (InputStream in = context.getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new FileOutputStream(outFile)) {
                    if (in != null) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts a VNDS zip (script.zip, background.zip, foreground.zip, sound.zip) directly into
     * the VN's root directory. These zips' entries are already rooted at the VN folder (e.g.
     * "script/main.scr"), not at the asset subfolder itself, so the destination here is
     * {@code localDir}, not {@code localDir/script} -- and since a VN can mix a loose folder
     * (e.g. "sound/bgm/*.mp3") with a same-named zip (e.g. "sound.zip" containing "sound/se/*"),
     * this merges into any existing directory rather than skipping when one is already present.
     */
    private static void extractZipIfPresent(File zipFile, File destRoot) throws IOException {
        if (!zipFile.exists()) {
            return;
        }
        String destRootPath = destRoot.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destRoot, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(destRootPath)) {
                    continue; // zip-slip guard: skip entries that would land outside destRoot
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static String readTitle(File localDir, String fallback) {
        Map<String, String> info = parseKeyValueFile(new File(localDir, "info.txt"));
        String title = info.get("title");
        return (title != null && !title.trim().isEmpty()) ? title.trim() : fallback;
    }

    static Map<String, String> parseKeyValueFile(File file) {
        Map<String, String> map = new HashMap<>();
        if (!file.exists()) {
            return map;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
