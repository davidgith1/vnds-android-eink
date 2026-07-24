package com.example.vndsandroideink;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Pads a root view by the system status/navigation bar insets so content isn't drawn underneath
 * them. Edge-to-edge is enforced by the platform once an app targets API 35+ (this app targets
 * 37), so without this every screen's top bar overlaps the status bar and bottom controls
 * overlap the navigation bar.
 */
final class EdgeToEdge {

    private EdgeToEdge() {
    }

    static void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
