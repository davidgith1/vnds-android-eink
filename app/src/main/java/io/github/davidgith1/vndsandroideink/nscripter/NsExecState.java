package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All mutable interpreter state for one NScripter playthrough: the parsed script, the line
 * pointer, variable storage, the gosub/return call stack, and small bits of pending-action
 * bookkeeping that command handlers ({@link NsCommandHandler}) read/write directly. Deliberately
 * a plain data holder -- {@link NsScriptEngine} owns the run loop and the {@link VnEngine}
 * surface, {@link NsCommandDispatcher} owns command behavior; this class just holds what both
 * need to share.
 */
public final class NsExecState {

    public List<String> lines = new ArrayList<>();
    public Map<String, Integer> labelIndex = new HashMap<>();
    public int pc = 0;

    /** Numeric variables ($1-style is string; this is the %N family), sparse: an absent key reads
     * as 0, same default-to-zero convention vnds.ScriptEngine uses for unset variables. */
    public final Map<Integer, Long> numVars = new HashMap<>();
    /** String variables (the $N family), sparse: an absent key reads as "". */
    public final Map<Integer, String> strVars = new HashMap<>();
    /** numalias-declared names for numeric variable slots, e.g. "numalias money,3" lets scripts
     * write "%money" instead of "%3" -- a naming convenience, not a separate constant namespace. */
    public final Map<String, Integer> numAliases = new HashMap<>();
    /** stralias's equivalent for string variable slots. */
    public final Map<String, Integer> strAliases = new HashMap<>();
    /** Bareword constants declared via "stralias name,\"literal value\"" (e.g. {@code stralias
     * bgcoffee,"data\bg_coffee.png"} then later {@code bg bgcoffee,10}) -- ONScripter-EN's
     * dual-purpose "stralias": a literal-string 2nd
     * argument defines a bareword text constant instead of a variable-slot alias (see {@link
     * #strAliases}), consulted wherever a later command's own bareword argument is resolved as a
     * file path. Distinct from {@link #strAliases}: those are looked up via a {@code $}-prefixed
     * variable read, these are used bare, directly in place of a quoted string. */
    public final Map<String, String> barewordConstants = new HashMap<>();

    /** Return addresses for gosub/return, most recent call on top. */
    public final Deque<Integer> callStack = new ArrayDeque<>();

    /** Subroutine names registered by "defsub" -- bareword pseudo-commands (e.g. "change_b") that
     * should be dispatched like "gosub *name" with the call's own comma-separated args threaded
     * through to that subroutine's own "getparam", rather than being treated as an unrecognized
     * command (silently skipped) or misread as dialogue. */
    public final java.util.Set<String> definedSubs = new java.util.HashSet<>();

    /** Args from the most recent defsub-pseudo-command call, queued for "getparam" to drain
     * positionally (first arg -> first getparam variable, etc.). Cleared as it's consumed; a
     * subroutine that never calls "getparam" just leaves it to be overwritten by the next call. */
    public List<NsArg> pendingSubParams = new ArrayList<>();

    public VnEngine.State runState = VnEngine.State.FINISHED;

    /** Whether "wait" actually pauses; mirrors vnds.ScriptEngine's own delaysEnabled -- the host
     * decides this via {@link NsScriptEngine#setDelaysEnabled}. Off by default. */
    public boolean delaysEnabled = false;

    /** Set when the just-shown dialogue line ended with a pagewait marker ("\"): the next
     * resumeFromTap must clear the text box before resuming execution -- see {@link
     * NsScriptEngine#resumeFromTap()}. */
    public boolean pendingPageClearOnResume = false;

    /** Text still waiting to be shown from the *current* script line, when a mid-line "@" pause
     * (see {@link NsDialogue}) leaves more of the line to display after the next tap -- as opposed
     * to a trailing "@"/"\\", which has nothing left and so leaves this {@code null}. Null means
     * the next {@link NsScriptEngine#resumeFromTap()} should simply advance to the next script
     * line, same as always. */
    public String pendingDialogueRemainder = null;

    /** The label each currently-shown choice jumps to, set by the "select" handler and consulted
     * by {@link NsScriptEngine#choose(int)} -- NScripter's select jumps directly per option,
     * unlike VNDS's choice (which just sets a "selected" variable and falls through). */
    public List<String> pendingChoiceLabels = new ArrayList<>();

    /** Text labels registered by "lsp"'s ":s/…;…" text-sprite tag, keyed by sprite layer -- this
     * host doesn't actually render such text sprites (custom font/size/color), only tracks the
     * label text, so a "spbtn"-registered button group can be shown as a native choice menu (see
     * {@link #pendingButtonLabels}) instead of real clickable-sprite hit-testing. */
    public final Map<Integer, String> spriteTextLabels = new HashMap<>();

    /** Filename hint (path/tag/extension stripped) for the last "lsp"-loaded plain image sprite at
     * each layer -- NOT a real text label (see {@link #spriteTextLabels}), just the raw asset name,
     * consulted by "spbtn" as a slightly more informative fallback placeholder (e.g. "hajime"
     * instead of a bare button id) for an image-sprite button with no real text this host can show. */
    public final Map<Integer, String> spriteFileHints = new HashMap<>();

