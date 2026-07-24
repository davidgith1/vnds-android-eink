package com.example.vndsandroideink.nscripter;

import com.example.vndsandroideink.engine.VnEngine;

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
        String display = stripLeadingBacktick(text);
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
            show(listener, display, isContinuation);
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
}
