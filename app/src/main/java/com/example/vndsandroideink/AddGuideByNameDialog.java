package com.example.vndsandroideink;

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
 * Small flat popup (same construction/style as {@link VndbFetchDialog}) for typing a plain title
 * to create a standalone guide entry with -- no VNDB id, no lookup, just a name. Unlike
 * VndbFetchDialog's id field, this is ordinary text (no visible-password input-type trick, which
 * there only exists to stop autocorrect from mangling a "v7"-style id).
 */
public final class AddGuideByNameDialog {

    public interface OnAdd {
        void onAdd(String title);
    }

    private AddGuideByNameDialog() {
    }

    public static void show(Context context, OnAdd onAdd) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        int pad = dp(context, 20);
        content.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(context);
        titleView.setText(R.string.add_guide_name_title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        content.addView(titleView);

        EditText input = new EditText(context);
        input.setHint(R.string.add_guide_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
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
        TextView addButton = flatButton(context, context.getString(R.string.add_guide_name_action));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = dp(context, 8);
        buttonRow.addView(cancelButton, btnLp);
        buttonRow.addView(addButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        addButton.setOnClickListener(v -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) {
                return; // caller has no meaningful key to work with; just leave the dialog up
            }
            dialog.dismiss();
            onAdd.onAdd(title);
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
