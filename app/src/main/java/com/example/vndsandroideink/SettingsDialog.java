package com.example.vndsandroideink;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Builds and shows the settings dialog, in the same flat, no-animation style as the save-slot
 * picker: plain tappable rows (no SwitchCompat thumb-slide, no SeekBar ripple) so opening
 * settings doesn't introduce a different visual language -- or a full e-ink refresh flash from
 * an animated control -- than the rest of the app.
 *
 * <p>Shown fullscreen with its own explicit scroll area (rather than a wrap-content AlertDialog),
 * so the row list can't be cut off on small screens or with a large text size. Paging is by
 * up/down buttons that jump a full screen at a time via {@code scrollBy} (an instant jump, not an
 * animated smooth-scroll) -- the same e-ink-friendly, no-motion paging TextLogDialog uses instead
 * of a raw drag-scroll.
 */
public final class SettingsDialog {

    public interface OnChanged {
        void onChanged();
    }

    private SettingsDialog() {
    }

    public static void show(Context context, OnChanged onChanged, Runnable onDismiss) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        // Built ahead of the e-ink row below so its toggle callback can enable/disable this one
        // live, but it's appended to the dialog later, inside the Display section.
        ToggleRow delayRow = buildToggleRow(context, R.string.instant_delays_eink,
                R.string.instant_delays_eink_summary, Prefs.isInstantDelaysInEink(context), value -> {
                    Prefs.setInstantDelaysInEink(context, value);
                    notifyChanged(onChanged);
                });

        // Holds the dialog reference for "Reset to defaults" (see below), which needs to dismiss
        // and rebuild this same dialog from scratch -- captured by a mutable array the way
        // SaveSlotDialog's own "renderPage" does, since the real Dialog doesn't exist yet at the
        // point this row's click listener is defined.
        Dialog[] dialogRef = new Dialog[1];

