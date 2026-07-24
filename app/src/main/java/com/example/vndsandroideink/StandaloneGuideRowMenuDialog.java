package com.example.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * The per-row "⋮" popup on a Guides-page entry: same flat, non-animated popup style as
 * {@link VnRowMenuDialog}, but scoped to what a standalone entry actually has -- no save data, so
 * no export/import-saves rows, but an entry-deletion row VnRowMenuDialog has no equivalent for
 * (deleting a VN is a whole separate action from its row's own menu there).
 */
public final class StandaloneGuideRowMenuDialog {

    public interface Listener {
        void onGetInfoFromVndb();
        void onVisitVndbPage();
        void onImportGuide();
        void onViewGuide();
        void onDeleteGuide();
        void onDeleteEntry();
    }

    private StandaloneGuideRowMenuDialog() {
    }

    /** @param hasGuide whether a completion guide is already imported for this entry -- "View
     *                  guide" and "Delete guide" are only shown when there's one. */
    public static void show(Context context, String title, boolean hasGuide, Listener listener) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_menu_panel);
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

        addDivider(context, content);
        addRow(context, content, R.string.vndb_get_info, () -> {
            dialog.dismiss();
            listener.onGetInfoFromVndb();
        });
        addDivider(context, content);
        addRow(context, content, R.string.vndb_visit_page, () -> {
            dialog.dismiss();
            listener.onVisitVndbPage();
        });
        addDivider(context, content);
        addRow(context, content, R.string.import_guide, () -> {
            dialog.dismiss();
            listener.onImportGuide();
        });
        if (hasGuide) {
            addDivider(context, content);
            addRow(context, content, R.string.view_guide, () -> {
                dialog.dismiss();
                listener.onViewGuide();
            });
            addDivider(context, content);
            addRow(context, content, R.string.delete_guide, () -> {
                dialog.dismiss();
                listener.onDeleteGuide();
            });
        }
        addDivider(context, content);
        addRow(context, content, R.string.delete_entry, () -> {
            dialog.dismiss();
            listener.onDeleteEntry();
        });

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
