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
 * Small flat popup (same style as {@link VndbFetchDialog}) for editing the title a library row
 * shows, prefilled with the current title. Saving a blank title clears the override, reverting to
 * whatever the pack itself would otherwise display (see {@link TitleOverrideManager}).
 */
public final class EditTitleDialog {

    public interface OnSave {
        void onSave(String newTitle);
    }

    private EditTitleDialog() {
    }

    public static void show(Context context, String currentTitle, OnSave onSave) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        int pad = dp(context, 20);
        content.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(context);
        titleView.setText(R.string.edit_title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        content.addView(titleView);

        EditText input = new EditText(context);
        input.setHint(R.string.edit_title_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(currentTitle);
        input.setSelection(input.getText().length());
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
        TextView saveButton = flatButton(context, context.getString(R.string.edit_title_action));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = dp(context, 8);
        buttonRow.addView(cancelButton, btnLp);
        buttonRow.addView(saveButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        saveButton.setOnClickListener(v -> {
            String newTitle = input.getText().toString();
            dialog.dismiss();
            onSave.onSave(newTitle);
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