    /** (label, button id) pairs registered by "spbtn" since the last "btnwait" consumed them --
     * ONScripter-EN's clickable-button-sprite pattern ("lsp" + "spbtn" + "btnwait"), mapped onto
     * the host's native choice UI rather than real sprite rendering/hit-testing. Parallel lists,
     * in registration order. */
    public final List<String> pendingButtonLabels = new ArrayList<>();
    public final List<Integer> pendingButtonIds = new ArrayList<>();

    /** The button ids "btnwait" is currently offering as a native choice, snapshotted from {@link
     * #pendingButtonIds} at the moment it fired {@code onChoices} -- consulted (and then cleared)
     * by {@link NsScriptEngine#choose(int)}. */
    public List<Integer> pendingChoiceButtonIds = new ArrayList<>();

    /** Which numeric variable slot the next {@link NsScriptEngine#choose(int)} should write the
     * selected button's id into, instead of jumping to a per-option label -- non-null only while
     * the current WAITING_CHOICE came from "btnwait", not "select". Real "btnwait" falls through to
     * the line right after it once the variable is set; it never jumps anywhere. */
    public Integer pendingBtnwaitVarIndex = null;

    /** Snapshot of the currently-displayed WAITING_CHOICE menu (its option texts, plus enough of
     * {@link #pendingChoiceLabels}/{@link #pendingBtnwaitVarIndex}/{@link #pendingChoiceButtonIds}
     * to restore {@link NsScriptEngine#choose(int)}'s dispatch), kept until a new "select"/"btnwait"
     * menu replaces it. Lets a host action taken from inside a choice's target -- e.g. "systemcall
     * load" finding no save data -- redisplay the very same menu via {@link
     * NsScriptEngine#reshowLastChoiceMenu()} instead of leaving the engine paused with nothing on
     * screen. {@code lastChoiceLabels} is set for a "select"-style menu, {@code
     * lastChoiceBtnwaitVarIndex}/{@code lastChoiceButtonIds} for a "btnwait"-style one -- exactly
     * one of the two pairs is non-null at a time. All null until the first menu is ever shown. */
    public List<String> lastChoiceOptionTexts = null;
    public List<String> lastChoiceLabels = null;
    public Integer lastChoiceBtnwaitVarIndex = null;
    public List<Integer> lastChoiceButtonIds = null;

    /** The (text, label) pairs registered by "csel" -- ONScripter-EN's non-blocking select-link
     * declaration: "csel" parses the exact same "\"text\",label,..." syntax
     * as "select"/"selnum"/"selgosub" (see {@link NsCommandDispatcher}'s shared parsing helper), but
     * instead of blocking on a click or building native buttons, it just records these pairs and
     * jumps straight to a fixed "*customsel" label -- where the script itself is expected to build
     * custom clickable buttons via "cselbtn" at each index, later resolving the click through
     * "selectbtnwait" (real semantics identical to "btnwait") plus "cselgoto"/"getcselnum" (see
     * those handlers). Parallel lists, replaced wholesale by each "csel" call (real ONScripter
     * doesn't append across calls either -- its own select-link list is rebuilt fresh each time).
     * Cleared by "cselgoto" once it jumps, mirroring real ONScripter's own behavior of clearing
     * the select-link list right after "cselgoto" resolves. */
    public List<String> customSelectTexts = new ArrayList<>();
    public List<String> customSelectLabels = new ArrayList<>();

    /** Subdirectory (relative to the VN's own root dir), if any, that "nsadir" declared as where
     * loose assets and the ".nsa" archive actually live: unlike the plain "nsa"
     * directive (which uses the VN's own root, this engine's long-standing default), "nsadir <dir>"
     * points the SAME archive-then-loose-file resolution at a subdirectory instead -- e.g.
     * "nsadir \"data\"", where the actual "arc.nsa" and loose cursor bitmaps sit under
     * a "data/" folder, not the VN root. Empty string (never null) means "no override, use the VN
     * root directly," the common case. */
    public String nsaDir = "";

    /** One "for VAR=FROM to TO [step STEP]" loop's nest frame: the loop variable's slot,
     * its target bound and step, and the resume position "next" jumps back to (right after the
     * "for" line itself, i.e. the loop body's own start). {@link #brokeImmediately} mirrors real
     * ONScripter's own behavior: set when the very first bound check already fails (e.g.
     * "for %0=10 to 1" with a positive step) -- the loop body still runs zero times, but the very
     * first "next" must pop this frame instead of incrementing/looping. */
    public static final class ForFrame {
        public final int varIndex;
        public final long to;
        public final long step;
        public final int resumePc;
        public final boolean brokeImmediately;

        public ForFrame(int varIndex, long to, long step, int resumePc, boolean brokeImmediately) {
            this.varIndex = varIndex;
            this.to = to;
            this.step = step;
            this.resumePc = resumePc;
            this.brokeImmediately = brokeImmediately;
        }
    }

    /** Stack of currently-open "for" loops, most-recently-entered on top -- real "for" loops can
     * nest, matching real ONScripter's own NestInfo chain. See {@link ForFrame}. */
    public final Deque<ForFrame> forStack = new ArrayDeque<>();

    /** Where a fresh playthrough begins (see {@link NsScript#startPc}'s doc) -- also where the
     * "reset" command (see {@code NsCommandDispatcher}'s handler) jumps back to, since real
     * NScripter's "reset" is exactly that: simulate a fresh launch without actually restarting the
     * process. */
    public int startPc = 0;

    public void loadScript(NsScript script) {
        this.lines = script.lines;
        this.labelIndex = script.labelIndex;
        this.startPc = script.startPc;
    }
}