        // ---- Display ------------------------------------------------------------------------
        addSectionHeader(context, list, R.string.settings_section_display);
        addToggleRow(context, list, R.string.eink_mode, R.string.eink_mode_summary,
                Prefs.isEinkMode(context), value -> {
                    Prefs.setEinkMode(context, value);
                    delayRow.setEnabled(value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        delayRow.setEnabled(Prefs.isEinkMode(context));
        list.addView(delayRow.row, matchWrap());
        addDivider(context, list);
        addToggleRow(context, list, R.string.keep_screen_on, R.string.keep_screen_on_summary,
                Prefs.isKeepScreenOn(context), value -> {
                    Prefs.setKeepScreenOn(context, value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        addToggleRow(context, list, R.string.use_novel_font, R.string.use_novel_font_summary,
                Prefs.isUseNovelFont(context), value -> {
                    Prefs.setUseNovelFont(context, value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        addToggleRow(context, list, R.string.paged_library_scroll, R.string.paged_library_scroll_summary,
                Prefs.isPagedLibraryScroll(context), value -> {
                    Prefs.setPagedLibraryScroll(context, value);
                    notifyChanged(onChanged);
                });

        // ---- Text ---------------------------------------------------------------------------
        addSectionHeader(context, list, R.string.settings_section_text);
        addStepperRow(context, list, R.string.text_size, 0, Prefs.getTextSizeSp(context),
                Prefs.MIN_TEXT_SIZE_SP, Prefs.MAX_TEXT_SIZE_SP, 1, "sp",
                R.string.decrease_text_size, R.string.increase_text_size,
                value -> Prefs.setTextSizeSp(context, value), onChanged);
        addDivider(context, list);
        addTextSpeedRow(context, list, onChanged);

        // ---- Auto-advance -------------------------------------------------------------------
        addSectionHeader(context, list, R.string.settings_section_auto_advance);
        addStepperRow(context, list, R.string.auto_advance_speed, 0, Prefs.getAutoAdvanceWpm(context),
                Prefs.MIN_AUTO_ADVANCE_WPM, Prefs.MAX_AUTO_ADVANCE_WPM, 25, " wpm",
                R.string.decrease_auto_advance_speed, R.string.increase_auto_advance_speed,
                value -> Prefs.setAutoAdvanceWpm(context, value), onChanged);
        addDivider(context, list);
        addToggleRow(context, list, R.string.auto_wait_for_sfx, R.string.auto_wait_for_sfx_summary,
                Prefs.isAutoWaitForSfx(context), value -> {
                    Prefs.setAutoWaitForSfx(context, value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        addStepperRow(context, list, R.string.auto_page_pause, 0, Prefs.getAutoPagePauseSeconds(context),
                Prefs.MIN_AUTO_PAGE_PAUSE_SEC, Prefs.MAX_AUTO_PAGE_PAUSE_SEC, 1, "s",
                R.string.decrease_auto_page_pause, R.string.increase_auto_page_pause,
                value -> Prefs.setAutoPagePauseSeconds(context, value), onChanged);

        // ---- Audio ----------------------------------------------------------------------------
        addSectionHeader(context, list, R.string.settings_section_audio);
        addToggleRow(context, list, R.string.mute_audio, 0,
                Prefs.isMuteAudio(context), value -> {
                    Prefs.setMuteAudio(context, value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        addToggleRow(context, list, R.string.volume_buttons_page_turn, R.string.volume_buttons_page_turn_summary,
                Prefs.isVolumeButtonsPageTurn(context), value -> {
                    Prefs.setVolumeButtonsPageTurn(context, value);
                    notifyChanged(onChanged);
                });
        addDivider(context, list);
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        addStepperRow(context, list, R.string.system_volume, 0,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), 0, maxSystemVolume, 1, "",
                R.string.decrease_system_volume, R.string.increase_system_volume,
                value -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0), onChanged);
        addDivider(context, list);
        addStepperRow(context, list, R.string.music_volume, 0, Prefs.getMusicVolumePercent(context),
                Prefs.MIN_MUSIC_VOLUME_PCT, Prefs.MAX_MUSIC_VOLUME_PCT, 10, "%",
                R.string.decrease_music_volume, R.string.increase_music_volume,
                value -> Prefs.setMusicVolumePercent(context, value), onChanged);
        addDivider(context, list);
        addStepperRow(context, list, R.string.sfx_volume, 0, Prefs.getSfxVolumePercent(context),
                Prefs.MIN_SFX_VOLUME_PCT, Prefs.MAX_SFX_VOLUME_PCT, 10, "%",
                R.string.decrease_sfx_volume, R.string.increase_sfx_volume,
                value -> Prefs.setSfxVolumePercent(context, value), onChanged);

        addDivider(context, list);
        addResetRow(context, list, dialogRef, onChanged, onDismiss);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(context, R.color.eink_background));

        TextView title = new TextView(context);
        title.setText(R.string.settings);
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
        // again on dismiss, which forces a redraw/reflow of the whole screen (and shifts the app
        // content vertically) right as the dialog opens and closes.
        EdgeToEdge.applyInsets(content);

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialogRef[0] = dialog;
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

    private static LinearLayout.LayoutParams weight1(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        int margin = dp(context, 4);
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        return lp;
    }

    /** A toggle row that another setting can enable/disable live (only "instant delays" needs this). */
    private static final class ToggleRow {
        final LinearLayout row;
        private final TextView summary;

        ToggleRow(LinearLayout row, TextView summary) {
            this.row = row;
            this.summary = summary;
        }

        void setEnabled(boolean enabled) {
            row.setEnabled(enabled);
            row.setClickable(enabled);
            row.setFocusable(enabled);
            row.setBackground(enabled ? ContextCompat.getDrawable(row.getContext(), R.drawable.bg_library_item) : null);
            summary.setText(enabled ? R.string.instant_delays_eink_summary : R.string.instant_delays_eink_disabled_summary);
        }
    }

    private static void addToggleRow(Context context, LinearLayout list, int titleRes, int summaryRes,
                                      boolean initialValue, Consumer<Boolean> onToggle) {
        list.addView(buildToggleRow(context, titleRes, summaryRes, initialValue, onToggle).row, matchWrap());
    }

    private static ToggleRow buildToggleRow(Context context, int titleRes, int summaryRes,
                                             boolean initialValue, Consumer<Boolean> onToggle) {
        boolean[] value = {initialValue};

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        row.setPadding(padH, padV, padH, padV);
        row.setBackgroundResource(R.drawable.bg_library_item);
        row.setClickable(true);
        row.setFocusable(true);

        TextView title = new TextView(context);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        row.addView(title);

        TextView summary = new TextView(context);
        summary.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        summary.setTextSize(13);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(context, 4);
        row.addView(summary, summaryLp);
        if (summaryRes == 0) {
            summary.setVisibility(View.GONE);
        } else {
            summary.setText(summaryRes);
        }

        Runnable refreshTitle = () -> title.setText(toggleTitle(context, titleRes, value[0]));
        refreshTitle.run();

        row.setOnClickListener(v -> {
            if (!row.isEnabled()) {
                return;
            }
            value[0] = !value[0];
            refreshTitle.run();
            onToggle.accept(value[0]);
        });

        return new ToggleRow(row, summary);
    }

    private static CharSequence toggleTitle(Context context, int titleRes, boolean value) {
        return context.getString(titleRes) + ": " + context.getString(value ? R.string.on : R.string.off);
    }

    /** Shared by "Text size", "Text speed", and "Auto-advance speed": a title (with an optional
     * summary line, e.g. "Text speed"'s "no effect in e-ink mode" note) plus a -/+ stepper with a
     * live value label. */
    private static void addStepperRow(Context context, LinearLayout list, int titleRes, int summaryRes, int initialValue,
                                       int min, int max, int step, String unitSuffix,
                                       int decreaseDescRes, int increaseDescRes,
                                       IntConsumer setter, OnChanged onChanged) {
        int[] value = {initialValue};

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        row.setPadding(padH, padV, padH, padV);

        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        row.addView(title);

        if (summaryRes != 0) {
            TextView summary = new TextView(context);
            summary.setText(summaryRes);
            summary.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
            summary.setTextSize(13);
            LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            summaryLp.topMargin = dp(context, 4);
            row.addView(summary, summaryLp);
        }

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = dp(context, 8);
        row.addView(controls, controlsLp);

        TextView minus = stepButton(context, "−", decreaseDescRes);
        TextView valueText = new TextView(context);
        valueText.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        valueText.setTextSize(16);
        valueText.setGravity(Gravity.CENTER);
        TextView plus = stepButton(context, "+", increaseDescRes);

        Runnable refreshValue = () -> valueText.setText(value[0] + unitSuffix);
        refreshValue.run();

        minus.setOnClickListener(v -> {
            if (value[0] > min) {
                value[0] -= step;
                setter.accept(value[0]);
                refreshValue.run();
                notifyChanged(onChanged);
            }
        });
        plus.setOnClickListener(v -> {
            if (value[0] < max) {
                value[0] += step;
                setter.accept(value[0]);
                refreshValue.run();
                notifyChanged(onChanged);
            }
        });

        controls.addView(minus, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));
        controls.addView(valueText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(plus, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));

        list.addView(row, matchWrap());
    }

    /** "Text speed" needs one more step past {@link Prefs#MAX_TEXT_SPEED_CPS}, landing on {@link
     * Prefs#TEXT_SPEED_INSTANT} (labeled "Instant" instead of a number) -- a non-linear top step
     * {@link #addStepperRow} can't express, so this copies its visual shell with its own click
     * logic instead of trying to force the sentinel through the shared linear +/- helper. */
    private static void addTextSpeedRow(Context context, LinearLayout list, OnChanged onChanged) {
        int[] value = {Prefs.getTextSpeedCps(context)};

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        row.setPadding(padH, padV, padH, padV);

        TextView title = new TextView(context);
        title.setText(R.string.text_speed);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        row.addView(title);

        TextView summary = new TextView(context);
        summary.setText(R.string.text_speed_summary);
        summary.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        summary.setTextSize(13);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(context, 4);
        row.addView(summary, summaryLp);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = dp(context, 8);
        row.addView(controls, controlsLp);

        TextView minus = stepButton(context, "−", R.string.decrease_text_speed);
        TextView valueText = new TextView(context);
        valueText.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        valueText.setTextSize(16);
        valueText.setGravity(Gravity.CENTER);
        TextView plus = stepButton(context, "+", R.string.increase_text_speed);

        Runnable refreshValue = () -> valueText.setText(value[0] == Prefs.TEXT_SPEED_INSTANT
                ? context.getString(R.string.text_speed_instant) : (value[0] + " cps"));
        refreshValue.run();

        minus.setOnClickListener(v -> {
            if (value[0] == Prefs.TEXT_SPEED_INSTANT) {
                value[0] = Prefs.MAX_TEXT_SPEED_CPS;
            } else if (value[0] > Prefs.MIN_TEXT_SPEED_CPS) {
                value[0] -= 20;
            } else {
                return;
            }
            Prefs.setTextSpeedCps(context, value[0]);
            refreshValue.run();
            notifyChanged(onChanged);
        });
        plus.setOnClickListener(v -> {
            if (value[0] == Prefs.TEXT_SPEED_INSTANT) {
                return;
            }
            value[0] = value[0] >= Prefs.MAX_TEXT_SPEED_CPS ? Prefs.TEXT_SPEED_INSTANT : value[0] + 20;
            Prefs.setTextSpeedCps(context, value[0]);
            refreshValue.run();
            notifyChanged(onChanged);
        });

        controls.addView(minus, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));
        controls.addView(valueText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(plus, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));

        list.addView(row, matchWrap());
    }

    private static TextView stepButton(Context context, String label, int contentDescriptionRes) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setContentDescription(context.getString(contentDescriptionRes));
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.choice_button_text));
        button.setBackground(ContextCompat.getDrawable(context, R.drawable.bg_choice_button));
        button.setTextSize(18);
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

    /** A plain, non-tappable label separating one group of rows (Display/Text/Auto-advance/Audio)
     * from the next -- bold and slightly smaller than a row title, with extra top margin (except
     * the very first one) so it reads as a section break rather than another setting. */
    private static void addSectionHeader(Context context, LinearLayout list, int titleRes) {
        TextView header = new TextView(context);
        header.setText(titleRes);
        header.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        header.setTextSize(13);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        int padH = dp(context, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, list.getChildCount() == 0 ? 8 : 20);
        lp.bottomMargin = dp(context, 4);
        header.setPadding(padH, 0, padH, 0);
        list.addView(header, lp);
    }

    /** A single tappable row at the end of the list that resets every setting back to its default
     * (after confirming), then rebuilds this same dialog from scratch so every row reflects the
     * reset values -- there's no per-row "refresh to a new value" mechanism, so a full rebuild is
     * the simplest way to get every row's displayed state back in sync. */
    private static void addResetRow(Context context, LinearLayout list, Dialog[] dialogRef,
                                     OnChanged onChanged, Runnable onDismiss) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        row.setPadding(padH, padV, padH, padV);
        row.setBackgroundResource(R.drawable.bg_library_item);
        row.setClickable(true);
        row.setFocusable(true);

        TextView title = new TextView(context);
        title.setText(R.string.reset_to_defaults);
        title.setTextColor(ContextCompat.getColor(context, R.color.eink_text));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        row.addView(title);

        row.setOnClickListener(v -> ConfirmDialog.show(context,
                context.getString(R.string.reset_to_defaults_confirm_title),
                context.getString(R.string.reset_to_defaults_confirm_message),
                context.getString(R.string.reset_to_defaults), () -> {
                    Prefs.resetAllToDefaults(context);
                    notifyChanged(onChanged);
                    // Clear the dismiss listener first: dismissing here is just to rebuild, not the
                    // user actually closing the dialog, so the real onDismiss (e.g. ReaderActivity's
                    // closeOverlay) must not fire for it -- the freshly shown dialog below gets
                    // onDismiss wired normally for whenever it's genuinely closed later.
                    dialogRef[0].setOnDismissListener(null);
                    dialogRef[0].dismiss();
                    show(context, onChanged, onDismiss);
                }, null));

        list.addView(row, matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static void notifyChanged(OnChanged onChanged) {
        if (onChanged != null) {
            onChanged.onChanged();
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
