package io.github.davidgith1.vndsandroideink.engine;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Common surface both script interpreters (VNDS's {@code vnds.ScriptEngine} and NScripter's
 * {@code nscripter.NsScriptEngine}) implement, so {@code ReaderActivity} can host either one
 * polymorphically instead of forking into a parallel Activity per format.
 */
public interface VnEngine {

    enum State {
        RUNNING, WAITING_TAP, WAITING_CHOICE, WAITING_DELAY, FINISHED
    }

    /** How a sprite image's transparency should be derived -- NScripter tags this per file-load
     * call (see {@code NsCommandDispatcher}'s "ld" handler); VNDS never does, so it's always
     * {@link #OPAQUE}. Matches real ONScripter behavior:
     * untagged is {@link #TOPLEFT_KEY} (real NScripter's own
     * default), not {@link #OPAQUE}. */
    enum SpriteTransparency {
        /** No transparency: show the decoded image as-is. */
        OPAQUE,
        /** ":a;" tag: {@code imageFile} is double-width, real art in the left half and a
         * grayscale alpha mask (white = transparent, black = opaque) in the right half, which
         * the host must composite into one real ARGB image before displaying it. */
        ALPHA_MASK,
        /** No tag (NScripter's own default for a freshly-loaded sprite) or an explicit ":l;" tag:
         * the color of the image's own top-left corner pixel is the "transparent" color-key,
         * applied everywhere it appears in the image. */
        TOPLEFT_KEY
    }

    /** Callbacks the engine drives as it executes commands. Invoked on the caller's thread. */
    interface Listener {
        /** Sentinels {@link #onSprite}'s x/y may carry instead of a literal pixel offset --
         * NScripter's "ld" left/center/right stand positions have no fixed pixel coordinate of
         * their own (in real ONScripter, they're resolved against the
         * decoded image's own size, which this plain-Java engine layer has no access to), so the
         * host resolves the real offset once it has decoded the image and knows the scene's
         * virtual canvas size. Never produced by VNDS, which always passes a literal x/y. */
        int AUTO_POSITION_LEFT = Integer.MIN_VALUE;
        int AUTO_POSITION_CENTER = Integer.MIN_VALUE + 1;
        int AUTO_POSITION_RIGHT = Integer.MIN_VALUE + 2;
        int AUTO_POSITION_BOTTOM = Integer.MIN_VALUE + 3;

        void onSpeaker(String name);
        void onTextLine(String line);
        /** Appends {@code moreText} to the end of the most recently shown line instead of starting
         * a new one -- NScripter's mid-line "@" pause (see {@code NsDialogue}) resumes with the
         * rest of the very same original line, which should read as one continuous line/sentence,
         * not a line break. VNDS never calls this -- its dialogue has no such mid-line marker. */
        void onTextAppend(String moreText);
        void onTextClear();
        /** @param fadeFrames the background change's optional fade-length argument, in frames at
         *                    60fps -- the host decides whether/how to actually honor it (e.g. no
         *                    fade at all in e-ink mode).
         * @param transparency see {@link SpriteTransparency}; a background can be alpha-mask-
         *                    tagged the same way a sprite can (e.g. a real ONScripter title
         *                    screen's message-box/title-text art loaded via "bg" instead of
         *                    "ld"/"lsp"), so this must be honored the same way {@link #onSprite}
         *                    already does, not always treated as {@link SpriteTransparency#OPAQUE}.
         * @param alphaMaskCells see {@link #onSprite}'s own doc; only meaningful when {@code
         *                    transparency} is {@link SpriteTransparency#ALPHA_MASK}, ignored
         *                    otherwise (pass 1). */
        void onBackground(File imageFile, int fadeFrames, SpriteTransparency transparency, int alphaMaskCells);
        /** @param layer a format-specific sprite/layer identity: {@code -1} means "always append,
         *               no identity" (VNDS's setimg -- foreground layers are only ever cleared by
         *               bgload), a value {@code >= 0} means "replace this numbered layer in place"
         *               (NScripter's numbered sprite/layer model).
         * @param x a literal pixel offset, or one of the {@code AUTO_POSITION_*} sentinels above.
         * @param y a literal pixel offset, or {@code AUTO_POSITION_BOTTOM}.
         * @param transparency see {@link SpriteTransparency}.
         * @param alphaMaskCells real ONScripter's multi-cell sprite-sheet tag (e.g. ":a/2,0,3;file",
         *                    matching real ONScripter's own cell-splitting math): an alpha-mask
         *                    image can be split into this
         *                    many equal-width animation cells, EACH itself a [color|mask] pair, laid
         *                    out left to right -- e.g. a real game's title-screen text
         *                    ("lsp 0,\":a/2,0,3;May/System/Title_Text.jpg\",0,0", decoded width
         *                    1280) is 2 cells of 320+320 (color+mask) each, NOT one plain 640+640
         *                    color/mask pair -- treating it as the latter corrupts the alpha entirely
         *                    (confirmed: it produces a solid opaque block over roughly half the
         *                    image). This host has no sprite-sheet animation of its own, so only
         *                    cell 0 (the first) is ever composited/shown -- see {@code
         *                    ReaderActivity#compositeSideBySideAlphaMask}. 1 for a plain,
         *                    non-cell/untagged image (the common case). Only meaningful when {@code
         *                    transparency} is {@link SpriteTransparency#ALPHA_MASK}, ignored
         *                    otherwise (pass 1). */
        void onSprite(int layer, int x, int y, File imageFile, SpriteTransparency transparency, int alphaMaskCells);
        /** Clears one numbered sprite layer (NScripter only; VNDS never calls this -- its sprites
         *  are only ever cleared in bulk, via {@link #onBackground}). */
        void onSpriteCleared(int layer);
        void onSound(File soundFileOrNull, int times);
        void onMusic(File musicFileOrNull);
        void onChoices(List<String> options);
        /** Same menu as {@link #onChoices(List)}, plus -- parallel to {@code options}, same size,
         * individual entries possibly {@code null} -- the button-sprite image (if any) NScripter's
         * "spbtn"/"exbtn" idiom loaded for that option (see {@code NsCommandDispatcher}'s "lsp"/
         * "spbtn" handlers), so a host that wants to render the real button graphic rather than a
         * plain text fallback can. Defaults to forwarding to the text-only overload for hosts (and
         * VNDS, which never has button images) that don't care. */
        default void onChoices(List<String> options, List<File> images) {
            onChoices(options);
        }
        /** Only invoked when the host has real delays enabled; see {@link #setDelaysEnabled}. */
        void onDelay(int frames);
        /** Invoked whenever a persistent global variable changes, so the host can persist it. */
        void onGlobalsChanged(Map<String, String> globals);
        void onFinished();
        /** NScripter's "end" command (see {@code NsCommandDispatcher}) -- unlike running off the
         * bottom of a script (which calls {@link #onFinished} and shows a "The End" screen), "end"
         * is an explicit exit invoked from a menu/title screen (e.g. its own "Quit" option), so the
         * host should just return straight to its library, the same as its own menu-driven "Return
         * to Library" action. Never produced by VNDS, which has no such command. */
        void onExitToLibrary();
        /** NScripter's "systemcall load" (see {@code NsCommandDispatcher}) -- a script-driven
         * request to open the host's own save-slot Load UI, the same one reachable from the
         * reader's own menu. Never produced by VNDS, which has no such command. */
        void onLoadMenuRequested();
    }

