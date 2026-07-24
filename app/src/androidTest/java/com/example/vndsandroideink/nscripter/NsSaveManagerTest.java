package com.example.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.vndsandroideink.SaveManager;
import com.example.vndsandroideink.engine.VnEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NsSaveManager needs a real Context/SharedPreferences, unavailable in this project's plain-JVM
 * unit tests (no Robolectric dependency) -- runs as an instrumented test instead, the same way
 * Activity-level behavior is verified elsewhere in this codebase.
 */
@RunWith(AndroidJUnit4.class)
public class NsSaveManagerTest {

    private Context context;
    private String vnKey;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Unique per run so parallel/repeated test runs never collide on real device state.
        vnKey = "ns_save_test_" + System.nanoTime();
    }

    @After
    public void tearDown() {
        SaveManager.deleteAll(context, vnKey);
    }

    private NsScriptEngine.Snapshot sampleSnapshot() {
        Map<Integer, Long> numVars = new HashMap<>();
        numVars.put(1, 42L);
        numVars.put(29, 0L);
        Map<Integer, String> strVars = new HashMap<>();
        strVars.put(3, "Yuki");
        Map<String, Integer> numAliases = new HashMap<>();
        numAliases.put("money", 5);
        Map<String, Integer> strAliases = new HashMap<>();
        strAliases.put("name", 3);
        List<Integer> callStack = Arrays.asList(42, 17); // top-of-stack first, per Snapshot's doc
        return new NsScriptEngine.Snapshot(123, numVars, strVars, numAliases, strAliases, callStack, true);
    }

    @Test
    public void saveThenLoadRoundTripsEngineState() {
        List<NsSaveManager.NsSpriteEntry> sprites = new ArrayList<>();
        sprites.add(new NsSaveManager.NsSpriteEntry(2, 10, 20, "chra/face.png", VnEngine.SpriteTransparency.OPAQUE, 1));
        List<SaveManager.SavedLine> bodyLines = new ArrayList<>();
        bodyLines.add(new SaveManager.SavedLine("Hello there", false));

        writeSlot(1, sampleSnapshot(), "bg/room.jpg", "sound/theme.mp3", sprites, "Yuki", bodyLines);

        NsSaveManager.NsSlotData loaded = NsSaveManager.load(context, vnKey, 1);
        assertEqualsSnapshot(sampleSnapshot(), loaded.engineState);
        assertEquals("bg/room.jpg", loaded.backgroundPath);
        assertEquals("sound/theme.mp3", loaded.musicPath);
        assertEquals(1, loaded.sprites.size());
        assertEquals(2, loaded.sprites.get(0).layer);
        assertEquals(10, loaded.sprites.get(0).x);
        assertEquals(20, loaded.sprites.get(0).y);
        assertEquals("chra/face.png", loaded.sprites.get(0).path);
        assertEquals("Yuki", loaded.lastSpeaker);
        assertEquals(1, loaded.bodyLines.size());
        assertEquals("Hello there", loaded.bodyLines.get(0).text);
    }

    /** {@link NsScriptEngine.Snapshot} round-trips through an actual {@link NsScriptEngine}
     * instance too, not just through the manager's own JSON encode/decode -- confirms
     * snapshotState()/restoreFromSnapshot() and NsSaveManager agree on the shape. */
    @Test
    public void snapshotFromARealEngineRoundTripsThroughSaveAndRestore() throws Exception {
        File vnDir = new File(context.getFilesDir(), "vns/ns_save_test_engine");
        vnDir.mkdirs();
        writeScript(vnDir, String.join("\n",
                "*start",
                "numalias money,5",
                "gosub *earn",
                "mov $3,\"Yuki\"",
                "The end\\",
                "*earn",
                "mov %money,42",
                "return",
                ""));

        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(vnDir, listener, new HashMap<>());
        engine.start(); // runs to the pagewait on "The end\"

        List<NsSaveManager.NsSpriteEntry> sprites = new ArrayList<>();
        List<SaveManager.SavedLine> bodyLines = new ArrayList<>();
        bodyLines.add(new SaveManager.SavedLine("The end", false));
        NsSaveManager.save(context, vnKey, 2, engine, null, VnEngine.SpriteTransparency.OPAQUE, 1, null, sprites, "", bodyLines);

        NsSaveManager.NsSlotData loaded = NsSaveManager.load(context, vnKey, 2);
        assertEquals("42", String.valueOf(loaded.engineState.numVars.get(5))); // %money -> slot 5
        assertEquals("Yuki", loaded.engineState.strVars.get(3));
        assertTrue(loaded.engineState.callStack.isEmpty()); // "return" already popped it

        NsScriptEngine restored = new NsScriptEngine(vnDir, listener, new HashMap<>());
        restored.restoreFromSnapshot(loaded.engineState);
        assertEquals("42", restored.getVariablesSnapshot().get("%5"));
    }

    @Test
    public void backgroundTransparencyRoundTrips() {
        // A save/load round-trip must not revert an alpha-mask-tagged background to opaque -- see
        // NsSaveManager.NsSlotData.backgroundTransparency's doc.
        writeSlot(4, sampleSnapshot(), "bg/masked.jpg", VnEngine.SpriteTransparency.ALPHA_MASK, "",
                new ArrayList<>(), "", new ArrayList<>());
        NsSaveManager.NsSlotData loaded = NsSaveManager.load(context, vnKey, 4);
        assertEquals(VnEngine.SpriteTransparency.ALPHA_MASK, loaded.backgroundTransparency);
    }

    @Test
    public void loadOfAnUnoccupiedSlotReturnsNull() {
        assertNull(NsSaveManager.load(context, vnKey, 5));
    }

    @Test
    public void listSlotsReportsOccupiedAndUnoccupiedCorrectly() {
        writeSlot(3, sampleSnapshot(), "", "", new ArrayList<>(), "", new ArrayList<>());
        List<SaveManager.SlotInfo> slots = NsSaveManager.listSlots(context, vnKey);
        assertEquals(SaveManager.SLOT_COUNT, slots.size());
        for (SaveManager.SlotInfo s : slots) {
            assertEquals(s.index == 3, s.occupied);
        }
    }

    @Test
    public void resumeSlotIsSeparateFromManualSlots() {
        assertFalse(NsSaveManager.hasResume(context, vnKey));
        writeSlot(SaveManager.SLOT_RESUME, sampleSnapshot(), "", "", new ArrayList<>(), "", new ArrayList<>());
        assertTrue(NsSaveManager.hasResume(context, vnKey));
        assertTrue(NsSaveManager.resumeSlotInfo(context, vnKey).occupied);
        // Never counted among the 1..SLOT_COUNT manual slots the Save/Load menu lists.
        for (SaveManager.SlotInfo s : NsSaveManager.listSlots(context, vnKey)) {
            assertFalse(s.occupied);
        }
    }

    @Test
    public void exportThenImportRoundTripsNsSaveDataCorrectly() throws org.json.JSONException {
        // SaveManager.exportData/importData are format-agnostic (operate on whatever keys share a
        // vnKey prefix) and were never touched adding NScripter support -- this confirms that
        // holds for real NS-shaped values, not just key presence.
        List<NsSaveManager.NsSpriteEntry> sprites = new ArrayList<>();
        sprites.add(new NsSaveManager.NsSpriteEntry(4, 1, 2, "chra/x.png", VnEngine.SpriteTransparency.OPAQUE, 1));
        List<SaveManager.SavedLine> bodyLines = new ArrayList<>();
        bodyLines.add(new SaveManager.SavedLine("Exported line", true));
        writeSlot(7, sampleSnapshot(), "bg/x.jpg", "sound/x.mp3", sprites, "Speaker", bodyLines);

        org.json.JSONObject exported = SaveManager.exportData(context, vnKey, "Test VN");

        String otherVnKey = vnKey + "_imported";
        SaveManager.importData(context, otherVnKey, exported);

        NsSaveManager.NsSlotData loaded = NsSaveManager.load(context, otherVnKey, 7);
        assertEqualsSnapshot(sampleSnapshot(), loaded.engineState);
        assertEquals("bg/x.jpg", loaded.backgroundPath);
        assertEquals("Speaker", loaded.lastSpeaker);
        assertEquals(1, loaded.sprites.size());
        assertEquals(4, loaded.sprites.get(0).layer);

        SaveManager.deleteAll(context, otherVnKey);
    }

    @Test
    public void deleteAllWipesBothVndsAndNsKeysForTheSameVnKey() {
        // Verifies the whole point of sharing one vnKey-prefixed SharedPreferences file: a single
        // cross-cutting operation (VN deleted from the library) cleans up either format's saves
        // without SaveManager needing to know which one was in use.
        writeSlot(1, sampleSnapshot(), "", "", new ArrayList<>(), "", new ArrayList<>());
        SaveManager.saveGlobals(context, vnKey, new HashMap<>()); // a VNDS-shaped key under the same vnKey
        SaveManager.deleteAll(context, vnKey);
        assertNull(NsSaveManager.load(context, vnKey, 1));
        assertTrue(SaveManager.loadGlobals(context, vnKey).isEmpty());
    }

    private void writeSlot(int slot, NsScriptEngine.Snapshot snapshot, String bg, String music,
                            List<NsSaveManager.NsSpriteEntry> sprites, String speaker, List<SaveManager.SavedLine> lines) {
        writeSlot(slot, snapshot, bg, VnEngine.SpriteTransparency.OPAQUE, music, sprites, speaker, lines);
    }

    private void writeSlot(int slot, NsScriptEngine.Snapshot snapshot, String bg,
                            VnEngine.SpriteTransparency bgTransparency, String music,
                            List<NsSaveManager.NsSpriteEntry> sprites, String speaker, List<SaveManager.SavedLine> lines) {
        // NsSaveManager.save() takes a live NsScriptEngine (matching SaveManager.save()'s own
        // precedent of taking a concrete engine, not a bare snapshot) -- restoreFromSnapshot into a
        // throwaway engine instance to drive save() from a known Snapshot for test determinism.
        // The scratch script needs >= sampleSnapshot()'s pc (123) lines, since restoreFromSnapshot
        // clamps pc to the script's actual length (protecting a real load against a save whose
        // script has since gotten shorter) -- an empty/short script would silently clamp it to 0.
        File vnDir = new File(context.getFilesDir(), "vns/ns_save_test_scratch");
        vnDir.mkdirs();
        File script = new File(vnDir, "0.txt");
        if (!script.exists()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("~\n");
            }
            try {
                writeScript(vnDir, sb.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        NsScriptEngine engine = new NsScriptEngine(vnDir, new FakeListener(), new HashMap<>());
        engine.restoreFromSnapshot(snapshot);
        NsSaveManager.save(context, vnKey, slot, engine, bg, bgTransparency, 1, music, sprites, speaker, lines);
    }

    private static void writeScript(File vnDir, String content) throws Exception {
        try (java.io.OutputStream out = new java.io.FileOutputStream(new File(vnDir, "0.txt"))) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** src/androidTest is a separate source set from src/test, so the JVM-test FakeListener isn't
     * visible here -- a minimal local stand-in, since these tests only care about engine state. */
    private static final class FakeListener implements VnEngine.Listener {
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
        public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        }

        @Override
        public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
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
        public void onGlobalsChanged(Map<String, String> globals) {
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

    private static void assertEqualsSnapshot(NsScriptEngine.Snapshot expected, NsScriptEngine.Snapshot actual) {
        assertEquals(expected.pc, actual.pc);
        assertEquals(expected.numVars, actual.numVars);
        assertEquals(expected.strVars, actual.strVars);
        assertEquals(expected.numAliases, actual.numAliases);
        assertEquals(expected.strAliases, actual.strAliases);
        assertEquals(expected.callStack, actual.callStack);
        assertEquals(expected.pendingPageClearOnResume, actual.pendingPageClearOnResume);
    }
}
