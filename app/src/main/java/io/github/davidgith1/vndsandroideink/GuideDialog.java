package io.github.davidgith1.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fullscreen, e-ink-friendly tree view over an imported completion guide (see {@link
 * GuideManager}): a collapsible section per route, each listing its choices and endings as
 * checkable rows. Checked state is written straight through to {@link GuideManager} on every
 * tap -- no separate "save" step, same as {@link VariablesDialog}'s immediate-commit editing.
 * Routes start collapsed: a guide can list hundreds of individual choices across many routes, and
 * showing them all at once would be an unusable wall of text. Which sections were left expanded,
 * and the scroll position, are remembered per VN (see {@link GuideManager#getExpandedSections}/
 * {@link GuideManager#getScrollPosition}) and restored the next time the guide is opened, and
 * persisted again on close.
 */
public final class GuideDialog {

    private GuideDialog() {
    }

    public static void show(Context context, String vnKey, String fallbackTitle, Runnable onDismiss) {
        GuideManager.Guide guide = GuideManager.loadGuide(context, vnKey);
        // Mutated in place by each section's expand/collapse toggle below, so it always reflects
        // the live tree shape when the dialog is dismissed -- no separate "gather state" pass.
        Set<String> expandedKeys = new HashSet<>(GuideManager.getExpandedSections(context, vnKey));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_background));

        TextView title = new TextView(context);
        title.setText(guide != null && guide.gameName != null && !guide.gameName.isEmpty() ? guide.gameName : fallbackTitle);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        int titlePad = dp(context, 16);
        title.setPadding(titlePad, titlePad, titlePad, dp(context, 8));
        content.addView(title, matchWrap());

        if (guide != null && guide.metaNote != null) {
            content.addView(metaCaption(context, guide.metaNote), matchWrap());
        }
        if (guide != null && guide.orderNote != null) {
            content.addView(metaCaption(context, guide.orderNote), matchWrap());
        }

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int listPad = dp(context, 12);
        list.setPadding(listPad, 0, listPad, listPad);

        if (guide == null || guide.routes.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.guide_empty);
            empty.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
            empty.setTextSize(14);
            list.addView(empty, matchWrap());
        } else {
            for (GuideManager.Route route : guide.routes) {
                list.addView(buildRouteSection(context, vnKey, route, expandedKeys), matchWrap());
            }
        }
        if (guide != null && !guide.saveSlots.isEmpty()) {
            list.addView(buildSaveSlotsSection(context, vnKey, guide.saveSlots, expandedKeys), matchWrap());
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView closeButton = dialogButton(context, R.string.close, 0, 16f);
        TextView upButton = dialogButton(context, R.string.settings_scroll_up, R.string.settings_scroll_up_desc, 26f);
        TextView downButton = dialogButton(context, R.string.settings_scroll_down, R.string.settings_scroll_down_desc, 26f);
        buttonRow.addView(upButton, weight1(context));
        buttonRow.addView(closeButton, weight1(context));
        buttonRow.addView(downButton, weight1(context));
        content.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 56)));

        // Not the "_Fullscreen" variant: that hides the status bar while shown and un-hides it
        // again on dismiss, which forces a redraw/reflow of the whole screen right as the dialog
        // opens and closes (same reasoning as Settings/TextLog/Variables).
        EdgeToEdge.applyInsets(content);

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> {
            GuideManager.setExpandedSections(context, vnKey, expandedKeys);
            GuideManager.setScrollPosition(context, vnKey, scrollView.getScrollY());
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        // scrollBy (not smoothScrollBy) jumps instantly, with no animated glide for e-ink to
        // have to redraw through.
        upButton.setOnClickListener(v -> scrollView.scrollBy(0, -scrollView.getHeight()));
        downButton.setOnClickListener(v -> scrollView.scrollBy(0, scrollView.getHeight()));

        dialog.show();
        // Deferred with post: the ScrollView has no measured extent to scroll within until after
        // this first layout pass.
        int savedScrollY = GuideManager.getScrollPosition(context, vnKey);
        if (savedScrollY > 0) {
            scrollView.post(() -> scrollView.scrollTo(0, savedScrollY));
        }
    }

    private static View buildRouteSection(Context context, String vnKey, GuideManager.Route route, Set<String> expandedKeys) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = matchWrap();
        sectionLp.topMargin = dp(context, 8);
        section.setLayoutParams(sectionLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundResource(R.drawable.bg_bordered_row);
        header.setClickable(true);
        header.setFocusable(true);
        int padH = dp(context, 12);
        int padV = dp(context, 10);
        header.setPadding(padH, padV, padH, padV);

        TextView headerTitle = new TextView(context);
        headerTitle.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        headerTitle.setTextSize(16);
        headerTitle.setTypeface(headerTitle.getTypeface(), Typeface.BOLD);
        header.addView(headerTitle);

        if (route.category != null && !route.category.isEmpty()) {
            header.addView(smallCaption(context, route.category, 0));
        }

        section.addView(header, matchWrap());

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int bodyPad = dp(context, 12);
        body.setPadding(bodyPad, dp(context, 8), bodyPad, dp(context, 4));
        boolean wasExpanded = expandedKeys.contains(route.id);
        body.setVisibility(wasExpanded ? View.VISIBLE : View.GONE);

        for (int i = 0; i < route.infoLines.size(); i++) {
            body.addView(smallCaption(context, route.infoLines.get(i), i == 0 ? 0 : dp(context, 4)));
        }

        for (GuideManager.Checkpoint checkpoint : route.checkpoints) {
            if (checkpoint.label != null) {
                body.addView(sectionCaption(context, checkpoint.label));
            }
            if (checkpoint.createsSaveId != null) {
                body.addView(smallCaption(context, "Create save here -- Slot " + checkpoint.createsSaveId, dp(context, 4)));
            }
            for (GuideManager.Choice choice : checkpoint.choices) {
                body.addView(buildCheckRow(context, vnKey, choice.key, choice.label, choice.detail, null));
            }
            if (checkpoint.info != null) {
                body.addView(smallCaption(context, checkpoint.info, dp(context, 4)));
            }
        }

        int totalEndings = route.endings.size();
        Runnable[] refreshHeader = new Runnable[1];
        boolean[] expanded = {wasExpanded};
        refreshHeader[0] = () -> {
            int done = 0;
            for (GuideManager.Ending ending : route.endings) {
                if (GuideManager.isChecked(context, vnKey, ending.key)) {
                    done++;
                }
            }
            String arrow = expanded[0] ? "▾ " : "▸ "; // ▾ / ▸
            String progress = totalEndings > 0 ? "  (" + done + "/" + totalEndings + ")" : "";
            headerTitle.setText(arrow + route.name + progress);
        };
        refreshHeader[0].run();

        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            if (expanded[0]) {
                expandedKeys.add(route.id);
            } else {
                expandedKeys.remove(route.id);
            }
            refreshHeader[0].run();
        });

        if (!route.endings.isEmpty()) {
            body.addView(sectionCaption(context, context.getString(R.string.guide_endings_label)));
            for (GuideManager.Ending ending : route.endings) {
                body.addView(buildCheckRow(context, vnKey, ending.key, ending.label, ending.detail, refreshHeader[0]));
            }
        }

        section.addView(body, matchWrap());
        return section;
    }

    /** A flat, non-collapsible checklist of the guide's "saveSlots" reference (Never7-style
     * guides use numbered save slots as waypoints between routes) -- same checkbox-row mechanics
     * as routes' choices/endings, just without any nested sub-grouping. */
    private static View buildSaveSlotsSection(Context context, String vnKey, List<GuideManager.SaveSlotRef> saveSlots,
                                               Set<String> expandedKeys) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = matchWrap();
        sectionLp.topMargin = dp(context, 8);
        section.setLayoutParams(sectionLp);

        LinearLayout header = new LinearLayout(context);
        header.setBackgroundResource(R.drawable.bg_bordered_row);
        header.setClickable(true);
        header.setFocusable(true);
        int padH = dp(context, 12);
        int padV = dp(context, 10);
        header.setPadding(padH, padV, padH, padV);

        TextView headerTitle = new TextView(context);
        headerTitle.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        headerTitle.setTextSize(16);
        headerTitle.setTypeface(headerTitle.getTypeface(), Typeface.BOLD);
        header.addView(headerTitle);
        section.addView(header, matchWrap());

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int bodyPad = dp(context, 12);
        body.setPadding(bodyPad, dp(context, 8), bodyPad, dp(context, 4));
        boolean wasExpanded = expandedKeys.contains(GuideManager.SAVE_SLOTS_SECTION_KEY);
        body.setVisibility(wasExpanded ? View.VISIBLE : View.GONE);

        for (GuideManager.SaveSlotRef slot : saveSlots) {
            body.addView(buildCheckRow(context, vnKey, slot.key, slot.label, null, null));
        }

        boolean[] expanded = {wasExpanded};
        headerTitle.setText((expanded[0] ? "▾ " : "▸ ") + context.getString(R.string.guide_save_slots_label));
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            if (expanded[0]) {
                expandedKeys.add(GuideManager.SAVE_SLOTS_SECTION_KEY);
            } else {
                expandedKeys.remove(GuideManager.SAVE_SLOTS_SECTION_KEY);
            }
            headerTitle.setText((expanded[0] ? "▾ " : "▸ ") + context.getString(R.string.guide_save_slots_label));
        });

        section.addView(body, matchWrap());
        return section;
    }

    /** @param detail an optional smaller secondary line under the label (e.g. an ending's
     *                "loadSave"/pivotal-choice steps, or a choice's "saveHereFor"/note); null if
     *                the item has none.
     * @param onToggled extra hook run after the checked-state is persisted (endings use this to
     *                  refresh their route header's completion count); null otherwise. */
    private static View buildCheckRow(Context context, String vnKey, String key, String label, String detail, Runnable onToggled) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_library_item);
        row.setClickable(true);
        row.setFocusable(true);
        int padH = dp(context, 8);
        int padV = dp(context, 8);
        row.setPadding(padH, padV, padH, padV);

        TextView labelView = new TextView(context);
        labelView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        labelView.setTextSize(14);
        row.addView(labelView);

        if (detail != null) {
            TextView detailView = new TextView(context);
            detailView.setText(detail);
            detailView.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
            detailView.setTextSize(11);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            detailLp.topMargin = dp(context, 3);
            detailLp.leftMargin = dp(context, 14); // indent past the ☐/☑ glyph
            row.addView(detailView, detailLp);
        }

        boolean[] checked = {GuideManager.isChecked(context, vnKey, key)};
        Runnable refresh = () -> labelView.setText((checked[0] ? "☑ " : "☐ ") + label); // ☑ / ☐
        refresh.run();

        row.setOnClickListener(v -> {
            checked[0] = !checked[0];
            GuideManager.setChecked(context, vnKey, key, checked[0]);
            refresh.run();
            if (onToggled != null) {
                onToggled.run();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private static View sectionCaption(Context context, String text) {
        TextView caption = new TextView(context);
        caption.setText(text);
        caption.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        caption.setTextSize(12);
        caption.setTypeface(caption.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        caption.setLayoutParams(lp);
        return caption;
    }

    /** "About this guide" line under the title (source/rating/generated-note/recommended-order
     * note) -- same horizontal padding as the title itself, since it sits outside the indented
     * route list. */
    private static View metaCaption(Context context, String text) {
        TextView caption = new TextView(context);
        caption.setText(text);
        caption.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        caption.setTextSize(12);
        int padH = dp(context, 16);
        caption.setPadding(padH, 0, padH, dp(context, 6));
        return caption;
    }

    private static View smallCaption(Context context, String text, int topMarginPx) {
        TextView caption = new TextView(context);
        caption.setText(text);
        caption.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        caption.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        caption.setLayoutParams(lp);
        return caption;
    }

    private static TextView dialogButton(Context context, int textRes, int contentDescriptionRes, float textSize) {
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
