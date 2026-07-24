package com.example.vndsandroideink;

import android.os.Build;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;

import java.util.Locale;

/**
 * Integration with Onyx's EPD (e-paper display) SDK -- onyxsdk-device, see
 * https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/README.md and
 * https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Base-SDK.md -- for hardware
 * screen-refresh control on Onyx devices (the Boox line). The SDK is compiled in directly (see
 * app/build.gradle.kts and the repo.boox.com maven repo in settings.gradle.kts), but its calls
 * only do anything meaningful on actual Onyx hardware -- every call here is gated on
 * {@link #isSupported()} so it's a no-op on any other device.
 *
 * <p>Only the full-screen flash below is wired up. Per-view partial refresh (GU/DU/etc. via
 * {@code EpdController.invalidate}/{@code refreshScreen}) was tried and dropped: every SDK call
 * reflects into a hidden vendor framework class, {@code android.onyx.ViewUpdateHelper}, and
 * confirmed on a real Boox Note2 that {@code repaintEveryThing(UpdateMode)} (full-screen) works
 * and is visually distinguishable per mode, but no per-view call produced any visible difference
 * -- despite the method genuinely existing and every {@link UpdateMode} resolving to a real,
 * distinct numeric ID. Not worth chasing further; full-screen is the only lever that's proven.
 */
final class EinkRefreshManager {

    private static final boolean SUPPORTED = isOnyxDevice();

    private EinkRefreshManager() {
    }

    /** Whether this device is Onyx hardware the EPD SDK calls below are meaningful on. */
    static boolean isSupported() {
        return SUPPORTED;
    }

    private static boolean isOnyxDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        return manufacturer.toLowerCase(Locale.ROOT).contains("onyx")
                || brand.toLowerCase(Locale.ROOT).contains("onyx");
    }

    /** Forces an immediate full-screen flashing refresh (16-level gray, GC mode), clearing any
     * ghosting built up since the last one. Confirmed working on a real Boox Note2. No-op if
     * unsupported. */
    static void fullRefresh() {
        if (!SUPPORTED) {
            return;
        }
        try {
            EpdController.repaintEveryThing(UpdateMode.GC);
        } catch (RuntimeException ignored) {
        }
    }
}
