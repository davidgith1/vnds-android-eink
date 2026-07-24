package com.example.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin wrapper around the app's single SharedPreferences file. */
public final class Prefs {

    private static final String FILE = "vnds_prefs";

    private static final String KEY_EINK_MODE = "eink_mode";
    private static final String KEY_MUTE_AUDIO = "mute_audio";
    private static final String KEY_TEXT_SIZE_SP = "text_size_sp";
    private static final String KEY_TEXT_SPEED_CPS = "text_speed_cps";
    private static final String KEY_INSTANT_DELAYS_EINK = "instant_delays_eink";
    private static final String KEY_AUTO_ADVANCE_WPM = "auto_advance_wpm";
    private static final String KEY_AUTO_WAIT_FOR_SFX = "auto_wait_for_sfx";
    private static final String KEY_AUTO_PAGE_PAUSE_SEC = "auto_page_pause_sec";
    private static final String KEY_USE_NOVEL_FONT = "use_novel_font";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_MUSIC_VOLUME_PCT = "music_volume_pct";
    private static final String KEY_SFX_VOLUME_PCT = "sfx_volume_pct";
    private static final String KEY_VOLUME_BUTTONS_PAGE_TURN = "volume_buttons_page_turn";
    private static final String KEY_PAGED_LIBRARY_SCROLL = "paged_library_scroll";

    public static final int MIN_TEXT_SIZE_SP = 14;
    public static final int MAX_TEXT_SIZE_SP = 32;
    private static final int DEFAULT_TEXT_SIZE_SP = 18;

    /** Characters revealed per second by the typewriter effect; only consulted outside e-ink mode
     * (e-ink always shows text instantly, no typewriter reveal at all). Default (160) is close to
     * this app's original hardcoded pacing (2 characters every 12ms, ~167 cps). */
    public static final int MIN_TEXT_SPEED_CPS = 20;
    public static final int MAX_TEXT_SPEED_CPS = 400;
    private static final int DEFAULT_TEXT_SPEED_CPS = 160;
    /** Sentinel stored in the same {@code text_speed_cps} slot: text is shown immediately outside
     * e-ink mode too, the same way e-ink mode itself already always does, instead of a very fast
     * but still real per-character typewriter delay. One step further than {@link
     * #MAX_TEXT_SPEED_CPS} on the Settings stepper. */
    public static final int TEXT_SPEED_INSTANT = -1;

    public static final int MIN_MUSIC_VOLUME_PCT = 0;
    public static final int MAX_MUSIC_VOLUME_PCT = 100;
    private static final int DEFAULT_VOLUME_PCT = 100;
    public static final int MIN_SFX_VOLUME_PCT = 0;
    public static final int MAX_SFX_VOLUME_PCT = 100;

    public static final int MIN_AUTO_ADVANCE_WPM = 100;
    public static final int MAX_AUTO_ADVANCE_WPM = 500;
    private static final int DEFAULT_AUTO_ADVANCE_WPM = 200;

    public static final int MIN_AUTO_PAGE_PAUSE_SEC = 0;
    public static final int MAX_AUTO_PAGE_PAUSE_SEC = 10;
    private static final int DEFAULT_AUTO_PAGE_PAUSE_SEC = 2;

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** E-ink mode defaults on only when this device is actually recognized as e-ink hardware (see
     * {@link EinkRefreshManager#isSupported()}) -- instant updates, no animation, forced light
     * theme, which only make sense to force by default on a real e-ink panel. Any other device
     * defaults off, but the Settings toggle still lets a user turn it on manually regardless
     * (e.g. to preview it, or for e-ink hardware this app doesn't yet brand-detect). */
    public static boolean isEinkMode(Context context) {
        return prefs(context).getBoolean(KEY_EINK_MODE, EinkRefreshManager.isSupported());
    }

    public static void setEinkMode(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_EINK_MODE, value).apply();
    }

    public static boolean isMuteAudio(Context context) {
        return prefs(context).getBoolean(KEY_MUTE_AUDIO, false);
    }

    public static void setMuteAudio(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_MUTE_AUDIO, value).apply();
    }

    public static int getTextSizeSp(Context context) {
        return prefs(context).getInt(KEY_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP);
    }

    public static void setTextSizeSp(Context context, int value) {
        prefs(context).edit().putInt(KEY_TEXT_SIZE_SP, value).apply();
    }

    public static int getTextSpeedCps(Context context) {
        return prefs(context).getInt(KEY_TEXT_SPEED_CPS, DEFAULT_TEXT_SPEED_CPS);
    }

    public static void setTextSpeedCps(Context context, int value) {
        prefs(context).edit().putInt(KEY_TEXT_SPEED_CPS, value).apply();
    }

    /** Only consulted while e-ink mode is on; e-ink mode off always plays delays in real time.
     * Defaults off: scripted delays (intro/opening pacing, etc.) are real content, not just
     * animation cruft, so skip them only if the user opts in. */
    public static boolean isInstantDelaysInEink(Context context) {
        return prefs(context).getBoolean(KEY_INSTANT_DELAYS_EINK, false);
    }

    public static void setInstantDelaysInEink(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_INSTANT_DELAYS_EINK, value).apply();
    }

