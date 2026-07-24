package com.example.vndsandroideink.nscripter;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.vndsandroideink.SaveManager;
import com.example.vndsandroideink.engine.VnEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * NScripter's counterpart to {@link SaveManager} -- kept as a separate class rather than folded
 * into it because the state that needs saving is categorically richer than VNDS's fixed {@code
 * SlotData} (numalias/stralias tables, a gosub/return call stack, numbered sprite layers instead
 * of an append-only list) and bolting one shape onto the other would force every save, of either
 * format, to carry the other's dead fields.
 *
 * <p>Deliberately reuses {@link SaveManager}'s own {@code SharedPreferences} file and {@code
 * vnKey}-prefixed key scheme (see {@link #key}) rather than a separate store, just under an
 * "ns."-tagged suffix so the two schemas can't collide -- which is also why {@link
 * SaveManager#deleteAll}/{@link SaveManager#clearResume}/export-import already work correctly for
 * NScripter saves with zero changes: they operate on whatever keys share a {@code vnKey} prefix,
 * never caring which suffix scheme produced them.
 *
 * <p>No {@code loadGlobals}/{@code saveGlobals} pair yet, unlike {@link SaveManager}'s: {@link
 * NsScriptEngine} has no persistent-global mechanism to persist (see its constructor's note) --
 * adding storage for a mechanism that doesn't exist yet would just be dead code.
 */
public final class NsSaveManager {

    private static final String PREFS_FILE = "vnds_saves"; // shared with SaveManager

    private NsSaveManager() {
    }

    /** One NScripter numbered sprite layer -- {@link SaveManager.SpriteEntry}'s counterpart, with
     * the layer identity VNDS's append-only sprite list never needed (see {@code
     * VnEngine.Listener#onSprite}'s doc on why the two engines' sprite models differ). */
    public static final class NsSpriteEntry {
        public final int layer;
        public final int x;
        public final int y;
        public final String path;
        /** See {@link VnEngine.SpriteTransparency} -- must be remembered per-sprite so a reload
         * re-composites the same way the live "ld" did. */
        public final VnEngine.SpriteTransparency transparency;
        /** See {@link VnEngine.Listener#onSprite}'s doc on alphaMaskCells; only meaningful when
         * {@link #transparency} is {@code ALPHA_MASK}. */
        public final int alphaMaskCells;

        public NsSpriteEntry(int layer, int x, int y, String path, VnEngine.SpriteTransparency transparency,
                              int alphaMaskCells) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.path = path;
            this.transparency = transparency;
            this.alphaMaskCells = alphaMaskCells;
        }
    }

    public static final class NsSlotData {
        public final NsScriptEngine.Snapshot engineState;
        public final String backgroundPath;
        /** See {@link NsSpriteEntry#transparency}'s doc -- the background's own equivalent, so a
         * reload doesn't revert an alpha-mask-tagged background to opaque. */
        public final VnEngine.SpriteTransparency backgroundTransparency;
        /** See {@link NsSpriteEntry#alphaMaskCells}'s doc -- the background's own equivalent. */
        public final int backgroundAlphaMaskCells;
        public final String musicPath;
        public final List<NsSpriteEntry> sprites;
        public final String lastSpeaker;
        public final List<SaveManager.SavedLine> bodyLines;

        NsSlotData(NsScriptEngine.Snapshot engineState, String backgroundPath,
                   VnEngine.SpriteTransparency backgroundTransparency, int backgroundAlphaMaskCells,
                   String musicPath, List<NsSpriteEntry> sprites, String lastSpeaker,
                   List<SaveManager.SavedLine> bodyLines) {
            this.engineState = engineState;
            this.backgroundPath = backgroundPath;
            this.backgroundTransparency = backgroundTransparency;
            this.backgroundAlphaMaskCells = backgroundAlphaMaskCells;
            this.musicPath = musicPath;
            this.sprites = sprites;
            this.lastSpeaker = lastSpeaker;
            this.bodyLines = bodyLines;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** Same {@code vnKey.slot.suffix} shape {@link SaveManager} uses, with every suffix "ns."-
     * tagged so e.g. "ns.pc" can never collide with VNDS's own plain "pc" under the same slot --
     * relevant since a stray resume snapshot from a VN that used to be the other format (unlikely,
     * but not impossible if a folder were somehow reused) shouldn't get misread as this format's. */
    private static String key(String vnKey, int slot, String suffix) {
        return vnKey + "." + slot + ".ns." + suffix;
    }

    public static boolean hasResume(Context context, String vnKey) {
        return prefs(context).contains(key(vnKey, SaveManager.SLOT_RESUME, "pc"));
    }

    public static SaveManager.SlotInfo resumeSlotInfo(Context context, String vnKey) {
        return slotInfo(context, vnKey, SaveManager.SLOT_RESUME);
    }

    public static List<SaveManager.SlotInfo> listSlots(Context context, String vnKey) {
        List<SaveManager.SlotInfo> result = new ArrayList<>();
        for (int slot = 1; slot <= SaveManager.SLOT_COUNT; slot++) {
            result.add(slotInfo(context, vnKey, slot));
        }
        return result;
    }

    private static SaveManager.SlotInfo slotInfo(Context context, String vnKey, int slot) {
        SharedPreferences p = prefs(context);
        boolean occupied = p.contains(key(vnKey, slot, "pc"));
        String preview = occupied ? p.getString(key(vnKey, slot, "preview"), "") : "";
        long timestamp = occupied ? p.getLong(key(vnKey, slot, "timestamp"), 0) : 0;
        // Reuses SaveManager's own SlotInfo type (not a duplicate) since ReaderActivity's existing
        // Save/Load dialog is already built around it and that type carries no VNDS-specific
        // meaning of its own -- just a slot index/occupied flag/preview/timestamp tuple.
        return new SaveManager.SlotInfo(slot, occupied, preview, timestamp);
    }

    public static void save(Context context, String vnKey, int slot, NsScriptEngine engine,
                             String backgroundPath, VnEngine.SpriteTransparency backgroundTransparency,
                             int backgroundAlphaMaskCells, String musicPath, List<NsSpriteEntry> sprites,
                             String lastSpeaker, List<SaveManager.SavedLine> bodyLines) {
        NsScriptEngine.Snapshot snap = engine.snapshotState();
        try {
            JSONObject numVars = new JSONObject();
            for (Map.Entry<Integer, Long> e : snap.numVars.entrySet()) {
                numVars.put(String.valueOf(e.getKey()), e.getValue());
            }
            JSONObject strVars = new JSONObject();
            for (Map.Entry<Integer, String> e : snap.strVars.entrySet()) {
                strVars.put(String.valueOf(e.getKey()), e.getValue());
            }
            JSONObject numAliases = new JSONObject();
            for (Map.Entry<String, Integer> e : snap.numAliases.entrySet()) {
                numAliases.put(e.getKey(), e.getValue());
            }
            JSONObject strAliases = new JSONObject();
            for (Map.Entry<String, Integer> e : snap.strAliases.entrySet()) {
                strAliases.put(e.getKey(), e.getValue());
            }
            JSONObject barewordConstants = new JSONObject();
            for (Map.Entry<String, String> e : snap.barewordConstants.entrySet()) {
                barewordConstants.put(e.getKey(), e.getValue());
            }
            JSONArray callStack = new JSONArray();
            for (int frame : snap.callStack) {
                callStack.put(frame);
            }
            JSONArray spritesJson = new JSONArray();
            for (NsSpriteEntry s : sprites) {
                JSONObject spriteJson = new JSONObject();
                spriteJson.put("layer", s.layer);
                spriteJson.put("x", s.x);
                spriteJson.put("y", s.y);
                spriteJson.put("path", s.path);
                spriteJson.put("transparency", s.transparency.name());
                spriteJson.put("alphaMaskCells", s.alphaMaskCells);
                spritesJson.put(spriteJson);
            }
            JSONArray linesJson = new JSONArray();
            for (SaveManager.SavedLine line : bodyLines) {
                JSONObject lineJson = new JSONObject();
                lineJson.put("t", line.text);
                lineJson.put("b", line.bold);
                linesJson.put(lineJson);
            }

            String speaker = lastSpeaker == null ? "" : lastSpeaker;
            String lastLine = bodyLines.isEmpty() ? "" : bodyLines.get(bodyLines.size() - 1).text;
            String preview = truncate(speaker.isEmpty() ? lastLine : speaker + ": " + lastLine, 60);

            prefs(context).edit()
                    .putInt(key(vnKey, slot, "pc"), snap.pc)
                    .putString(key(vnKey, slot, "numVars"), numVars.toString())
                    .putString(key(vnKey, slot, "strVars"), strVars.toString())
                    .putString(key(vnKey, slot, "numAliases"), numAliases.toString())
                    .putString(key(vnKey, slot, "strAliases"), strAliases.toString())
                    .putString(key(vnKey, slot, "barewordConstants"), barewordConstants.toString())
                    .putString(key(vnKey, slot, "callStack"), callStack.toString())
                    .putBoolean(key(vnKey, slot, "pendingPageClear"), snap.pendingPageClearOnResume)
                    .putString(key(vnKey, slot, "pendingDialogueRemainder"),
                            snap.pendingDialogueRemainder == null ? "" : snap.pendingDialogueRemainder)
                    .putString(key(vnKey, slot, "nsaDir"), snap.nsaDir)
                    .putString(key(vnKey, slot, "bg"), backgroundPath == null ? "" : backgroundPath)
                    .putString(key(vnKey, slot, "bgTransparency"), backgroundTransparency.name())
                    .putInt(key(vnKey, slot, "bgAlphaMaskCells"), backgroundAlphaMaskCells)
                    .putString(key(vnKey, slot, "music"), musicPath == null ? "" : musicPath)
                    .putString(key(vnKey, slot, "sprites"), spritesJson.toString())
                    .putString(key(vnKey, slot, "lastSpeaker"), speaker)
                    .putString(key(vnKey, slot, "lines"), linesJson.toString())
                    .putString(key(vnKey, slot, "preview"), preview)
                    .putLong(key(vnKey, slot, "timestamp"), System.currentTimeMillis())
                    .apply();
        } catch (JSONException e) {
            // Every field is a primitive/string we control; this cannot actually happen.
        }
    }

    public static NsSlotData load(Context context, String vnKey, int slot) {
        SharedPreferences p = prefs(context);
        if (!p.contains(key(vnKey, slot, "pc"))) {
            return null;
        }
        try {
            int pc = p.getInt(key(vnKey, slot, "pc"), 0);

            Map<Integer, Long> numVars = new HashMap<>();
            JSONObject numVarsJson = new JSONObject(p.getString(key(vnKey, slot, "numVars"), "{}"));
            for (Iterator<String> it = numVarsJson.keys(); it.hasNext(); ) {
                String k = it.next();
                numVars.put(Integer.parseInt(k), numVarsJson.getLong(k));
            }

            Map<Integer, String> strVars = new HashMap<>();
            JSONObject strVarsJson = new JSONObject(p.getString(key(vnKey, slot, "strVars"), "{}"));
            for (Iterator<String> it = strVarsJson.keys(); it.hasNext(); ) {
                String k = it.next();
                strVars.put(Integer.parseInt(k), strVarsJson.getString(k));
            }

            Map<String, Integer> numAliases = new HashMap<>();
            JSONObject numAliasesJson = new JSONObject(p.getString(key(vnKey, slot, "numAliases"), "{}"));
            for (Iterator<String> it = numAliasesJson.keys(); it.hasNext(); ) {
                String k = it.next();
                numAliases.put(k, numAliasesJson.getInt(k));
            }

            Map<String, Integer> strAliases = new HashMap<>();
            JSONObject strAliasesJson = new JSONObject(p.getString(key(vnKey, slot, "strAliases"), "{}"));
            for (Iterator<String> it = strAliasesJson.keys(); it.hasNext(); ) {
                String k = it.next();
                strAliases.put(k, strAliasesJson.getInt(k));
            }

            Map<String, String> barewordConstants = new HashMap<>();
            JSONObject barewordConstantsJson = new JSONObject(p.getString(key(vnKey, slot, "barewordConstants"), "{}"));
            for (Iterator<String> it = barewordConstantsJson.keys(); it.hasNext(); ) {
                String k = it.next();
                barewordConstants.put(k, barewordConstantsJson.getString(k));
            }

            List<Integer> callStack = new ArrayList<>();
            JSONArray callStackJson = new JSONArray(p.getString(key(vnKey, slot, "callStack"), "[]"));
            for (int i = 0; i < callStackJson.length(); i++) {
                callStack.add(callStackJson.getInt(i));
            }

            boolean pendingPageClear = p.getBoolean(key(vnKey, slot, "pendingPageClear"), false);
            String pendingRemainder = p.getString(key(vnKey, slot, "pendingDialogueRemainder"), "");
            String nsaDir = p.getString(key(vnKey, slot, "nsaDir"), "");

            NsScriptEngine.Snapshot engineState = new NsScriptEngine.Snapshot(
                    pc, numVars, strVars, numAliases, strAliases, callStack, pendingPageClear,
                    pendingRemainder.isEmpty() ? null : pendingRemainder, barewordConstants, nsaDir);

            String bg = p.getString(key(vnKey, slot, "bg"), "");
            VnEngine.SpriteTransparency bgTransparency =
                    parseTransparency(p.getString(key(vnKey, slot, "bgTransparency"), ""));
            int bgAlphaMaskCells = p.getInt(key(vnKey, slot, "bgAlphaMaskCells"), 1);
            String music = p.getString(key(vnKey, slot, "music"), "");

            List<NsSpriteEntry> sprites = new ArrayList<>();
            JSONArray spritesJson = new JSONArray(p.getString(key(vnKey, slot, "sprites"), "[]"));
            for (int i = 0; i < spritesJson.length(); i++) {
                JSONObject spriteJson = spritesJson.getJSONObject(i);
                sprites.add(new NsSpriteEntry(spriteJson.getInt("layer"), spriteJson.getInt("x"),
                        spriteJson.getInt("y"), spriteJson.getString("path"),
                        parseTransparency(spriteJson.optString("transparency", "")),
                        spriteJson.optInt("alphaMaskCells", 1)));
            }

            String lastSpeaker = p.getString(key(vnKey, slot, "lastSpeaker"), "");

            List<SaveManager.SavedLine> lines = new ArrayList<>();
            JSONArray linesJson = new JSONArray(p.getString(key(vnKey, slot, "lines"), "[]"));
            for (int i = 0; i < linesJson.length(); i++) {
                JSONObject lineJson = linesJson.getJSONObject(i);
                lines.add(new SaveManager.SavedLine(lineJson.getString("t"), lineJson.getBoolean("b")));
            }

            return new NsSlotData(engineState, bg, bgTransparency, bgAlphaMaskCells, music, sprites,
                    lastSpeaker, lines);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Parses a persisted transparency name, tolerating an empty/unrecognized value (e.g. a save
     * written before this field existed) by defaulting to opaque, same tolerance as an unknown
     * script command elsewhere in this format. */
    private static VnEngine.SpriteTransparency parseTransparency(String name) {
        try {
            return VnEngine.SpriteTransparency.valueOf(name);
        } catch (IllegalArgumentException e) {
            return VnEngine.SpriteTransparency.OPAQUE;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
