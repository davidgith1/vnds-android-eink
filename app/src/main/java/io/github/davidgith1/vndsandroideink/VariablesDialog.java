package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.TreeMap;

/**
 * Fullscreen list of every current setvar (local, kept in the save slot) and gsetvar (global, kept
 * in global.sav) variable, editable in place. Same e-ink-friendly shape as SettingsDialog/
 * TextLogDialog: flat rows, no ripple/elevation, an explicit up/down instant-jump scroll pair
 * alongside the ScrollView rather than relying on a raw drag-scroll gesture.
 */
public final class VariablesDialog {

    public interface OnVarChanged {
        /** @param global false for a setvar (local/save) variable, true for a gsetvar (global) one. */
        void onVarChanged(boolean global, String name, String value);
    }

    private VariablesDialog() {
    }

    public static void show(Context context, Map<String, String> localVars, Map<String, String> globalVars,
                             OnVarChanged onChanged, Runnable onDismiss) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        addSectionHeader(context, list, R.string.variables_section_local);
        addVarRows(context, list, localVars, false, onChanged);
        addSectionHeader(context, list, R.string.variables_section_global);
        addVarRows(context, list, globalVars, true, onChanged);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_background));

        TextView title = new TextView(context);
        title.setText(R.string.variables_title);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        int titlePad = dp(context, 16);
        title.setPadding(titlePad, titlePad, titlePad, dp(context, 8));
        content.addView(title, matchWrap());

        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView closeButton = dialogButton(context, R.string.close, 0);
        TextView upButton = dialogButton(context, R.string.settings_scroll_up, R.string.settings_scroll_up_desc);
        TextView downButton = dialogButton(context, R.string.settings_scroll_down, R.string.settings_scroll_down_desc);
        buttonRow.addView(closeButton, weight1(context));
        buttonRow.addView(upButton, weight1(context));
        buttonRow.addView(downButton, weight1(context));
        content.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 56)));

        // Not the "_Fullscreen" variant: that hides the status bar while shown and un-hides it
        // again on dismiss, which forces a redraw/reflow of the whole screen right as the dialog
        // opens and closes (same reasoning as SettingsDialog/TextLogDialog).
        EdgeToEdge.applyInsets(content);

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        closeButton.setOnClickListener(v -> dialog.dismiss());
        // scrollBy (not smoothScrollBy) jumps a full page instantly, with no animated glide for
        // e-ink to have to redraw through.
        upButton.setOnClickListener(v -> scrollView.scrollBy(0, -scrollView.getHeight()));
        downButton.setOnClickListener(v -> scrollView.scrollBy(0, scrollView.getHeight()));

        dialog.show();
    }

    private static void addSectionHeader(Context context, LinearLayout list, int textRes) {
        TextView header = new TextView(context);
        header.setText(textRes);
        header.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        header.setTextSize(14);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        int padH = dp(context, 16);
        header.setPadding(padH, dp(context, 12), padH, dp(context, 4));
        list.addView(header, matchWrap());
    }

    private static void addVarRows(Context context, LinearLayout list, Map<String, String> vars,
                                    boolean global, OnVarChanged onChanged) {
        if (vars.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.variables_none);
            empty.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
            empty.setTextSize(14);
            int padH = dp(context, 16);
            empty.setPadding(padH, 0, padH, dp(context, 8));
            list.addView(empty, matchWrap());
            return;
        }
        // Sorted for a stable, scannable order -- the underlying map's iteration order is
        // otherwise arbitrary and would reshuffle every time the dialog reopens.
        for (Map.Entry<String, String> e : new TreeMap<>(vars).entrySet()) {
            list.addView(buildVarRow(context, e.getKey(), e.getValue(), global, onChanged), matchWrap());
            addDivider(context, list);
        }
    }

    private static LinearLayout buildVarRow(Context context, String name, String value, boolean global,
                                             OnVarChanged onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(context, 16);
        int padV = dp(context, 8);
        row.setPadding(padH, padV, padH, padV);

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        nameView.setTextSize(16);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.rightMargin = dp(context, 12);
        row.addView(nameView, nameLp);

        EditText valueInput = new EditText(context);
        valueInput.setText(value);
        valueInput.setSingleLine(true);
        valueInput.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        valueInput.setInputType(InputType.TYPE_CLASS_TEXT);
        valueInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        valueInput.setGravity(Gravity.END);
        row.addView(valueInput, new LinearLayout.LayoutParams(dp(context, 120), LinearLayout.LayoutParams.WRAP_CONTENT));

        // Committed on focus-lost (tapping away, or Done on the keyboard), not on every keystroke
        // -- a mid-edit partial value (e.g. "1" while typing "12") must never briefly become the
        // live script variable.
        valueInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                onChanged.onVarChanged(global, name, valueInput.getText().toString());
            }
        });
        valueInput.setOnEditorActionListener((v, actionId, event) -> {
            valueInput.clearFocus();
            return false;
        });

        return row;
    }

    private static TextView dialogButton(Context context, int textRes, int contentDescriptionRes) {
        TextView button = new TextView(context);
        button.setText(textRes);
        if (contentDescriptionRes != 0) {
            button.setContentDescription(context.getString(contentDescriptionRes));
        }
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.choice_button_text));
        button.setBackground(ContextCompat.getDrawable(context, R.drawable.bg_choice_button));
        button.setTextSize(16);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private static void addDivider(Context context, LinearLayout list) {
        View divider = new View(context);
        divider.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_divider));
        list.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)));
    }

    private static LinearLayout.LayoutParams weight1(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        int margin = dp(context, 4);
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        return lp;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
