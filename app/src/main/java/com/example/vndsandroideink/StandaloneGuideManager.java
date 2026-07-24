package com.example.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The registry of "standalone" completion guides: entries not tied to any imported VNDS story,
 * just an imported guide.json plus either a VNDB link or a plain typed-in name. Created from the
 * Guides page's "+ Add guide" (either a VNDB fetch, the same dialog a normal library row's
 * "Get info from VNDB" uses, or {@link AddGuideByNameDialog} for a plain name), after which a
 * guide is imported for that entry the same way a VN's own guide would be. Reuses
 * {@link VndbManager} (for the linked VNDB metadata, if any) and {@link GuideManager} (for the
 * guide file itself and its checked-off progress) exactly as-is -- both are keyed by a plain
 * string with no assumption it belongs to a real VN -- just keyed by this entry's own generated
 * key instead.
 */
public final class StandaloneGuideManager {

    private static final String PREFS_FILE = "vnds_standalone_guides";
    private static final String KEYS_PREF = "keys";
    private static final String TITLE_PREF_PREFIX = "title.";

    private StandaloneGuideManager() {
    }

    public static final class Entry {
        public final String key;
        public final VndbMeta meta; // null until/unless this entry is ever linked to VNDB
        /** Resolved display title: {@code meta}'s title if linked, else whatever plain name the
         * entry was created or later renamed with, else the raw key as a last resort. */
        public final String title;
        public final boolean hasGuide;

        Entry(String key, VndbMeta meta, String title, boolean hasGuide) {
            this.key = key;
            this.meta = meta;
            this.title = title;
            this.hasGuide = hasGuide;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    private static List<String> loadKeys(Context context) {
        List<String> keys = new ArrayList<>();
        String json = prefs(context).getString(KEYS_PREF, null);
        if (json != null) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    keys.add(array.getString(i));
                }
            } catch (JSONException ignored) {
            }
        }
        return keys;
    }

    private static void saveKeys(Context context, List<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) {
            array.put(key);
        }
        prefs(context).edit().putString(KEYS_PREF, array.toString()).apply();
    }

    /** Every standalone entry, sorted by title. */
    public static List<Entry> listEntries(Context context) {
        List<Entry> result = new ArrayList<>();
        for (String key : loadKeys(context)) {
            VndbMeta meta = VndbManager.load(context, key);
            result.add(new Entry(key, meta, resolveTitle(context, key, meta), GuideManager.hasGuide(context, key)));
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    private static String resolveTitle(Context context, String key, VndbMeta meta) {
        if (meta != null && meta.title != null && !meta.title.isEmpty()) {
            return meta.title;
        }
        String manual = getManualTitle(context, key);
        return manual != null && !manual.isEmpty() ? manual : key;
    }

    private static String manualTitleKey(String key) {
        return TITLE_PREF_PREFIX + key;
    }

    private static String getManualTitle(Context context, String key) {
        return prefs(context).getString(manualTitleKey(key), null);
    }

    public static String newKey() {
        return GuideManager.newStandaloneKey();
    }

    /** Registers a freshly-created entry keyed to {@code key} -- call only after
     * {@link VndbManager#fetch} has already succeeded for this key (so {@link #listEntries} always
     * finds a real meta to show), never for re-linking an existing entry's VNDB id. */
    public static void register(Context context, String key) {
        List<String> keys = loadKeys(context);
        if (!keys.contains(key)) {
            keys.add(key);
            saveKeys(context, keys);
        }
    }

    /** Creates a fresh entry with just a plain typed-in title, no VNDB link at all -- for
     * {@link AddGuideByNameDialog}'s flow. Can still be linked to VNDB later via the row menu's
     * "Get info from VNDB", same as any other entry; the resolved title then prefers the VNDB
     * title over this one (see {@link #resolveTitle}), rather than needing to clear it. */
    public static String createManualEntry(Context context, String title) {
        String key = newKey();
        prefs(context).edit().putString(manualTitleKey(key), title).apply();
        register(context, key);
        return key;
    }

    /** Removes a standalone entry entirely: its VNDB link, its guide (if any) and all checked-off
     * progress, and the registry entry itself. Irreversible: the caller is expected to have
     * already confirmed with the user. */
    public static void deleteEntry(Context context, String key) {
        List<String> keys = loadKeys(context);
        keys.remove(key);
        saveKeys(context, keys);
        VndbManager.clear(context, key);
        GuideManager.clearProgress(context, key);
        GuideManager.deleteStandaloneGuideDir(context, key);
        prefs(context).edit().remove(manualTitleKey(key)).apply();
    }
}
