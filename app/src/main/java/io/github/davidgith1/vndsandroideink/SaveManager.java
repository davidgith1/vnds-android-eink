package io.github.davidgith1.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.davidgith1.vndsandroideink.vnds.ScriptEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Reads/writes the fixed set of per-VN save slots backing the reader's save/load menu. */
public final class SaveManager {

    public static final int SLOT_COUNT = 24;
    /** A reserved slot index (outside 1..SLOT_COUNT, so {@link #listSlots} never surfaces it in
     * the manual Save/Load menu) that's kept always current with the latest reading position --
     * updated automatically on leaving the reader, not by any explicit user action. */
    public static final int SLOT_RESUME = 0;

    private static final String PREFS_FILE = "vnds_saves";

    private SaveManager() {
    }

    /** One line of the saved dialogue box: its text and whether it was a bold speaker line. */
    public static final class SavedLine {
        public final String text;
        public final boolean bold;

        public SavedLine(String text, boolean bold) {
            this.text = text;
            this.bold = bold;
        }
    }

    /** One active foreground sprite layer: since setimg always adds (never replaces by
     * position), multiple entries can share the same (x, y). */
    public static final class SpriteEntry {
        public final int x;
        public final int y;
        public final String path;

        public SpriteEntry(int x, int y, String path) {
            this.x = x;
            this.y = y;
            this.path = path;
        }
    }

    public static final class SlotInfo {
        public final int index;
        public final boolean occupied;
        public final String preview;
        public final long timestamp;

        public SlotInfo(int index, boolean occupied, String preview, long timestamp) {
            this.index = index;
            this.occupied = occupied;
            this.preview = preview;
            this.timestamp = timestamp;
        }
    }

    public static final class SlotData {
        public final String file;
        public final int pc;
        public final Map<String, String> vars;
        public final String backgroundPath;
        public final String musicPath;
        public final List<SpriteEntry> sprites;
        public final String lastSpeaker;
        public final List<SavedLine> bodyLines;

        SlotData(String file, int pc, Map<String, String> vars, String backgroundPath, String musicPath,
                 List<SpriteEntry> sprites, String lastSpeaker, List<SavedLine> bodyLines) {
            this.file = file;
            this.pc = pc;
            this.vars = vars;
            this.backgroundPath = backgroundPath;
            this.musicPath = musicPath;
            this.sprites = sprites;
            this.lastSpeaker = lastSpeaker;
            this.bodyLines = bodyLines;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    private static String key(String vnKey, int slot, String suffix) {
        return vnKey + "." + slot + "." + suffix;
    }

    /** Wipes every save slot and the global-variables blob for a VN, e.g. when it's deleted from
     * the library so stale save data doesn't linger under a name nothing references anymore. */
    public static void deleteAll(Context context, String vnKey) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String prefix = vnKey + ".";
        for (String existingKey : prefs(context).getAll().keySet()) {
            if (existingKey.startsWith(prefix)) {
                editor.remove(existingKey);
            }
        }
        editor.apply();
    }

    public static boolean hasResume(Context context, String vnKey) {
        return prefs(context).contains(key(vnKey, SLOT_RESUME, "file"));
    }

    /** Describes the resume slot the same way {@link #listSlots} describes a manual one, so it
     * can be shown alongside them (e.g. in the library's "Load save" list). */
    public static SlotInfo resumeSlotInfo(Context context, String vnKey) {
        SharedPreferences p = prefs(context);
        boolean occupied = p.contains(key(vnKey, SLOT_RESUME, "file"));
        String preview = occupied ? p.getString(key(vnKey, SLOT_RESUME, "preview"), "") : "";
        long timestamp = occupied ? p.getLong(key(vnKey, SLOT_RESUME, "timestamp"), 0) : 0;
        return new SlotInfo(SLOT_RESUME, occupied, preview, timestamp);
    }

    /** Called once a story reaches its ending: resuming a finished story doesn't mean anything. */
    public static void clearResume(Context context, String vnKey) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String prefix = vnKey + "." + SLOT_RESUME + ".";
        for (String existingKey : prefs(context).getAll().keySet()) {
            if (existingKey.startsWith(prefix)) {
                editor.remove(existingKey);
            }
        }
        editor.apply();
    }

