package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Shown when opening a VN from the library: a small flat e-ink-friendly popup offering "Start
 * from beginning" plus "Resume" and/or "Load save…" when they're actually available -- matching
 * the app's other custom popups (no rounded corners, no elevation/shadow, no enter/exit animation).
 */
public final class LaunchChooserDialog {

    public interface Listener {
        void onStartFromBeginning();
        void onResume();
        void onLoadSave();
    }

    private LaunchChooserDialog() {
    }

    public static void show(Context context, String title, boolean hasResume, boolean hasSaves, Listener listener) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        // Padding equal to the background's stroke width, same reason as the overflow menu panels:
        // without it, the full-width rows below paint over the left/right edges of the border.
        int borderPad = dp(context, 2);
        content.setPadding(borderPad, borderPad, borderPad, borderPad);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(16);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setMaxLines(2);
        int titlePad = dp(context, 16);
        titleView.setPadding(titlePad, titlePad, titlePad, dp(context, 8));
        content.addView(titleView);

        Dialog dialog = new Dialog(context, R.style.Theme_VNDSAndroidEink_FlatDialog);

        if (hasResume) {
            addDivider(context, content);
            addRow(context, content, R.string.resume_novel, () -> {
                dialog.dismiss();
                listener.onResume();
            });
        }
        addDivider(context, content);
        addRow(context, content, R.string.start_from_beginning, () -> {
            dialog.dismiss();
            if (hasResume) {
                // Starting fresh will overwrite the resume snapshot the next time the player
                // leaves the story, same as loading any other save would -- confirm first.
                ConfirmDialog.show(context, context.getString(R.string.confirm_start_over_title),
                        context.getString(R.string.confirm_start_over_message),
                        context.getString(R.string.start_from_beginning), listener::onStartFromBeginning, null);
            } else {
                listener.onStartFromBeginning();
            }
        });
        if (hasSaves) {
            addDivider(context, content);
            addRow(context, content, R.string.load_save, () -> {
                dialog.dismiss();
                listener.onLoadSave();
            });
        }

        content.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 260), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(content);
        dialog.show();
    }

    private static void addRow(Context context, LinearLayout content, int textRes, Runnable onClick) {
        TextView row = new TextView(context);
        row.setText(textRes);
        row.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        row.setTextSize(16);
        row.setBackgroundResource(R.drawable.bg_library_item);
        row.setClickable(true);
        row.setFocusable(true);
        int pad = dp(context, 16);
        row.setPadding(pad, pad, pad, pad);
        row.setOnClickListener(v -> onClick.run());
        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private static void addDivider(Context context, LinearLayout content) {
        View divider = new View(context);
        divider.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_divider));
        content.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)));
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
