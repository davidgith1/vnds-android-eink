package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Small flat popup (matching {@link ConfirmDialog}'s style: no rounded corners, no elevation, no
 * enter/exit animation) for typing in a VNDB id to link a library entry to, e.g. "v7" for
 * Tsukihime -- VNDB entries can't be matched automatically, so this is always a manual id.
 */
public final class VndbFetchDialog {

    public interface OnFetch {
        void onFetch(String rawId);
    }

    private VndbFetchDialog() {
    }

    public static void show(Context context, OnFetch onFetch) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        int pad = dp(context, 20);
        content.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(context);
        titleView.setText(R.string.vndb_fetch_title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        content.addView(titleView);

        EditText input = new EditText(context);
        input.setHint(R.string.vndb_fetch_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        input.setHintTextColor(ContextCompat.getColor(context, R.color.eink_text));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(context, 12);
        content.addView(input, inputLp);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonRowLp.topMargin = dp(context, 20);
        content.addView(buttonRow, buttonRowLp);

        Dialog dialog = new Dialog(context, R.style.Theme_VNDSAndroidEink_FlatDialog);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                dp(context, 280), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(content);

        TextView cancelButton = flatButton(context, context.getString(R.string.cancel));
        TextView fetchButton = flatButton(context, context.getString(R.string.vndb_fetch_action));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = dp(context, 8);
        buttonRow.addView(cancelButton, btnLp);
        buttonRow.addView(fetchButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        fetchButton.setOnClickListener(v -> {
            String rawId = input.getText().toString();
            dialog.dismiss();
            onFetch.onFetch(rawId);
        });

        dialog.show();
        input.requestFocus();
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
