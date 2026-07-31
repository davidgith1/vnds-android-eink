package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.util.regex.Pattern;

/**
 * Handles a bare (non-command) script line as dialogue text. NScripter has no "text" keyword --
 * any line the dispatcher doesn't recognize as a command is printed verbatim, with '\'/'@'
 * acting as inline pause markers wherever they occur in the text (e.g. "...good quality.@
 * Nevertheless, it's still small.@" pauses once mid-sentence and
 * again at the end), not just at the very end of the line: '\' waits for a tap and starts a fresh
 * page on resume; '@' waits for a tap but keeps the page, then shows whatever text followed it
 * (see {@link NsExecState#pendingDialogueRemainder}); reaching the end of the text with no marker
 * continues immediately without waiting at all.
 */
final class NsDialogue {

    private NsDialogue() {
    }

    /** @param isContinuation true when {@code text} is a stored {@link
     * NsExecState#pendingDialogueRemainder} being resumed mid-line rather than a fresh script
     * line -- routes the shown segment through {@link VnEngine.Listener#onTextAppend} instead of
     * {@link VnEngine.Listener#onTextLine} so it reads as the same line/sentence continuing, not a
     * line break. */
    static void handle(NsExecState state, String text, VnEngine.Listener listener, boolean isContinuation) {
        String display = resolveIfBareStringVariable(stripInlineColorCodes(stripLeadingBacktick(text)), state);
        int slash = display.indexOf('\\');
        int at = display.indexOf('@');
        int marker;
        char markerChar;
        if (slash >= 0 && (at < 0 || slash < at)) {
            marker = slash;
            markerChar = '\\';
        } else if (at >= 0) {
            marker = at;
            markerChar = '@';
        } else {
            if (!display.isEmpty()) {
                // A line consisting ENTIRELY of a stripped inline color code (e.g. a bare
                // "#ffffff" line -- see stripInlineColorCodes's own doc) produces no visible
                // characters in real ONScripter either; skip emitting a spurious blank text line
                // for it rather than showing an empty page/append.
                show(listener, display, isContinuation);
            }
            state.pendingDialogueRemainder = null;
            // Auto-continue: no wait, run() keeps going to the next line.
            return;
        }

        show(listener, display.substring(0, marker), isContinuation);
        state.runState = VnEngine.State.WAITING_TAP;
        String rest = display.substring(marker + 1);
        if (markerChar == '\\') {
            state.pendingPageClearOnResume = true;
            state.pendingDialogueRemainder = null; // '\' always ends this text block outright
        } else if (rest.isEmpty()) {
            state.pendingDialogueRemainder = null; // trailing '@': nothing left on this page
        } else {
            state.pendingDialogueRemainder = rest; // more text follows on the SAME page after the tap
        }
    }

    private static void show(VnEngine.Listener listener, String segment, boolean isContinuation) {
        if (isContinuation) {
            listener.onTextAppend(segment);
        } else {
            listener.onTextLine(segment);
        }
    }

    /** A line-leading '`' is a real NScripter convention (real scripts commonly use it to prefix
     * nearly every dialogue and select/rmenu option string) -- a parser-only tag,
     * never meant to actually appear on screen. */
    static String stripLeadingBacktick(String text) {
        return text.startsWith("`") ? text.substring(1) : text;
    }

    /** A bare "#RRGGBB" (exactly 6 hex digits) anywhere in dialogue text is a real, native
     * NScripter inline control code, not literal text -- real ONScripter-EN's own text-output loop
     * (see ONScripterLabel_text.cpp's "ch == '#'" branch) consumes it silently to change the
     * current font color for whatever follows, producing zero visible characters of its own; only a
     * "#" NOT followed by 6 valid hex digits is left as an ordinary literal character. A real,
     * observed case this matters for (my_black_cat's own opening line): a whole line consisting of
     * nothing but "#ffffff" (set text color to white, common right after a screen-clearing "bg
     * black" scene transition) -- shown as literal "#ffffff" dialogue before this was recognized,
     * since nothing else in this pipeline treated '#' as special. This host doesn't track or render
     * per-run text color at all (plain grayscale/e-ink text, matching this project's own
     * no-rich-text-color design elsewhere), so the code is simply dropped rather than acted on --
     * the same tolerance real backgrounds/sprites get for an untracked color tag. */
    private static final Pattern INLINE_COLOR_CODE = Pattern.compile("#[0-9a-fA-F]{6}");

    private static String stripInlineColorCodes(String text) {
        return INLINE_COLOR_CODE.matcher(text).replaceAll("");
    }

    /** A dialogue "line" consisting ENTIRELY of a bare "$name"/"$3" string-variable reference is a
     * real, common NScripter authoring idiom for a per-character name tag: the script sets the
     * variable once ("mov $1,\"Ryuuji\""), then that same bare "$1" is used as its own whole line
     * right before each of that character's dialogue lines (a real, observed case:
     * plain_song_christmas_special's own script) -- combined with a "setwindow" that carves out a
     * separate name-box region above the main text, this is how these scripts render a name tag at
     * all, since real NScripter's script format has no dedicated name-box command of its own. Real
     * ONScripter-EN's own string-expression grammar (see ScriptHandler::parseStr's "$" branch)
     * evaluates a bare "$N" to that variable's stored string value wherever a string expression is
     * read; this reimplementation's dialogue-line reader previously never applied that evaluation at
     * all (unlike command ARGUMENTS, which already go through {@link NsExpr#string}), so such a line
     * showed the literal, unevaluated "$1" text instead of the character's actual name. Deliberately
     * narrow -- only a line that's NOTHING BUT the reference, not "$1" embedded mid-sentence (a much
     * rarer real pattern this doesn't attempt, to avoid misreading an incidental literal '$' in
     * ordinary prose, e.g. a price mentioned in dialogue).
     *
     * <p>Shape alone isn't enough, though: a whole line that's LITERALLY a price or code (e.g.
     * "$5") has the exact same "$" + identifier shape as a real name-tag reference, but was never
     * actually assigned via "mov"/"stralias" the way a real name-tag variable always is beforehand
     * -- only substitute when the referenced slot has actually been assigned (see {@link
     * #resolveIfBareStringVariable}), so an unset slot's name is left as the literal text it
     * obviously is instead of silently vanishing (or showing an unrelated slot's leftover value). */
    private static final Pattern BARE_STRING_VAR = Pattern.compile("\\$[A-Za-z0-9_]+");

    private static String resolveIfBareStringVariable(String text, NsExecState state) {
        if (!BARE_STRING_VAR.matcher(text).matches()) {
            return text;
        }
        NsArg arg = new NsArg(NsArg.Kind.STR_VAR_EXPR, text, text.substring(1));
        if (!state.strVars.containsKey(NsExpr.strVarIndex(arg, state))) {
            return text; // never actually assigned: a literal line that just looks like one
        }
        return NsExpr.string(arg, state);
    }
}