    /**
     * Controls whether a scripted pause actually pauses execution. The host decides this based
     * on its own e-ink/animation preferences -- the engine itself has no opinion on timing.
     */
    void setDelaysEnabled(boolean enabled);

    State getState();

    String getCurrentFile();

    int getPc();

    Map<String, String> getVariablesSnapshot();

    /** Persistent (global) variables, snapshotted the same way {@link #getVariablesSnapshot}
     * covers local ones -- for the host's own "Variables" viewer/editor. */
    Map<String, String> getGlobalsSnapshot();

    /** Directly sets a local variable's value, bypassing whatever modifier syntax a script's own
     * assignment command would use -- for host-driven edits (a debug/inspector UI), not script
     * execution. */
    void setVariable(String name, String value);

    /** Directly sets a persistent (global) variable's value, the same way {@link #setVariable}
     * does for local ones, and notifies the host to persist it. */
    void setGlobal(String name, String value);

    /**
     * Peeks (without consuming) whether resuming from the current WAITING_TAP line will
     * immediately wipe the text box for a new page. Lets the host add extra Auto-advance pause
     * before a page the player is currently reading disappears.
     */
    boolean isPageEndPending();

    /** Starts execution from the top of the script. */
    void start();

    /**
     * Resumes execution at a previously saved position, without re-running any commands. The
     * caller is responsible for restoring the on-screen visuals (background, sprites, text box
     * contents) to match, since jumping straight to {@code savedPc} skips the commands that
     * originally produced them.
     */
    void restoreState(String file, int savedPc, Map<String, String> vars);

    void resumeFromTap();

    /** Resumes after the host's scheduled delay has elapsed; see {@link Listener#onDelay}. */
    void resumeFromDelay();

    /**
     * Lets the host force a WAITING_DELAY-style pause outside of a scripted delay command --
     * used to hold a text clear (and the engine) until a voice-synced sound effect finishes
     * playing. Resume with {@link #resumeFromDelay()}. Must only be called synchronously from
     * within a {@code Listener} callback, while the engine's run loop is still on the call stack.
     */
    void pauseForHostTiming();

    void choose(int zeroBasedIndex);

    /**
     * Redisplays whatever "select"/"btnwait" choice menu was most recently shown, restoring the
     * engine to WAITING_CHOICE exactly as it was before that choice was picked -- for a host action
     * taken from inside a choice's target (e.g. NScripter's "systemcall load" finding no save data
     * to load) that needs to undo having left that menu, rather than leaving the engine stuck at
     * whatever paused state the failed action left it in. Re-invokes {@link Listener#onChoices}
     * when it does.
     * @return true if a menu was restored; false if there was nothing to restore (no choice menu
     * has been shown yet, or this format -- VNDS -- never produces one).
     */
    boolean reshowLastChoiceMenu();
}
