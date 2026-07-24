package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A clean-room, core-subset interpreter for plain-text NScripter scripts -- structurally the
 * NScripter-format counterpart to {@code vnds.ScriptEngine}: a line-pointer loop
 * ({@link #runLoop()}) that executes commands until something must block on the player (a
 * pagewait/clickwait dialogue line, or a {@code select}), at which point it returns and waits for
 * {@link #resumeFromTap()}/{@link #choose(int)}. Unlike VNDS's per-file scripts, the whole script
 * (every numbered continuation file) is one combined line buffer -- see {@link NsScriptSource}.
 *
 * <p>Command behavior itself lives in {@link NsCommandDispatcher}; this class owns the
 * {@link VnEngine} surface, construction/loading, and the run loop's blocking-state transitions.
 */
public final class NsScriptEngine implements VnEngine {

    private final File vnDir;
    private final Listener listener;
    private final NsExecState state = new NsExecState();

    public NsScriptEngine(File vnDir, Listener listener, Map<String, String> initialGlobals) {
        this.vnDir = vnDir;
        this.listener = listener;
        // initialGlobals is currently unused: NScripter's persistent-global mechanism isn't wired
        // up yet (see getGlobalsSnapshot()) -- deferred to the save/load milestone.
    }

    @Override
    public void setDelaysEnabled(boolean enabled) {
        state.delaysEnabled = enabled;
    }

    @Override
    public State getState() {
        return state.runState;
    }

    @Override
    public String getCurrentFile() {
        // NScripter has no per-file position -- every continuation file is concatenated into one
        // buffer (see NsScriptSource) -- so this is a constant placeholder, not a real filename.
        return "0.txt";
    }

    @Override
    public int getPc() {
        return state.pc;
    }

    @Override
    public Map<String, String> getVariablesSnapshot() {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<Integer, Long> e : state.numVars.entrySet()) {
            m.put("%" + e.getKey(), String.valueOf(e.getValue()));
        }
        for (Map.Entry<Integer, String> e : state.strVars.entrySet()) {
            m.put("$" + e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public Map<String, String> getGlobalsSnapshot() {
        // No persistent-global mechanism yet -- see the constructor's note on initialGlobals.
        return new HashMap<>();
    }

    @Override
    public void setVariable(String name, String value) {
        if (name.startsWith("%")) {
            putNum(name.substring(1), value);
        } else if (name.startsWith("$")) {
            putStr(name.substring(1), value);
        }
    }

    @Override
    public void setGlobal(String name, String value) {
        // No persistent-global mechanism yet: a host-driven edit here has nothing to persist into.
    }

    private void putNum(String indexStr, String value) {
        try {
            state.numVars.put(Integer.parseInt(indexStr), Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Malformed snapshot key/value: ignore, same tolerance as an unrecognized command.
        }
    }

    private void putStr(String indexStr, String value) {
        try {
            state.strVars.put(Integer.parseInt(indexStr), value);
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public boolean isPageEndPending() {
        int i = state.pc;
        while (i < state.lines.size()) {
            NsLine line = NsTokenizer.classify(state.lines.get(i));
            if (line.type == NsLine.Type.BLANK || line.type == NsLine.Type.COMMENT) {
                i++;
                continue;
            }
            if (line.type != NsLine.Type.STATEMENT) {
                return false; // a label/tilde marker next: not a page-ending dialogue line
            }
            // Matches NsCommandDispatcher's own dialogue-vs-command rule: any lowercase-leading
            // statement is treated as a command invocation (known or not), never dialogue.
            boolean isDialogueLine = line.text.isEmpty() || !Character.isLowerCase(line.text.charAt(0));
            return isDialogueLine && line.text.endsWith("\\");
        }
        return false;
    }

    @Override
    public void start() {
        NsScript script = NsScriptSource.load(vnDir);
        state.loadScript(script);
        // startPc begins at the "*define" label, not after "game" (see NsScript#startPc's doc) --
        // running normally from there through "game" naturally executes the header's own
        // "numalias"/"stralias" declarations (and anything they gosub into) exactly like real
        // NScripter does, so no separate registration pass is needed here.
        state.pc = script.startPc;
        state.runState = State.RUNNING;
        runLoop();
    }

    @Override
    public void restoreState(String file, int savedPc, Map<String, String> vars) {
        if (state.lines.isEmpty()) {
            state.loadScript(NsScriptSource.load(vnDir));
        }
        state.pc = Math.max(0, Math.min(savedPc, state.lines.size()));
        state.numVars.clear();
        state.strVars.clear();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            setVariable(e.getKey(), e.getValue());
        }
        state.runState = State.WAITING_TAP;
    }

    /**
     * The full interpreter state {@link NsSaveManager} needs to snapshot -- richer than what
     * {@link #restoreState}'s generic {@code VnEngine} signature can carry (a plain {@code
     * Map<String,String>} can't represent numalias/stralias tables or a call stack), so this is an
     * NScripter-specific addition, not part of the {@link VnEngine} interface.
     */
    public static final class Snapshot {
        public final int pc;
        public final Map<Integer, Long> numVars;
        public final Map<Integer, String> strVars;
        public final Map<String, Integer> numAliases;
        public final Map<String, Integer> strAliases;
        /** Top-of-stack first (i.e. this class's own iteration/construction order), matching how
         * {@link NsExecState#callStack} (an {@link ArrayDeque} used as a stack) iterates -- see
         * {@link #restoreFromSnapshot} for how it's pushed back in the right order. */
        public final List<Integer> callStack;
        public final boolean pendingPageClearOnResume;
        /** See {@link NsExecState#pendingDialogueRemainder}; null (never empty) when there's
         * nothing left to show on the current page. */
        public final String pendingDialogueRemainder;
        /** See {@link NsExecState#barewordConstants}. Restoring a save jumps straight to its saved
         * {@code pc} without re-running anything (see {@link #restoreFromSnapshot}'s own doc), so
         * unlike {@link #start()} -- which reaches these by actually executing the "*define"
         * header -- a restored playthrough has no other way to recover them. */
        public final Map<String, String> barewordConstants;
        /** See {@link NsExecState#nsaDir}. Not restoring this was a real bug: a resumed
         * playthrough of a sample whose assets live under an "nsadir"-declared subdirectory (that
         * command only ever runs once, in the "*define" header, which a restore jumps past rather
         * than re-executing) would revert to resolving assets against the VN root, so every
         * background/sprite/sound load *after* the restore point silently failed to resolve --
         * the restored save's own already-loaded image/music still showed fine since those come
         * from the save's literal saved paths, not a fresh resolve. */
        public final String nsaDir;

        public Snapshot(int pc, Map<Integer, Long> numVars, Map<Integer, String> strVars,
                         Map<String, Integer> numAliases, Map<String, Integer> strAliases,
                         List<Integer> callStack, boolean pendingPageClearOnResume) {
            this(pc, numVars, strVars, numAliases, strAliases, callStack, pendingPageClearOnResume,
                    null, new HashMap<>(), "");
        }

        public Snapshot(int pc, Map<Integer, Long> numVars, Map<Integer, String> strVars,
                         Map<String, Integer> numAliases, Map<String, Integer> strAliases,
                         List<Integer> callStack, boolean pendingPageClearOnResume,
                         String pendingDialogueRemainder) {
            this(pc, numVars, strVars, numAliases, strAliases, callStack, pendingPageClearOnResume,
                    pendingDialogueRemainder, new HashMap<>(), "");
        }

        public Snapshot(int pc, Map<Integer, Long> numVars, Map<Integer, String> strVars,
                         Map<String, Integer> numAliases, Map<String, Integer> strAliases,
                         List<Integer> callStack, boolean pendingPageClearOnResume,
                         String pendingDialogueRemainder, Map<String, String> barewordConstants) {
            this(pc, numVars, strVars, numAliases, strAliases, callStack, pendingPageClearOnResume,
                    pendingDialogueRemainder, barewordConstants, "");
        }

        public Snapshot(int pc, Map<Integer, Long> numVars, Map<Integer, String> strVars,
                         Map<String, Integer> numAliases, Map<String, Integer> strAliases,
                         List<Integer> callStack, boolean pendingPageClearOnResume,
                         String pendingDialogueRemainder, Map<String, String> barewordConstants,
                         String nsaDir) {
            this.pc = pc;
            this.numVars = numVars;
            this.strVars = strVars;
            this.numAliases = numAliases;
            this.strAliases = strAliases;
            this.callStack = callStack;
            this.pendingPageClearOnResume = pendingPageClearOnResume;
            this.pendingDialogueRemainder = pendingDialogueRemainder;
            this.barewordConstants = barewordConstants;
            this.nsaDir = nsaDir == null ? "" : nsaDir;
        }
    }

    public Snapshot snapshotState() {
        return new Snapshot(state.pc, new HashMap<>(state.numVars), new HashMap<>(state.strVars),
                new HashMap<>(state.numAliases), new HashMap<>(state.strAliases),
                new ArrayList<>(state.callStack), state.pendingPageClearOnResume,
                state.pendingDialogueRemainder, new HashMap<>(state.barewordConstants), state.nsaDir);
    }

    /** The counterpart to {@link #snapshotState()} -- like {@link #restoreState}, repositions
     * without re-running any commands, so the caller is responsible for restoring on-screen
     * visuals to match. */
    public void restoreFromSnapshot(Snapshot snapshot) {
        if (state.lines.isEmpty()) {
            state.loadScript(NsScriptSource.load(vnDir));
        }
        state.pc = Math.max(0, Math.min(snapshot.pc, state.lines.size()));
        state.numVars.clear();
        state.numVars.putAll(snapshot.numVars);
        state.strVars.clear();
        state.strVars.putAll(snapshot.strVars);
        state.numAliases.clear();
        state.numAliases.putAll(snapshot.numAliases);
        state.strAliases.clear();
        state.strAliases.putAll(snapshot.strAliases);
        state.barewordConstants.clear();
        state.barewordConstants.putAll(snapshot.barewordConstants);
        state.nsaDir = snapshot.nsaDir;
        state.callStack.clear();
        // Push back-to-front so the resulting stack's pop order matches what was snapshotted
        // (the snapshot list is top-of-stack-first; push() adds to the top).
        for (int i = snapshot.callStack.size() - 1; i >= 0; i--) {
            state.callStack.push(snapshot.callStack.get(i));
        }
        state.pendingPageClearOnResume = snapshot.pendingPageClearOnResume;
        state.pendingDialogueRemainder = snapshot.pendingDialogueRemainder;
        state.runState = State.WAITING_TAP;
    }

    @Override
    public void resumeFromTap() {
        if (state.runState != State.WAITING_TAP) {
            return;
        }
        state.runState = State.RUNNING;
        if (state.pendingBtnwaitVarIndex != null) {
            // A "btnwait"/"selectbtnwait" with no registered buttons still blocks for a plain tap
            // (see NsCommandDispatcher's btnwaitHandler doc) -- resolves to -1, matching real
            // ONScripter's own "no button hit" sentinel for a click that misses every registered
            // button-sprite.
            state.numVars.put(state.pendingBtnwaitVarIndex, -1L);
            state.pendingBtnwaitVarIndex = null;
        }
        if (state.pendingDialogueRemainder != null) {
            // A mid-line "@" pause: more of the *current* script line is still waiting to be
            // shown, rather than advancing to the next one -- see NsDialogue's class doc.
            String remainder = state.pendingDialogueRemainder;
            state.pendingDialogueRemainder = null;
            NsDialogue.handle(state, remainder, listener, true);
            if (state.runState != State.RUNNING) {
                return; // hit another '@'/'\' -- stay put until the next tap
            }
        }
        runLoop();
    }

    @Override
    public void resumeFromDelay() {
        if (state.runState == State.WAITING_DELAY) {
            state.runState = State.RUNNING;
            runLoop();
        }
    }

    @Override
    public void pauseForHostTiming() {
        state.runState = State.WAITING_DELAY;
    }

    @Override
    public void choose(int zeroBasedIndex) {
        if (state.runState != State.WAITING_CHOICE) {
            return;
        }
        if (state.pendingBtnwaitVarIndex != null) {
            // "btnwait"'s choice (see NsCommandDispatcher's handler): unlike "select", it never
            // jumps anywhere -- it just stores the picked button's id and falls through to the
            // line right after "btnwait".
            if (zeroBasedIndex >= 0 && zeroBasedIndex < state.pendingChoiceButtonIds.size()) {
                state.numVars.put(state.pendingBtnwaitVarIndex,
                        (long) state.pendingChoiceButtonIds.get(zeroBasedIndex));
            }
            state.pendingBtnwaitVarIndex = null;
            state.pendingChoiceButtonIds = new ArrayList<>();
            state.runState = State.RUNNING;
            runLoop();
            return;
        }
        if (zeroBasedIndex >= 0 && zeroBasedIndex < state.pendingChoiceLabels.size()) {
            Integer dest = state.labelIndex.get(state.pendingChoiceLabels.get(zeroBasedIndex));
            if (dest != null) {
                state.pc = dest;
            }
        }
        state.pendingChoiceLabels = new ArrayList<>();
        state.runState = State.RUNNING;
        runLoop();
    }

    @Override
    public boolean reshowLastChoiceMenu() {
        if (state.lastChoiceOptionTexts == null || state.lastChoiceOptionTexts.isEmpty()) {
            return false;
        }
        if (state.lastChoiceLabels != null) {
            state.pendingChoiceLabels = new ArrayList<>(state.lastChoiceLabels);
            state.pendingBtnwaitVarIndex = null;
            state.pendingChoiceButtonIds = new ArrayList<>();
        } else {
            state.pendingChoiceLabels = new ArrayList<>();
            state.pendingBtnwaitVarIndex = state.lastChoiceBtnwaitVarIndex;
            state.pendingChoiceButtonIds = new ArrayList<>(state.lastChoiceButtonIds);
        }
        state.runState = State.WAITING_CHOICE;
        listener.onChoices(new ArrayList<>(state.lastChoiceOptionTexts));
        return true;
    }

    /**
     * Defensive cap on consecutive lines run in one {@link #runLoop()} call, without the host ever
     * regaining control. This is necessary in practice: some real scripts rely on a
     * blocking-wait command (e.g. "btnwait", well outside the core subset) inside what's meant to
     * be an interactive loop -- since an unrecognized command silently no-ops rather than blocking
     * (by design; see {@code NsCommandDispatcher.runOneStatement}), that loop would otherwise spin
     * the calling thread forever instead of ever returning to the host. Generous enough that no
     * normal script segment between two real taps/choices should ever come close to it.
     */
    private static final int MAX_STEPS_PER_RESUME = 200_000;

    private void runLoop() {
        if (state.pendingPageClearOnResume) {
            state.pendingPageClearOnResume = false;
            listener.onTextClear();
        }
        int steps = 0;
        while (state.runState == State.RUNNING) {
            if (state.pc >= state.lines.size()) {
                state.runState = State.FINISHED;
                listener.onFinished();
                return;
            }
            if (++steps > MAX_STEPS_PER_RESUME) {
                // See MAX_STEPS_PER_RESUME's doc: treat this the same as any other blocking point
                // so the host regains control (and could, at minimum, let the player back out)
                // instead of the app appearing to hang.
                state.runState = State.WAITING_TAP;
                return;
            }
            String raw = state.lines.get(state.pc);
            state.pc++;
            NsCommandDispatcher.execute(NsTokenizer.classify(raw), state, listener, vnDir);
        }
    }
}
