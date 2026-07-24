package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * A small Yes/No confirmation in the app's flat e-ink visual language: no rounded corners, no
 * elevation/shadow, no fade/scale enter-exit animation, and no button ripple -- unlike a stock
 * {@code AlertDialog.Builder} confirmation, which redraws with all of that by default and reads
 * as visually inconsistent (and needlessly refresh-heavy) next to the rest of the app.
 */
public final class ConfirmDialog {

    public interface OnConfirmed {
        void onConfirmed();
    }

    private ConfirmDialog() {
    }

    public static void show(Context context, String title, String message, String confirmLabel,
                             OnConfirmed onConfirmed, Runnable onDismiss) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        int pad = dp(context, 20);
        content.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        content.addView(titleView);

        TextView messageView = new TextView(context);
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        messageView.setTextSize(15);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.topMargin = dp(context, 12);
        content.addView(messageView, messageLp);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonRowLp.topMargin = dp(context, 20);
        content.addView(buttonRow, buttonRowLp);

        Dialog dialog = new Dialog(context, R.style.Theme_VNDSAndroidEink_FlatDialog);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(content);
        // Fires on every way this closes -- confirm, cancel, back, or outside-tap -- so a caller
        // that froze something (e.g. an overlay pause) before showing this can reliably undo it
        // regardless of which way the user leaves, not just on confirm.
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }

        TextView cancelButton = flatButton(context, context.getString(R.string.cancel));
        TextView confirmButton = flatButton(context, confirmLabel);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = dp(context, 8);
        buttonRow.addView(cancelButton, btnLp);
        buttonRow.addView(confirmButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        confirmButton.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirmed.onConfirmed();
        });

        dialog.show();
    }

    private static TextView flatButton(Context context, String label) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.choice_button_text));
        button.setBackground(ContextCompat.getDrawable(context, R.drawable.bg_choice_button));
        button.setTextSize(15);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
