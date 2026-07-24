package io.github.davidgith1.vndsandroideink;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

public class VndsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // The app's reading UI (toolbars, text panel, library list) is always white-on-black,
        // regardless of system dark mode -- that's the whole point of an e-ink-friendly reader.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
}
