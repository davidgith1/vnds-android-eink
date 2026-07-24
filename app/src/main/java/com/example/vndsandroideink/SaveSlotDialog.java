package com.example.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * The e-ink friendly slot picker for both "Save" and "Load": a flat grid of bordered cells (no
 * ripple/list animation), paginated with Prev/Next buttons at the bottom instead of one long
 * scrolling column -- SaveManager.SLOT_COUNT is large enough now that a single list would run
 * well past the screen.
 */
public final class SaveSlotDialog {

    public interface OnSlotChosen {
        void onSlotChosen(int slotIndex);
    }

    private static final int COLUMNS = 3;
    private static final int ROWS_PER_PAGE = 4;
    private static final int PER_PAGE = COLUMNS * ROWS_PER_PAGE;

    private SaveSlotDialog() {
    }

    public static void show(Context context, boolean forSave, List<SaveManager.SlotInfo> slots,
                             OnSlotChosen callback, Runnable onDismiss) {
        int totalPages = Math.max(1, (slots.size() + PER_PAGE - 1) / PER_PAGE);
        int[] page = {0};

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_background));

        TextView title = new TextView(context);
        title.setText(forSave ? R.string.save_progress : R.string.load_progress);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        int titlePad = dp(context, 16);
        title.setPadding(titlePad, titlePad, titlePad, dp(context, 8));
        content.addView(title, matchWrap());

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        int gridPad = dp(context, 12);
        grid.setPadding(gridPad, 0, gridPad, gridPad);
        content.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView pageLabel = new TextView(context);
        pageLabel.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        pageLabel.setTextSize(13);
        pageLabel.setGravity(Gravity.CENTER);
        int pageLabelPad = dp(context, 4);
        pageLabel.setPadding(0, pageLabelPad, 0, pageLabelPad);
        content.addView(pageLabel, matchWrap());

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView closeButton = flatButton(context, R.string.close, 0, 16f);
        // Bigger than the Close label -- "◂"/"▸" read as barely-there hairlines at body text size.
        TextView prevButton = flatButton(context, R.string.save_slot_prev_page, R.string.save_slot_prev_page_desc, 26f);
        TextView nextButton = flatButton(context, R.string.save_slot_next_page, R.string.save_slot_next_page_desc, 26f);
        buttonRow.addView(prevButton, weight1(context));
        buttonRow.addView(closeButton, weight1(context));
        buttonRow.addView(nextButton, weight1(context));
        content.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 56)));

        // Not the "_Fullscreen" variant: that hides the status bar while shown and un-hides it
        // again on dismiss, which forces a redraw/reflow of the whole screen right as the dialog
        // opens and closes (same reasoning as Settings/TextLog/Variables).
        EdgeToEdge.applyInsets(content);

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        closeButton.setOnClickListener(v -> dialog.dismiss());

        Runnable[] renderPage = new Runnable[1];
        renderPage[0] = () -> renderGridPage(context, grid, forSave, slots, page[0], dialog, callback);
        prevButton.setOnClickListener(v -> {
            if (page[0] > 0) {
                page[0]--;
                renderPage[0].run();
                updatePager(pageLabel, prevButton, nextButton, page[0], totalPages);
            }
        });
        nextButton.setOnClickListener(v -> {
            if (page[0] < totalPages - 1) {
                page[0]++;
                renderPage[0].run();
                updatePager(pageLabel, prevButton, nextButton, page[0], totalPages);
            }
        });

        renderPage[0].run();
        updatePager(pageLabel, prevButton, nextButton, page[0], totalPages);
        if (totalPages <= 1) {
            // Nothing to page between -- Prev/Next are already invisible (updatePager above), the
            // "Page 1/1" label itself would just be dead weight.
            pageLabel.setVisibility(View.GONE);
        }

        dialog.show();
    }

    /** @param page zero-based; Prev/Next visibility is INVISIBLE (not GONE) at either end so the
     *              Close button in the middle doesn't shift horizontally as pages change. */
    private static void updatePager(TextView pageLabel, TextView prevButton, TextView nextButton,
                                     int page, int totalPages) {
        pageLabel.setText(pageLabel.getContext().getString(R.string.save_slot_page_indicator, page + 1, totalPages));
        prevButton.setVisibility(page > 0 ? View.VISIBLE : View.INVISIBLE);
        nextButton.setVisibility(page < totalPages - 1 ? View.VISIBLE : View.INVISIBLE);
    }

    private static void renderGridPage(Context context, LinearLayout grid, boolean forSave,
                                        List<SaveManager.SlotInfo> slots, int page, Dialog dialog,
                                        OnSlotChosen callback) {
        grid.removeAllViews();
        int start = page * PER_PAGE;
        for (int r = 0; r < ROWS_PER_PAGE; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) {
                rowLp.topMargin = dp(context, 8);
            }
            for (int c = 0; c < COLUMNS; c++) {
                int index = start + r * COLUMNS + c;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) {
                    cellLp.leftMargin = dp(context, 8);
                }
                View cell = index < slots.size()
                        ? buildCell(context, forSave, slots.get(index), dialog, callback)
                        : new View(context); // pads out a short final page so earlier cells keep size
                row.addView(cell, cellLp);
            }
            grid.addView(row, rowLp);
        }
    }

    private static View buildCell(Context context, boolean forSave, SaveManager.SlotInfo slot,
                                   Dialog dialog, OnSlotChosen callback) {
        boolean interactive = forSave || slot.occupied;

        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setBackgroundResource(R.drawable.bg_bordered_row);
        int pad = dp(context, 8);
        cell.setPadding(pad, pad, pad, pad);
        if (interactive) {
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> {
                if (forSave && slot.occupied) {
                    // Confirm before clobbering an existing save -- the slot dialog itself stays
                    // open underneath so a "Cancel" just returns to picking a slot.
                    ConfirmDialog.show(context, context.getString(R.string.overwrite_save_title),
                            context.getString(R.string.overwrite_save_message, slot.index),
                            context.getString(R.string.overwrite), () -> {
                                dialog.dismiss();
                                callback.onSlotChosen(slot.index);
                            }, null);
                    return;
                }
                dialog.dismiss();
                callback.onSlotChosen(slot.index);
            });
        }

        TextView title = new TextView(context);
        title.setText(slotLabel(context, slot));
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        subtitle.setTextSize(10);
        subtitle.setGravity(Gravity.CENTER);
        // Date and time on their own line each, not one "yyyy-MM-dd HH:mm" line -- a 3-column
        // cell is too narrow for that to read as one line without wrapping mid-string.
        subtitle.setText(slot.occupied
                ? DateFormat.format("yyyy-MM-dd", slot.timestamp) + "\n" + DateFormat.format("HH:mm", slot.timestamp)
                : context.getString(R.string.save_slot_empty));
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(context, 4);
        cell.addView(subtitle, subtitleLp);

        if (slot.occupied) {
            TextView preview = new TextView(context);
            preview.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
            preview.setTextSize(11);
            preview.setMaxLines(2);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setText(slot.preview);
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            previewLp.topMargin = dp(context, 6);
            cell.addView(preview, previewLp);
        }

        return cell;
    }

    private static String slotLabel(Context context, SaveManager.SlotInfo slot) {
        return slot.index == SaveManager.SLOT_RESUME
                ? context.getString(R.string.resume_novel)
                : context.getString(R.string.save_slot_title, slot.index);
    }

    private static TextView flatButton(Context context, int textRes, int contentDescriptionRes, float textSize) {
        TextView button = new TextView(context);
        button.setText(textRes);
        if (contentDescriptionRes != 0) {
            button.setContentDescription(context.getString(contentDescriptionRes));
        }
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.choice_button_text));
        button.setBackground(ContextCompat.getDrawable(context, R.drawable.bg_choice_button));
        button.setTextSize(textSize);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
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
