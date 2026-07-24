package com.example.vndsandroideink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.vndsandroideink.nscripter.NsSaveManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Milestone-6 acceptance test for the "read" half of engine-type persistence:
 * {@code VnImporter.scanLocal()} (exercised through the public {@code loadLocalLibrary}) must
 * correctly read back the ".engine" marker a real import would have written, and must default to
 * VNDS for a pack imported before that marker existed at all -- verified against real, unmodified
 * production code, not a mock.
 *
 * <p>The "write" half (VnImporter's SAF-based detection/copy logic choosing NSCRIPTER vs VNDS)
 * isn't covered by an automated test here: it requires a real SAF folder-tree Uri, which isn't
 * reliably obtainable without driving the system file picker's UI. That was instead verified live
 * (manually staging an already-imported NScripter pack and confirming MainActivity's library,
 * resume detection, and ReaderActivity launch all behave correctly against it) -- see the
 * conversation/commit notes for this milestone.
 */
@RunWith(AndroidJUnit4.class)
public class VnImporterEngineMarkerTest {

    private Context context;
    private File nsVnDir;
    private File legacyVnDir;

    @After
    public void tearDown() {
        if (nsVnDir != null) {
            deleteRecursively(nsVnDir);
            SaveManager.deleteAll(context, nsVnDir.getName());
        }
        if (legacyVnDir != null) {
            deleteRecursively(legacyVnDir);
            SaveManager.deleteAll(context, legacyVnDir.getName());
        }
    }

    @Test
    public void scanLocalReadsEngineNScripterMarkerAndUsesNsSaveManagerForResume() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "vn_marker_test_ns_" + System.nanoTime();
        nsVnDir = new File(context.getFilesDir(), "vns/" + name);
        nsVnDir.mkdirs();
        writeMarker(nsVnDir, ".imported", "");
        writeMarker(nsVnDir, ".engine", "NSCRIPTER");
        // Seed an NScripter resume snapshot the same shape a real save would leave -- scanLocal
        // must consult NsSaveManager (not SaveManager) for this pack's hasResume, matching the
        // engine type its own marker declares.
        seedNsResumeSlot(name);

        VnEntry entry = findEntry(name);
        assertEquals(VnEntry.EngineType.NSCRIPTER, entry.engineType);
        assertTrue("expected hasResume from the NS-shaped resume slot this test seeded", entry.hasResume);
    }

    @Test
    public void scanLocalDefaultsToVndsWhenNoEngineMarkerExists() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "vn_marker_test_legacy_" + System.nanoTime();
        legacyVnDir = new File(context.getFilesDir(), "vns/" + name);
        legacyVnDir.mkdirs();
        writeMarker(legacyVnDir, ".imported", ""); // no ".engine" file -- pre-dates this feature

        VnEntry entry = findEntry(name);
        assertEquals(VnEntry.EngineType.VNDS, entry.engineType);
    }

    private VnEntry findEntry(String name) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<VnEntry>> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                VnImporter.loadLocalLibrary(context, new VnImporter.Callback() {
                    @Override
                    public void onComplete(List<VnEntry> entries) {
                        result.set(entries);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception e) {
                        latch.countDown();
                    }
                }));
        assertTrue("loadLocalLibrary callback never fired", latch.await(10, TimeUnit.SECONDS));
        for (VnEntry e : result.get()) {
            if (e.localDir.getName().equals(name)) {
                return e;
            }
        }
        throw new AssertionError("Expected local library to contain " + name);
    }

    private void seedNsResumeSlot(String vnKey) throws IOException {
        File scratchDir = new File(context.getFilesDir(), "vns/" + vnKey);
        writeMarker(scratchDir, "0.txt", "*start\nHello\\\n");
        com.example.vndsandroideink.nscripter.NsScriptEngine engine =
                new com.example.vndsandroideink.nscripter.NsScriptEngine(scratchDir, new NoOpListener(), new java.util.HashMap<>());
        engine.start();
        NsSaveManager.save(context, vnKey, SaveManager.SLOT_RESUME, engine, null,
                com.example.vndsandroideink.engine.VnEngine.SpriteTransparency.OPAQUE, 1, null,
                new java.util.ArrayList<>(), "", new java.util.ArrayList<>());
        new File(scratchDir, "0.txt").delete(); // was only needed to construct a valid engine snapshot
    }

    private static void writeMarker(File dir, String name, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
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

    private static final class NoOpListener implements com.example.vndsandroideink.engine.VnEngine.Listener {
        @Override
        public void onSpeaker(String name) {
        }

        @Override
        public void onTextLine(String line) {
        }

        @Override
        public void onTextAppend(String moreText) {
        }

        @Override
        public void onTextClear() {
        }

        @Override
        public void onBackground(File imageFile, int fadeFrames,
                                  com.example.vndsandroideink.engine.VnEngine.SpriteTransparency transparency,
                                  int alphaMaskCells) {
        }

        @Override
        public void onSprite(int layer, int x, int y, File imageFile,
                             com.example.vndsandroideink.engine.VnEngine.SpriteTransparency transparency,
                             int alphaMaskCells) {
        }

        @Override
        public void onSpriteCleared(int layer) {
        }

        @Override
        public void onSound(File soundFileOrNull, int times) {
        }

        @Override
        public void onMusic(File musicFileOrNull) {
        }

        @Override
        public void onChoices(List<String> options) {
        }

        @Override
        public void onDelay(int frames) {
        }

        @Override
        public void onGlobalsChanged(java.util.Map<String, String> globals) {
        }

        @Override
        public void onFinished() {
        }

        @Override
        public void onExitToLibrary() {
        }

        @Override
        public void onLoadMenuRequested() {
        }
    }
}
