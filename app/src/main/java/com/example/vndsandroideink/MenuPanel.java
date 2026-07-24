package com.example.vndsandroideink;

import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

/**
 * Shows/hides the slide-down hamburger menu panel + its scrim, and wires the "back closes the menu
 * if it's open, otherwise falls through to normal back behavior" pattern -- identical in
 * MainActivity and ReaderActivity.
 */
final class MenuPanel {

    private final View panel;
    private final View scrim;

    MenuPanel(View panel, View scrim) {
        this.panel = panel;
        this.scrim = scrim;
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        panel.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
    }

    void close() {
        panel.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
    }

    /** @param onClose extra hook run whenever back-press closes the menu (e.g. ReaderActivity also
     *                 needs to un-pause whatever the menu paused); may be null. */
    void wireBackPress(ComponentActivity activity, Runnable onClose) {
        wireBackPress(activity, onClose, null);
    }

    /** @param onClose see the 2-arg overload's doc.
     * @param onBackWithMenuClosed run instead of falling through to the platform's own default
     *                             back behavior when back is pressed and the menu panel is NOT
     *                             open -- e.g. ReaderActivity's own "confirm before leaving if not
     *                             resumable" check, the same one its menu's own Library row already
     *                             applies, instead of finishing the activity unconditionally. Null
     *                             keeps the plain default-back-finish behavior the 2-arg overload
     *                             always had. */
    void wireBackPress(ComponentActivity activity, Runnable onClose, Runnable onBackWithMenuClosed) {
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isOpen()) {
                    close();
                    if (onClose != null) {
                        onClose.run();
                    }
                    return;
                }
                if (onBackWithMenuClosed != null) {
                    onBackWithMenuClosed.run();
                    return;
                }
                setEnabled(false);
                activity.getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }
}
