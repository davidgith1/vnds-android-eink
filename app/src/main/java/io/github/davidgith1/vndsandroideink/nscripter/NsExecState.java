package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
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

    /** Parallel to {@link #callStack} (same size, pushed/popped in lockstep by every call site that
     * touches {@code callStack}): the not-yet-run rest of the CALLER's ':'-chained line, when a
     * "gosub" (or a defsub-pseudo-command call, which is dispatched the same way) was itself one
     * piece of a longer chain -- e.g. a very common real idiom, a confirm dialog's "Yes" branch:
     * "gosub *windowoff:textoff:csp -1:mp3fadeout 1000:bg black,0:print %110:wait 500:end". Real
     * ONScripter's own script cursor, once "gosub" pushes a return address and jumps, resumes
     * reading character-by-character from EXACTLY that pushed position once "return" pops it --
     * including the rest of a colon-chain the gosub itself was embedded in. This host's line-based
     * {@code pc} can't represent "resume mid-line" as a single int the way real ONScripter's
     * character-offset cursor can, so the leftover chain text is carried here instead: {@link
     * NsCommandDispatcher#executeChain} stashes it onto the just-pushed frame when a call happens
     * mid-chain, and the "return" handler runs it (via a fresh {@code executeChain} call) after
     * popping, instead of just falling through to whatever's on the next physical line. Empty string
     * (the common case: a gosub reached with nothing chained after it, or as a whole line by itself
     * -- {@link ArrayDeque} itself can't hold a real {@code null} element) means plain fall-through
     * is correct. Before this existed, a chain-embedded "gosub" silently
     * discarded everything chained after it once its own "return" fired -- e.g. the "Yes, return to
     * title"/"Yes, end game" confirm dialogs above would run the fade-out subroutine and then just
     * keep playing, never reaching the trailing "reset"/"end" at all.
     *
     * <p>Not persisted by {@link NsSaveManager}/{@code NsScriptEngine.Snapshot}: by the time a
     * save can happen (the engine sitting at a genuine {@code WAITING_TAP}/{@code WAITING_CHOICE}
     * block), any mid-chain gosub still on the stack is one still-running system-transition
     * subroutine away from finishing entirely synchronously in the common case -- an accepted, narrow
     * gap versus the churn of a save-format change, not a data-loss risk for ordinary player saves.
     *
     * <p>Deliberately a separate structure from {@link #pendingChainRemainder} rather than one
     * unified "pending continuation" concept, even though both exist to solve the same underlying
     * "resume mid ':' chain" problem: this one is inherently PER-CALL-FRAME data (as many
     * outstanding remainders as nested gosubs, popped by "return" in the same LIFO order as {@link
     * #callStack} itself, whose element type this is persisted in lockstep with -- see above), while
     * {@link #pendingChainRemainder} is a single top-level slot consumed by an entirely different
     * trigger ({@code resumeFromTap}/{@code resumeFromDelay}, not "return"). Merging them into one
     * field would either conflate those two distinct triggers (risking a live outstanding call frame
     * being mistaken for the current resume's own continuation, or vice versa) or force exactly the
     * save-format change the paragraph above exists to avoid, since {@link #callStack}'s element
     * type would need to grow a second field. Neither field actually needs a future blocking command
     * to know it exists, though: {@link NsCommandDispatcher#executeChain} already centralizes the
     * choice between them generically, by diffing {@code pc}/{@link #callStack}'s size/{@link
     * #runState} before and after each chain piece runs -- a new blocking command just changes
     * {@code runState} or jumps, and the right mechanism is selected for it automatically. */
    public final Deque<String> callStackChainRemainder = new ArrayDeque<>();

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

    /** The not-yet-run rest of the CURRENT script line's ':'-chained commands, when one of them
     * (almost always "wait") blocked mid-chain -- e.g. a very common real idiom, a confirm dialog's
     * "Yes" branch: "gosub *windowoff:textoff:...:wait 500:end" (or "...:reset"). Real ONScripter's
     * own script cursor sits mid-line at the exact point "wait" returns and simply keeps reading
     * character by character from there; this host's host-driven WAITING_DELAY model instead
     * returns control to the caller and must be told, on resume, to pick the SAME line back up
     * rather than falling through to whatever comes next on the FOLLOWING line. Null means there's
     * nothing left of the current line to resume -- either it wasn't a chain, or the chain finished
     * normally, or execution jumped elsewhere entirely (a real goto/gosub/return supersedes
     * whatever was left of the old line, matching real ONScripter's own cursor motion -- see
     * NsCommandDispatcher.executeChain's own doc for exactly when this is set vs left null). Before
     * this existed, a blocking "wait" mid-chain silently discarded everything chained after it: a
     * "Yes, end the game" confirmation would show the fade-to-black and then just... keep running
     * the next unrelated line, never actually calling "end"/"reset".
     *
     * <p>See {@link #callStackChainRemainder}'s own doc for why that field exists separately
     * instead of this one covering the gosub-mid-chain case too. */
    public String pendingChainRemainder = null;

    /** The label each currently-shown choice jumps to, set by the "select" handler and consulted
     * by {@link NsScriptEngine#choose(int)} -- NScripter's select jumps directly per option,
     * unlike VNDS's choice (which just sets a "selected" variable and falls through). */
    public List<String> pendingChoiceLabels = new ArrayList<>();

    /** True while {@link #pendingChoiceLabels} came from "selgosub" rather than plain "select" --
     * real ONScripter-EN's SELECT_GOSUB_MODE pushes a return address (the line right after the
     * whole selgosub block, same position "gosub"/"return" already use) onto the call stack before
     * jumping, so the chosen branch can "return" back into the menu's own flow instead of the plain
     * one-way jump "select" does. Consulted (and reset) by {@link NsScriptEngine#choose(int)}. */
    public boolean pendingChoiceIsGosub = false;

    /** Text labels registered by "lsp"'s ":s/…;…" text-sprite tag, keyed by sprite layer -- this
     * host doesn't actually render such text sprites (custom font/size/color), only tracks the
     * label text, so a "spbtn"-registered button group can be shown as a native choice menu (see
     * {@link #pendingButtons}) instead of real clickable-sprite hit-testing. */
    public final Map<Integer, String> spriteTextLabels = new HashMap<>();

    /** Filename hint (path/tag/extension stripped) for the last "lsp"-loaded plain image sprite at
     * each layer -- NOT a real text label (see {@link #spriteTextLabels}), just the raw asset name,
     * consulted by "spbtn" as a slightly more informative fallback placeholder (e.g. "hajime"
     * instead of a bare button id) for an image-sprite button with no real text this host can show. */
    public final Map<Integer, String> spriteFileHints = new HashMap<>();

    /** The resolved image file for the last "lsp"-loaded plain image sprite at each layer --
     * companion to {@link #spriteFileHints} (a display-name fallback), this is the actual asset a
     * host CAN render, consulted by "spbtn" so an image-sprite button (see {@link
     * #pendingButtons}) can show its real graphic instead of only a text placeholder. Absent
     * (or stale, harmlessly -- "spbtn" only reads it when {@link #spriteTextLabels} has no entry
     * for that layer) for a text-labeled layer, which has no image of its own. */
    public final Map<Integer, File> spriteImageFiles = new HashMap<>();

    /** x/y position for the last "lsp"/"lsph" real image-sprite load at each layer -- "lsp" already
     * fires {@link io.github.davidgith1.vndsandroideink.engine.VnEngine.Listener#onSprite} with
     * these itself, but "lsph" (see that handler's doc) loads the same image while starting hidden,
     * so a later "vsp" revealing it needs to recall where it actually goes. */
    public final Map<Integer, int[]> spritePositions = new HashMap<>();

    /** The raw, still-tag-carrying "lsp"/"lsph" spec string (before {@link #spriteImageFiles}'s own
     * tag-stripped resolution) for the last real image-sprite load at each layer -- kept so a later
     * "vsp" reveal can recompute the exact same transparency/cell-count "lsp" itself would have,
     * without re-deriving them from the already-stripped File alone. */
    public final Map<Integer, String> spriteRawSpecs = new HashMap<>();

    /** The image most recently loaded by "btndef" (real syntax: {@code btndef "file"}, or {@code
     * btndef clear}/{@code btndef ""} to clear it) -- unlike "lsp"'s per-layer sprites, real
     * ONScripter-EN's "btn no,x,y,w,h,srcX,srcY" idiom (see that handler's own doc) has no sprite
     * layer of its own at all: EVERY "btn" registered while one "btndef" image is loaded crops its
     * own visible appearance out of this SAME shared bitmap, at its own (srcX,srcY,w,h) -- there is
     * no other source for what a "btn" button actually looks like. Null (the common starting state,
     * or after "btndef clear") means "btn" has nothing to crop from, so it registers a button with
     * no image at all (see {@link ButtonEntry#cropRect}'s own doc on the fallback that
     * results). */
    public File btndefImage = null;

    /** One button registered by "spbtn"/"exbtn"/"btn" (tagged {@link ButtonEntry.Source#SPBTN}) or
     * linked via "cselbtn" (tagged {@link ButtonEntry.Source#CSEL}), pending until the next
     * "btnwait"-family command consumes them -- ONScripter-EN's clickable-button-sprite pattern
     * ("lsp" + "spbtn" + "btnwait"), mapped onto the host's native choice UI rather than real
     * sprite rendering/hit-testing. Both sources share one list (formerly six-plus-six verbatim-
     * duplicated parallel lists, one set per source, which every producer/consumer had to push/
     * clear/snapshot in lockstep by hand with no compiler-enforced alignment -- e.g. "reset" once
     * cleared the SPBTN pair but forgot the CSEL one, desyncing them until the next "spbtn"
     * registration zipped new labels against stale images) but stay logically separate via {@link
     * ButtonEntry#source}: a "csel" declaration is the script author's own real narrative decision
     * (e.g. "Coffee" vs "Sports drink"), while a plain "spbtn"/"exbtn" registered around the same
     * time is very commonly a persistent, always-present system toolbar (quick-save/quick-load/
     * menu/backlog/skip/auto/help icons, gosub'd from a shared subroutine and re-registered before
     * essentially every blocking wait in the whole script, choice screens included) --
     * functionality this host's own UI chrome (ReaderActivity's save/load/settings/text-log
     * controls) already provides natively. A btnwait that finds any CSEL entries shows ONLY those
     * as the native choice menu (see that handler's own doc) rather than drowning the one real
     * decision the script is actually asking the player to make in a wall of unrelated toolbar
     * icons -- before this separation, e.g. "Coffee" vs "Sports drink" appeared as a 2-option
     * choice buried among 8+ generic system buttons with no way to tell which was which. CSEL
     * entries are cleared by "csel" (a new declaration replaces the old one), same as {@link
     * #customSelectTexts} -- SPBTN entries are untouched by that, since a toolbar re-registered
     * independently of any "csel" must survive it. */
    public final List<ButtonEntry> pendingButtons = new ArrayList<>();

    /** One entry in {@link #pendingButtons}. */
    public static final class ButtonEntry {
        public enum Source { SPBTN, CSEL }

        public final String label;
        public final int id;
        /** {@code null} for a button with no real image to show (a text-labeled sprite, or a
         * "cselbtn" link, which never has one). */
        public final File image;
        /** Transparency/alpha-mask-cell-count for {@link #image}, the same pair {@link
         * io.github.davidgith1.vndsandroideink.engine.VnEngine.Listener#onSprite} already gets for
         * an ordinary sprite -- an spbtn button's own image is JUST the layer's "lsp"-loaded
         * sprite, so it carries the exact same real transparency tag (an ":a;" alpha-mask cutout,
         * a ":l;"/untagged top-left color-key, or a flat ":c;" opaque crop) that sprite would if
         * shown directly, not a plain untreated rectangle. Meaningless (but always populated, as
         * {@code OPAQUE}/{@code 1}) when {@link #image} is {@code null}. */
        public final VnEngine.SpriteTransparency transparency;
        public final int alphaMaskCells;
        /** {@code {srcX, srcY, w, h}} sub-rectangle of {@link #image} to show as this button's own
         * graphic, or {@code null} to show the whole image untouched. Only ever non-null for a
         * plain "btn"-registered button: its own image IS a crop of the shared {@link
         * #btndefImage}, at exactly the rectangle real ONScripter-EN's own "btn no,x,y,w,h,srcX,
         * srcY" declares -- unlike "spbtn"/"exbtn" (a whole per-layer sprite image, no cropping)
         * or "cselbtn" (no image at all). */
        public final int[] cropRect;
        public final Source source;

        public ButtonEntry(String label, int id, File image, VnEngine.SpriteTransparency transparency,
                            int alphaMaskCells, int[] cropRect, Source source) {
            this.label = label;
            this.id = id;
            this.image = image;
            this.transparency = transparency;
            this.alphaMaskCells = alphaMaskCells;
            this.cropRect = cropRect;
            this.source = source;
        }
    }

    /** The button ids "btnwait" is currently offering as a native choice, snapshotted from {@link
     * #pendingButtons} at the moment it fired {@code onChoices} -- consulted (and then cleared)
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
    public boolean lastChoiceIsGosub = false;
    public Integer lastChoiceBtnwaitVarIndex = null;
    public List<Integer> lastChoiceButtonIds = null;
    /** Snapshot of {@link #pendingButtons} at the same moment {@link #lastChoiceButtonIds} was
     * snapshotted -- null for a "select"-style menu (which never has button images), parallel to
     * {@link #lastChoiceOptionTexts} for a "btnwait"-style one. */
    public List<File> lastChoiceImages = null;
    /** See {@link ButtonEntry#transparency}; the same pairing, snapshotted alongside
     * {@link #lastChoiceImages}. Null exactly when {@link #lastChoiceImages} is null. */
    public List<VnEngine.SpriteTransparency> lastChoiceImageTransparencies = null;
    public List<Integer> lastChoiceImageAlphaMaskCells = null;
    /** See {@link ButtonEntry#cropRect}; the same pairing, snapshotted alongside {@link
     * #lastChoiceImages}. Null exactly when {@link #lastChoiceImages} is null. */
    public List<int[]> lastChoiceImageCropRects = null;

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
