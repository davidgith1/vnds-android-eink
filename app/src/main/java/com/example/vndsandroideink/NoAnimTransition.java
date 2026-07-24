package com.example.vndsandroideink;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

/**
 * Starts/finishes an activity with no enter/exit animation -- the e-ink no-animation rule applies
 * to activity transitions too, not just in-screen content. {@code overridePendingTransition} is
 * deprecated since API 34 in favor of {@code overrideActivityTransition}, which this app's minSdk
 * (24) still needs to fall back on.
 *
 * <p>Unlike {@code overridePendingTransition}, the OPEN half of the new API can only be applied by
 * the activity actually being opened (typically in its own {@code onCreate}), not by whichever
 * activity happens to call {@code startActivity()} -- so {@link #start} only handles the pre-34
 * fallback; every activity ever reached via our own {@code startActivity()} (currently
 * {@code ReaderActivity} and {@code GuideLibraryActivity}) must call {@link #applyOpenOverride} at
 * the top of its own {@code onCreate} for the API 34+ half. The CLOSE half has no such
 * restriction, since the finishing activity is always the one calling {@link #finish}.
 */
final class NoAnimTransition {

    private NoAnimTransition() {
    }

    /** Call at the very top of {@code onCreate}, before {@code setContentView}, in any activity
     * that's ever reached via our own {@link #start} (not the launcher intent-filter, and not one
     * only ever returned to via {@link #finish} -- e.g. MainActivity needs neither). */
    static void applyOpenOverride(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        }
    }

    static void start(Activity activity, Intent intent) {
        activity.startActivity(intent);
        if (Build.VERSION.SDK_INT < 34) {
            activity.overridePendingTransition(0, 0);
        }
    }

    static void finish(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            activity.finish();
        } else {
            activity.finish();
            activity.overridePendingTransition(0, 0);
        }
    }
}
