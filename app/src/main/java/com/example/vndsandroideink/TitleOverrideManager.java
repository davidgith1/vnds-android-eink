package com.example.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;

/** User-typed title overrides for library rows, keyed by VN folder name -- lets the user rename how
 * a VN is displayed in the library (see the row menu's "Edit title") without touching the pack's
 * own files; {@link VnImporter#scanLocal} applies this on top of whatever default title the pack
 * itself would otherwise show (info.txt, NScripter's "caption", or the raw folder name). */
public final class TitleOverrideManager {

    private static final String PREFS_FILE = "vnds_title_overrides";

    private TitleOverrideManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** The user-set override for {@code vnKey}, or null if never set (or cleared back to default). */
    public static String load(Context context, String vnKey) {
        return prefs(context).getString(vnKey, null);
    }

    /** Sets the override, or clears it back to the pack's own default title if {@code newTitle} is
     * blank. */
    public static void set(Context context, String vnKey, String newTitle) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (newTitle == null || newTitle.trim().isEmpty()) {
            editor.remove(vnKey);
        } else {
            editor.putString(vnKey, newTitle.trim());
        }
        editor.apply();
    }

    public static void clear(Context context, String vnKey) {
        prefs(context).edit().remove(vnKey).apply();
    }
}
