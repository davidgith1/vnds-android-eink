package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Fullscreen backlog of everything read so far: text only (no images), paginated by whole rows
 * (never cutting one in half) instead of a raw pixel scroll, with animation-free page-turn
 * buttons matching the app's e-ink-friendly, no-motion visual language.
 *
 * <p>Each page turn repeats one row from the page you just left -- marked with "▸" -- so there's
 * a visible continuity anchor between pages: paging up leaves the old first row as the new page's
 * last row; paging down leaves the old last row as the new page's first row. The very first page
 * shown when the dialog opens has no marker, since there's no previous page yet.
 */
public final class TextLogDialog {

    private TextLogDialog() {
    }

    public static void show(Context context, List<SaveManager.SavedLine> entries, Typeface font, Runnable onDismiss) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_background));

        TextView textView = new TextView(context);
        textView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        textView.setTextSize(16);
        textView.setTypeface(font); // same font (novel's own, or null for system) as the reader itself
        int pad = dp(context, 16);
        textView.setPadding(pad, pad, pad, pad);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView returnButton = logButton(context, R.string.text_log_return, 0);
        TextView upButton = logButton(context, R.string.text_log_up, R.string.text_log_up_desc);
        TextView downButton = logButton(context, R.string.text_log_down, R.string.text_log_down_desc);
        buttonRow.addView(returnButton, weight1(context));
        buttonRow.addView(upButton, weight1(context));
        buttonRow.addView(downButton, weight1(context));
        content.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 56)));

        // Not the "_Fullscreen" variant: that hides the status bar while shown and un-hides it
        // again on dismiss, which forces a redraw/reflow of the whole screen (and shifts the app
        // content vertically) right as the dialog opens and closes.
        EdgeToEdge.applyInsets(content);

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        returnButton.setOnClickListener(v -> dialog.dismiss());

        dialog.show();

        if (entries.isEmpty()) {
            return; // nothing to paginate; buttons stay inert
        }

        Pager pager = new Pager(entries);
        int[] pageStart = {0};
        int[] pageEnd = {0};
        int[] markerIndex = {-1}; // -1 = no continuity marker on the current page

        textView.post(() -> {
            int width = Math.max(1, textView.getWidth() - textView.getPaddingLeft() - textView.getPaddingRight());
            pager.measure(width, textView.getPaint());
            int budget = Math.max(1, scrollView.getHeight() - textView.getPaddingTop() - textView.getPaddingBottom());

            pageEnd[0] = entries.size() - 1;
            pageStart[0] = pager.findBackwardStart(pageEnd[0], budget);
            markerIndex[0] = -1;
            renderPage(textView, entries, pageStart[0], pageEnd[0], markerIndex[0]);
            scrollView.scrollTo(0, 0);

            upButton.setOnClickListener(v -> {
                if (pageStart[0] <= 0) {
                    return; // already showing the earliest entry
                }
                int marker = pageStart[0]; // old first row reappears, marked
                int newStart = pager.findBackwardStart(marker, budget);
                int newEnd = marker;
                if (newStart == 0) {
                    // Ran out of earlier content before filling the page: use the leftover room
                    // to keep showing what followed the marker, instead of leaving it blank.
                    newEnd = pager.growForward(marker, pager.sumHeights(newStart, marker), budget);
                }
                pageStart[0] = newStart;
                pageEnd[0] = newEnd;
                markerIndex[0] = marker;
                renderPage(textView, entries, pageStart[0], pageEnd[0], markerIndex[0]);
                scrollView.scrollTo(0, 0);
            });
            downButton.setOnClickListener(v -> {
                if (pageEnd[0] >= entries.size() - 1) {
                    return; // already showing the most recent entry
                }
                int marker = pageEnd[0]; // old last row reappears, marked
                int newEnd = pager.findForwardEnd(marker, budget);
                int newStart = marker;
                if (newEnd == entries.size() - 1) {
                    // Ran out of later content before filling the page: use the leftover room to
                    // keep showing what preceded the marker, instead of leaving it blank.
                    newStart = pager.growBackward(marker, pager.sumHeights(marker, newEnd), budget);
                }
                pageStart[0] = newStart;
                pageEnd[0] = newEnd;
                markerIndex[0] = marker;
                renderPage(textView, entries, pageStart[0], pageEnd[0], markerIndex[0]);
                scrollView.scrollTo(0, 0);
            });
        });
    }

    private static void renderPage(TextView textView, List<SaveManager.SavedLine> entries,
                                    int start, int end, int markerIndex) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = start; i <= end; i++) {
            SaveManager.SavedLine line = entries.get(i);
            int spanStart = sb.length();
            if (i == markerIndex) {
                sb.append("▸ ");
            }
            sb.append(line.text);
            if (line.bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), spanStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < end) {
                sb.append("\n");
            }
        }
        textView.setText(sb);
    }

    /** Precomputes each row's rendered height once, then greedily fits ranges within a pixel budget. */
    private static final class Pager {
        private final List<SaveManager.SavedLine> entries;
        private float[] heights;

        Pager(List<SaveManager.SavedLine> entries) {
            this.entries = entries;
        }

        void measure(int width, TextPaint paint) {
            heights = new float[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                String text = entries.get(i).text;
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).build();
                heights[i] = layout.getHeight();
            }
        }

        /** Last index (inclusive) starting from start that still fits budgetPx; always includes at least start. */
        int findForwardEnd(int start, int budgetPx) {
            float used = 0f;
            int end = start;
            while (end < entries.size()) {
                float h = heights[end];
                if (used + h > budgetPx && end > start) {
                    break;
                }
                used += h;
                end++;
            }
            return end - 1;
        }

        /** First index (inclusive) ending at end that still fits budgetPx; always includes at least end. */
        int findBackwardStart(int end, int budgetPx) {
            float used = 0f;
            int start = end;
            while (start >= 0) {
                float h = heights[start];
                if (used + h > budgetPx && start < end) {
                    break;
                }
                used += h;
                start--;
            }
            return start + 1;
        }

        float sumHeights(int start, int end) {
            float sum = 0f;
            for (int i = start; i <= end; i++) {
                sum += heights[i];
            }
            return sum;
        }

        /** Extends forward from `from` (already counted in usedPx) to use up any budget left over. */
        int growForward(int from, float usedPx, int budgetPx) {
            float used = usedPx;
            int end = from;
            while (end + 1 < entries.size()) {
                float h = heights[end + 1];
                if (used + h > budgetPx) {
                    break;
                }
                used += h;
                end++;
            }
            return end;
        }

        /** Extends backward from `from` (already counted in usedPx) to use up any budget left over. */
        int growBackward(int from, float usedPx, int budgetPx) {
            float used = usedPx;
            int start = from;
            while (start - 1 >= 0) {
                float h = heights[start - 1];
                if (used + h > budgetPx) {
                    break;
                }
                used += h;
                start--;
            }
            return start;
        }
    }

    private static TextView logButton(Context context, int textRes, int contentDescriptionRes) {
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

    private static LinearLayout.LayoutParams weight1(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        int margin = dp(context, 4);
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        return lp;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
