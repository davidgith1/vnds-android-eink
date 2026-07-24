package io.github.davidgith1.vndsandroideink;

/**
 * "free" flavor counterpart to the "full" flavor's real implementation (see that source set's
 * copy of this same class for the actual Onyx EPD SDK integration). This flavor has no
 * proprietary dependencies at all -- for distribution through F-Droid -- so hardware full-screen
 * refresh on Onyx/Boox devices is simply unavailable here; every caller already treats
 * {@link #isSupported()} as the single gate before touching e-ink-specific behavior, so returning
 * false here just means Boox hardware falls back to this app's ordinary (non-Onyx) instant-update
 * behavior, same as any other Android device.
 */
final class EinkRefreshManager {

    private EinkRefreshManager() {
    }

    static boolean isSupported() {
        return false;
    }

    static void fullRefresh() {
    }
}