    private static String playMillisKey(String vnKey) {
        return vnKey + ".playMillis";
    }

    /** Total wall-clock time this VN has actually been on screen, summed across every session. */
    public static long getTotalPlayMillis(Context context, String vnKey) {
        return prefs(context).getLong(playMillisKey(vnKey), 0);
    }

    /** Adds to that running total; called with however long the just-ended session lasted. */
    public static void addPlayMillis(Context context, String vnKey, long millis) {
        if (millis <= 0) {
            return;
        }
        long total = getTotalPlayMillis(context, vnKey) + millis;
        prefs(context).edit().putLong(playMillisKey(vnKey), total).apply();
    }

    public static List<SlotInfo> listSlots(Context context, String vnKey) {
        SharedPreferences p = prefs(context);
        List<SlotInfo> result = new ArrayList<>();
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            boolean occupied = p.contains(key(vnKey, slot, "file"));
            String preview = occupied ? p.getString(key(vnKey, slot, "preview"), "") : "";
            long timestamp = occupied ? p.getLong(key(vnKey, slot, "timestamp"), 0) : 0;
            result.add(new SlotInfo(slot, occupied, preview, timestamp));
        }
        return result;
    }

    public static void save(Context context, String vnKey, int slot, ScriptEngine engine,
                             String backgroundPath, String musicPath, List<SpriteEntry> sprites,
                             String lastSpeaker, List<SavedLine> bodyLines) {
        try {
            JSONObject vars = new JSONObject();
            for (Map.Entry<String, String> e : engine.getVariablesSnapshot().entrySet()) {
                vars.put(e.getKey(), e.getValue());
            }
            JSONArray spritesJson = new JSONArray();
            for (SpriteEntry e : sprites) {
                JSONObject spriteJson = new JSONObject();
                spriteJson.put("x", e.x);
                spriteJson.put("y", e.y);
                spriteJson.put("path", e.path);
                spritesJson.put(spriteJson);
            }
            JSONArray linesJson = new JSONArray();
            for (SavedLine line : bodyLines) {
                JSONObject lineJson = new JSONObject();
                lineJson.put("t", line.text);
                lineJson.put("b", line.bold);
                linesJson.put(lineJson);
            }

            String speaker = lastSpeaker == null ? "" : lastSpeaker;
            String lastLine = bodyLines.isEmpty() ? "" : bodyLines.get(bodyLines.size() - 1).text;
            String preview = truncate(speaker.isEmpty() ? lastLine : speaker + ": " + lastLine, 60);

            prefs(context).edit()
                    .putString(key(vnKey, slot, "file"), engine.getCurrentFile())
                    .putInt(key(vnKey, slot, "pc"), engine.getPc())
                    .putString(key(vnKey, slot, "vars"), vars.toString())
                    .putString(key(vnKey, slot, "bg"), backgroundPath == null ? "" : backgroundPath)
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

    public static SlotData load(Context context, String vnKey, int slot) {
        SharedPreferences p = prefs(context);
        String file = p.getString(key(vnKey, slot, "file"), null);
        if (file == null) {
            return null;
        }
        try {
            int pc = p.getInt(key(vnKey, slot, "pc"), 0);

            Map<String, String> vars = new HashMap<>();
            JSONObject varsJson = new JSONObject(p.getString(key(vnKey, slot, "vars"), "{}"));
            for (Iterator<String> it = varsJson.keys(); it.hasNext(); ) {
                String k = it.next();
                vars.put(k, varsJson.getString(k));
            }

            String bg = p.getString(key(vnKey, slot, "bg"), "");
            String music = p.getString(key(vnKey, slot, "music"), "");

            List<SpriteEntry> sprites = new ArrayList<>();
            JSONArray spritesJson = new JSONArray(p.getString(key(vnKey, slot, "sprites"), "[]"));
            for (int i = 0; i < spritesJson.length(); i++) {
                JSONObject spriteJson = spritesJson.getJSONObject(i);
                sprites.add(new SpriteEntry(spriteJson.getInt("x"), spriteJson.getInt("y"),
                        spriteJson.getString("path")));
            }

            String lastSpeaker = p.getString(key(vnKey, slot, "lastSpeaker"), "");

            List<SavedLine> lines = new ArrayList<>();
            JSONArray linesJson = new JSONArray(p.getString(key(vnKey, slot, "lines"), "[]"));
            for (int i = 0; i < linesJson.length(); i++) {
                JSONObject lineJson = linesJson.getJSONObject(i);
                lines.add(new SavedLine(lineJson.getString("t"), lineJson.getBoolean("b")));
            }

            return new SlotData(file, pc, vars, bg, music, sprites, lastSpeaker, lines);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * gsetvar variables (the VNDS format's "global.sav"): stored independently of any save slot,
     * one blob per VN, so they survive across saves and even a brand new game.
     */
    public static Map<String, String> loadGlobals(Context context, String vnKey) {
        Map<String, String> result = new HashMap<>();
        try {
            JSONObject json = new JSONObject(prefs(context).getString(vnKey + ".globals", "{}"));
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                String k = it.next();
                result.put(k, json.getString(k));
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    public static void saveGlobals(Context context, String vnKey, Map<String, String> globals) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> e : globals.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }
            prefs(context).edit().putString(vnKey + ".globals", json.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Identifies a save-export file as this app's own format, distinct from any other JSON file
     * a user might mistakenly try to import. */
    private static final String EXPORT_APP_ID = "vnds-android-eink-saves";
    private static final int EXPORT_FORMAT = 1;

    /** Bundles every save slot, the resume slot, globals, and play time for one VN into a single
     * JSON document a user can move across devices or keep as a backup. Every entry keeps the
     * type it was stored as (SharedPreferences mixes String/Int/Long) so {@link #importData} can
     * restore it exactly, rather than guessing from the string form. */
    public static JSONObject exportData(Context context, String vnKey, String title) throws JSONException {
        Map<String, ?> all = prefs(context).getAll();
        String prefix = vnKey + ".";
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix)) {
                continue;
            }
            Object value = e.getValue();
            JSONObject typed = new JSONObject();
            if (value instanceof String) {
                typed.put("type", "s").put("v", value);
            } else if (value instanceof Integer) {
                typed.put("type", "i").put("v", value);
            } else if (value instanceof Long) {
                typed.put("type", "l").put("v", value);
            } else if (value instanceof Boolean) {
                typed.put("type", "b").put("v", value);
            } else if (value instanceof Float) {
                typed.put("type", "f").put("v", value);
            } else {
                continue; // not a type this file ever writes; nothing to restore it as
            }
            entries.put(k.substring(prefix.length()), typed);
        }
        JSONObject root = new JSONObject();
        root.put("app", EXPORT_APP_ID);
        root.put("format", EXPORT_FORMAT);
        root.put("vnTitle", title);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("entries", entries);
        return root;
    }

    public static boolean isSaveExportFile(JSONObject root) {
        return EXPORT_APP_ID.equals(root.optString("app"));
    }

    /** The VN title recorded at export time, shown so the user can tell whether a file they're
     * about to import actually belongs to the novel they're importing it into. */
    public static String exportedTitle(JSONObject root) {
        return root.optString("vnTitle", "");
    }

    /** Replaces every save slot, the resume slot, globals, and play time currently stored for
     * {@code vnKey} with what's in {@code root} -- a full overwrite, not a merge, so a slot this
     * VN currently has that the export doesn't (e.g. one made after the export) is cleared too. */
    public static void importData(Context context, String vnKey, JSONObject root) throws JSONException {
        JSONObject entries = root.getJSONObject("entries");
        deleteAll(context, vnKey);
        SharedPreferences.Editor editor = prefs(context).edit();
        Iterator<String> it = entries.keys();
        while (it.hasNext()) {
            String suffix = it.next();
            JSONObject typed = entries.getJSONObject(suffix);
            String key = vnKey + "." + suffix;
            switch (typed.getString("type")) {
                case "s":
                    editor.putString(key, typed.getString("v"));
                    break;
                case "i":
                    editor.putInt(key, typed.getInt("v"));
                    break;
                case "l":
                    editor.putLong(key, typed.getLong("v"));
                    break;
                case "b":
                    editor.putBoolean(key, typed.getBoolean("v"));
                    break;
                case "f":
                    editor.putFloat(key, (float) typed.getDouble("v"));
                    break;
                default:
                    break;
            }
        }
        editor.apply();
    }
}
