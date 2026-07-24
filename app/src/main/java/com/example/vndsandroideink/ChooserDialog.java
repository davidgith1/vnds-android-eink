package com.example.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * A small flat, bordered popup listing a handful of mutually-exclusive actions as tappable rows
 * (not a plain {@code AlertDialog.Builder().setItems()}, since a raw system list dialog gives no
 * way to put the app's usual 1px border on individual rows). Shared by the library's
 * "+ Import novel" chooser and the Guides page's "+ Add guide" chooser.
 */
public final class ChooserDialog {

    /** One tappable row: {@code labelRes}'s text, and what runs after the popup closes. */
    public static final class Row {
        final int labelRes;
        final Runnable onClick;

        public Row(int labelRes, Runnable onClick) {
            this.labelRes = labelRes;
            this.onClick = onClick;
        }
    }

    private ChooserDialog() {
    }

    public static void show(Context context, int titleRes, Row... rows) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        // Same background/border as every other popup in the app -- including the three-dot
        // menu's own panel -- rather than the thinner 1px bg_menu_popup this used previously.
        content.setBackgroundResource(R.drawable.bg_menu_panel);
        int borderPad = dp(context, 2);
        content.setPadding(borderPad, borderPad, borderPad, borderPad);

        TextView titleView = new TextView(context);
        titleView.setText(titleRes);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        titleView.setTextSize(16);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        int titlePad = dp(context, 16);
        titleView.setPadding(titlePad, titlePad, titlePad, dp(context, 8));
        content.addView(titleView);

        Dialog dialog = new Dialog(context, R.style.Theme_VNDSAndroidEink_FlatDialog);

        for (Row row : rows) {
            addRow(context, content, row.labelRes, dialog, row.onClick);
        }

        content.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 280), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(content);
        dialog.show();
    }

    private static void addRow(Context context, LinearLayout content, int textRes, Dialog dialog, Runnable onClick) {
        TextView row = new TextView(context);
        row.setText(textRes);
        row.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        row.setTextSize(16);
        row.setBackgroundResource(R.drawable.bg_bordered_row);
        row.setClickable(true);
        row.setFocusable(true);
        int pad = dp(context, 16);
        row.setPadding(pad, pad, pad, pad);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            onClick.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 8);
        content.addView(row, lp);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