    public static int getAutoAdvanceWpm(Context context) {
        return prefs(context).getInt(KEY_AUTO_ADVANCE_WPM, DEFAULT_AUTO_ADVANCE_WPM);
    }

    public static void setAutoAdvanceWpm(Context context, int value) {
        prefs(context).edit().putInt(KEY_AUTO_ADVANCE_WPM, value).apply();
    }

    /** Whether Auto-advance should extend its delay to let a playing sound effect finish. */
    public static boolean isAutoWaitForSfx(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_WAIT_FOR_SFX, true);
    }

    public static void setAutoWaitForSfx(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_AUTO_WAIT_FOR_SFX, value).apply();
    }

    /** Extra pause Auto-advance adds before wiping the box for a new page, so the player has time
     * to finish reading the current page's last line instead of it vanishing right on schedule. */
    public static int getAutoPagePauseSeconds(Context context) {
        return prefs(context).getInt(KEY_AUTO_PAGE_PAUSE_SEC, DEFAULT_AUTO_PAGE_PAUSE_SEC);
    }

    public static void setAutoPagePauseSeconds(Context context, int value) {
        prefs(context).edit().putInt(KEY_AUTO_PAGE_PAUSE_SEC, value).apply();
    }

    /** Whether to use a VN's own bundled "default.ttf", when it ships one, instead of the system
     * font. On by default: it's the story's intended presentation, not just decoration. */
    public static boolean isUseNovelFont(Context context) {
        return prefs(context).getBoolean(KEY_USE_NOVEL_FONT, true);
    }

    public static void setUseNovelFont(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_USE_NOVEL_FONT, value).apply();
    }

    /** Keeps the reader's screen on for as long as it's in the foreground, bypassing the system
     * sleep timer. Off by default: it trades battery life for this, so it's an opt-in. */
    public static boolean isKeepScreenOn(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public static void setKeepScreenOn(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    /** Relative loudness applied on top of the system volume, independently for music vs. sound
     * effects -- see {@link #isMuteAudio}, which stays the master on/off switch (muted audio never
     * starts playing regardless of these levels). Both default to 100 (full volume, unchanged from
     * this app's original behavior before these existed). */
    public static int getMusicVolumePercent(Context context) {
        return prefs(context).getInt(KEY_MUSIC_VOLUME_PCT, DEFAULT_VOLUME_PCT);
    }

    public static void setMusicVolumePercent(Context context, int value) {
        prefs(context).edit().putInt(KEY_MUSIC_VOLUME_PCT, value).apply();
    }

    public static int getSfxVolumePercent(Context context) {
        return prefs(context).getInt(KEY_SFX_VOLUME_PCT, DEFAULT_VOLUME_PCT);
    }

    public static void setSfxVolumePercent(Context context, int value) {
        prefs(context).edit().putInt(KEY_SFX_VOLUME_PCT, value).apply();
    }

    /** Whether the hardware volume up/down buttons turn pages (see {@code ReaderActivity}'s
     * "isAdvanceKey") instead of behaving like normal Android volume buttons. On by default,
     * matching this app's original (only) behavior; turning it off frees the buttons to adjust the
     * system volume the ordinary way instead. */
    public static boolean isVolumeButtonsPageTurn(Context context) {
        return prefs(context).getBoolean(KEY_VOLUME_BUTTONS_PAGE_TURN, true);
    }

    public static void setVolumeButtonsPageTurn(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_VOLUME_BUTTONS_PAGE_TURN, value).apply();
    }

    /** Shows up/down instant-jump buttons on the library list, the same no-motion paging
     * {@code SettingsDialog}/{@code GuideDialog} already use for their own scroll areas, instead of
     * (or alongside) ordinary drag scrolling -- a continuous drag follows the finger frame-by-frame,
     * which is exactly the kind of motion e-ink panels struggle to redraw cleanly. Off by default:
     * only e-ink hardware benefits, and drag-scrolling still works either way. */
    public static boolean isPagedLibraryScroll(Context context) {
        return prefs(context).getBoolean(KEY_PAGED_LIBRARY_SCROLL, false);
    }

    public static void setPagedLibraryScroll(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_PAGED_LIBRARY_SCROLL, value).apply();
    }

    /** Resets every setting in this file back to its default value. Safe to wipe outright:
     * save-game data lives in a completely separate SharedPreferences file ("vnds_saves", owned by
     * {@code SaveManager}/{@code NsSaveManager}), never in this one. */
    public static void resetAllToDefaults(Context context) {
        prefs(context).edit().clear().apply();
    }
}
