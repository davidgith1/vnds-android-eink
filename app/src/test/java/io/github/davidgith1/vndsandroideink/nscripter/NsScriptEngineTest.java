package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class NsScriptEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(tmp.getRoot(), "0.txt")), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    @Test
    public void playsThroughDialogueTapsToAChoiceThenToTheEnd() throws IOException {
        write(String.join("\n",
                "*start",
                "mov %1,0",
                "Hello there\\",
                "Still here@",
                "select \"Go left\",*left,\"Go right\",*right",
                "*left",
                "mov %1,1",
                "goto *done",
                "*right",
                "mov %1,2",
                "*done",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("Hello there", listener.textLines.get(0));

        engine.resumeFromTap(); // clears page, shows "Still here@" (clickwait, no clear on next resume)
        assertEquals(1, listener.textClears); // page clear fired from the pagewait marker
        assertEquals("Still here", listener.textLines.get(1));
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());

        engine.resumeFromTap(); // continues straight into "select" -- no second clear (no '\' marker)
        assertEquals(1, listener.textClears);
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(2, listener.lastChoices.size());

        engine.choose(1); // "Go right" -> *right -> mov %1,2 -> falls through to *done -> "The end\"
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("The end", listener.textLines.get(2));
        assertEquals("2", engine.getVariablesSnapshot().get("%1"));

        engine.resumeFromTap(); // past EOF: finished
        assertTrue(listener.finished);
        assertEquals(VnEngine.State.FINISHED, engine.getState());
    }

    @Test
    public void selectStillCollectsItsOptionListAcrossABlankLine() throws IOException {
        // A real, observed pattern (Instant Death! Panda Samurai's own title-screen "select"s):
        // "select" on its own bare line, THEN a blank line, THEN the "\"text\",*label,..."
        // continuation -- e.g. "select\n\n\"` Start \",*ppa,\n\"` Postscript \",*kou". Before
        // NsCommandDispatcher.collectSelectPairs skipped over blank continuation lines, the very
        // first blank line right after a bare "select" made it stop collecting immediately (before
        // consuming any real option), leaving the "\"text\",*label" lines to fall through to the
        // normal per-line dispatch loop as their own separate statements -- which aren't a
        // recognized command, so they were shown as raw, unparsed dialogue (literally
        // "\"` Start \",*ppa" printed on screen) instead of ever presenting a real choice menu.
        write(String.join("\n",
                "*start",
                "select",
                "",
                "\"Go left\",*left,",
                "\"Go right\",*right",
                "*left",
                "mov %1,1",
                "goto *done",
                "*right",
                "mov %1,2",
                "*done",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(java.util.Arrays.asList("Go left", "Go right"), listener.lastChoices);

        engine.choose(1); // "Go right" -> *right -> mov %1,2 -> falls through to *done
        assertEquals("2", engine.getVariablesSnapshot().get("%1"));
        assertEquals("The end", listener.textLines.get(0));
    }

    @Test
    public void selgosubJumpsViaGosubAndReturnComesBackRightAfterTheWholeBlock() throws IOException {
        // Real ONScripter-EN's "selgosub" (see NsCommandDispatcher's handler and
        // NsExecState.pendingChoiceIsGosub's doc): unlike "select"'s plain one-way jump, the chosen
        // option's label is reached via "gosub", so its own "return" comes back to right after the
        // whole (possibly multi-line, backtick-quoted) selgosub block -- the real-world idiom this
        // supports is a scene's own "already viewed -- skip?" menu repeated throughout a script,
        // where either branch needs to fall back into the same continuing flow. Before "selgosub"
        // was implemented, it was an unrecognized mnemonic that silently no-op'd, and its own
        // continuation line (starting with a backtick, not a command mnemonic) read as literal
        // garbage dialogue instead of ever presenting a real choice.
        write(String.join("\n",
                "*start",
                "mov %1,99",
                "selgosub `1. Option A`,*optA,",
                "\t`2. Option B`,*optB",
                "The end\\",
                "goto *done",
                "*optA",
                "mov %1,1",
                "return",
                "*optB",
                "mov %1,2",
                "return",
                "*done",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(2, listener.lastChoices.size());
        assertEquals("1. Option A", listener.lastChoices.get(0));
        assertEquals("2. Option B", listener.lastChoices.get(1));

        engine.choose(0); // "Option A" -> gosub *optA -> mov %1,1 -> return -> right after the block
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("1", engine.getVariablesSnapshot().get("%1"));
        assertEquals("The end", listener.textLines.get(listener.textLines.size() - 1));
    }

    /** Mirrors a real, very common Tsukihime idiom (repeated dozens of times, once per scene):
     * "if %sceneskip==1 && %viewed==1 skip 4 / gosub *scene / mov %viewed,1 / skip N /
     * <already-viewed-prompt via selgosub> / skip 3 / *afterprompt / return" -- %900/%901 stand in
     * for %sceneskip/%viewed here (real script "numalias"-declares those, which this hand-written
     * fixture doesn't bother with; a bare unregistered name would resolve to variable slot 0 for
     * BOTH, aliasing them together -- see NsExpr.resolveIndex's tolerant fallback). Real
     * Tsukihime's own copy of this idiom uses "skip 9" for N; this test's own line spacing differs
     * slightly, hence "skip 8" below -- the exact count is just "however many lines separate this
     * skip from the line right after the whole prompt block," not a fixed constant. First time
     * through (either the scene hasn't been viewed yet, or the player hasn't turned on auto-skip),
     * the scene plays normally and the "already viewed" prompt never appears at all -- the trailing
     * "skip" jumps past the whole prompt block. See both this and {@link
     * #skipJumpsStraightIntoTheAlreadyViewedPromptWhenBothConditionsAreTrue} together: before
     * "skip" was implemented, the guard on line 1 always fell through as if it had never fired
     * (an unrecognized mnemonic silently no-ops), so the prompt below appeared unconditionally on
     * every single visit -- first time or not, auto-skip on or not. */
    @Test
    public void skipJumpsPastTheAlreadyViewedPromptOnAFreshFirstTimeVisit() throws IOException {
        write(String.join("\n",
                "*start",
                "mov %900,0",
                "mov %901,0",
                "if %900==1 && %901==1 skip 4",
                "gosub *scene",
                "mov %901,1",
                "skip 8",
                "`You have already viewed this scene.",
                "`Would you like to skip?",
                "selgosub `1. Skip`,*afterprompt,",
                "\t`2. Don't skip`,*scene",
                "skip 3",
                "*afterprompt",
                "return",
                "Continuing story.\\",
                "goto *done",
                "*scene",
                "Scene content.\\",
                "return",
                "*done",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        // The scene subroutine ran for real (its own line showed) ...
        assertTrue(listener.textLines.contains("Scene content."));
        engine.resumeFromTap();
        // ... and the "already viewed" prompt never appeared -- straight through to the next line.
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("Continuing story.", listener.textLines.get(listener.textLines.size() - 1));
        assertEquals("1", engine.getVariablesSnapshot().get("%901"));
    }

    @Test
    public void skipJumpsStraightIntoTheAlreadyViewedPromptWhenBothConditionsAreTrue() throws IOException {
        write(String.join("\n",
                "*start",
                "mov %900,1",
                "mov %901,1",
                "if %900==1 && %901==1 skip 4",
                "gosub *scene",
                "mov %901,1",
                "skip 9",
                "`You have already viewed this scene.",
                "`Would you like to skip?",
                "selgosub `1. Skip`,*afterprompt,",
                "\t`2. Don't skip`,*scene",
                "skip 3",
                "*afterprompt",
                "return",
                "Continuing story.\\",
                "goto *done",
                "*scene",
                "Scene content.\\",
                "return",
                "*done",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        // Landed directly on the prompt -- the scene subroutine was NOT re-run this time.
        assertFalse(listener.textLines.contains("Scene content."));
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals("You have already viewed this scene.Would you like to skip?",
                String.join("", listener.textLines));
        assertEquals(2, listener.lastChoices.size());
        assertEquals("1. Skip", listener.lastChoices.get(0));
        assertEquals("2. Don't skip", listener.lastChoices.get(1));

        engine.choose(0); // "Skip" -> gosub *afterprompt -> return -> right after the "skip 3" line
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("Continuing story.", listener.textLines.get(listener.textLines.size() - 1));
        assertFalse("the scene must still not have been replayed", listener.textLines.contains("Scene content."));
    }

    @Test
    public void aBlockingWaitMidChainResumesTheRestOfThatSameLineNotTheNextOne() throws IOException {
        // Mirrors a real, very common confirm-dialog idiom (real ONScripter-EN's own script cursor
        // reads character-by-character, not line-by-line, so a mid-chain "wait" resumes into
        // whatever's chained after it on the SAME line -- see NsExecState.pendingChainRemainder's
        // doc): "gosub *windowoff:mov %1,1:wait 500:mov %1,2:goto *done". Before this was fixed,
        // NsCommandDispatcher.executeChain's own "state changed -> stop" check couldn't tell a
        // genuine jump (goto/gosub/select, whose destination correctly supersedes the rest of the
        // old line) apart from a same-position block like "wait" -- so as soon as "wait" flipped
        // runState to WAITING_DELAY, the rest of the chain ("mov %1,2:goto *done") was silently
        // dropped, and resuming just fell through to whatever the NEXT unrelated script line was.
        // A real-world instance: a "Yes, quit" confirmation's Yes branch is exactly this shape
        // ("...:wait 500:end"/"...:wait 500:reset") -- pressing Yes visibly faded out and then
        // just kept running the story instead of ever actually quitting/resetting.
        write(String.join("\n",
                "*start",
                "mov %1,1",
                "if %1==1 mov %1,1:wait 500:mov %1,2:goto *done",
                "mov %1,99", // dead code if the chain resumes correctly; would run if it doesn't
                "*done",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.setDelaysEnabled(true);

        engine.start();
        assertEquals(VnEngine.State.WAITING_DELAY, engine.getState());
        assertEquals("1", engine.getVariablesSnapshot().get("%1")); // "mov %1,1" already ran

        engine.resumeFromDelay();
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        // "mov %1,2" (chained AFTER "wait") and the "goto *done" jump both ran -- not "mov %1,99".
        assertEquals("2", engine.getVariablesSnapshot().get("%1"));
        assertEquals("The end", listener.textLines.get(0));
    }

    @Test
    public void aGosubEmbeddedMidChainResumesTheRestOfThatSameLineOnceItsSubroutineReturns() throws IOException {
        // The OTHER half of the real "*check_reset"/"*check_end" confirm-dialog idiom (see the
        // "wait"-mid-chain test above): "gosub *windowoff:textoff:...:wait 500:end" -- here the
        // BLOCKING command isn't what's mid-chain, the "gosub" itself is, and it's a real jump, not
        // an in-place block. Before NsExecState.callStackChainRemainder existed,
        // NsCommandDispatcher.executeChain's "state.pc changed -> stop" check fired the instant
        // "gosub" jumped, discarding "mov %2,99:end" outright -- so once *sub's own "return" popped
        // back, the engine just fell through to the next unrelated physical line, and the user's
        // real report ("return to title" / "close the game" Yes button doing neither) was exactly
        // this: the fade-out subroutine ran, then the story just kept playing.
        write(String.join("\n",
                "*start",
                "mov %1,1",
                "if %1==1 gosub *fadeout:mov %2,99:end",
                "mov %2,7", // dead code if the chain resumes correctly; would run if it doesn't
                "*fadeout",
                "mov %3,1",
                "return",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        // "gosub *fadeout" ran (mov %3,1), "return" popped back into the middle of the original
        // chain, "mov %2,99" ran, and "end" halted the run loop -- not "mov %2,7".
        assertEquals("1", engine.getVariablesSnapshot().get("%3"));
        assertEquals("99", engine.getVariablesSnapshot().get("%2"));
        assertTrue(listener.exitedToLibrary);
    }

    @Test
    public void reshowLastChoiceMenuRestoresASelectMenuAfterSystemcallLoadFindsNothing() throws IOException {
        // e.g. a title screen's "New game"/"Load" select: picking "Load" runs "systemcall load",
        // which pauses the engine (see NsCommandDispatcher's handler); if the host then finds no
        // save data, it calls reshowLastChoiceMenu() instead of leaving the engine stuck at
        // WAITING_TAP with the choice buttons already gone from screen.
        write(String.join("\n",
                "*start",
                "select \"New game\",*newgame,\"Load\",*loadgame",
                "*newgame",
                "mov %1,1",
                "goto *done",
                "*loadgame",
                "systemcall load",
                "*done",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(2, listener.lastChoices.size());

        engine.choose(1); // "Load" -> *loadgame -> "systemcall load" pauses at WAITING_TAP
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertTrue(listener.loadMenuRequested);

        assertTrue(engine.reshowLastChoiceMenu()); // host found nothing to load
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(2, listener.lastChoices.size());
        assertEquals("New game", listener.lastChoices.get(0));

        engine.choose(0); // the restored menu still dispatches correctly afterward
        assertEquals("1", engine.getVariablesSnapshot().get("%1"));
        assertEquals("The end", listener.textLines.get(listener.textLines.size() - 1));
    }

    @Test
    public void snapshotRoundTripsAChoiceMenuIntoABrandNewEngineInstance() throws IOException {
        // Covers saving/loading while a choice menu is on screen: snapshotState()/
        // restoreFromSnapshot() must carry the lastChoice* fields through a save/load round-trip
        // (a fresh NsExecState otherwise has none of this), so ReaderActivity can call
        // reshowLastChoiceMenu() after restoring into a NEW engine instance -- simulating an app
        // restart, unlike reshowLastChoiceMenuRestoresASelectMenuAfterSystemcallLoadFindsNothing
        // above, which reshows within the SAME instance that never lost its state.
        write(String.join("\n",
                "*start",
                "select \"Go left\",*left,\"Go right\",*right",
                "*left",
                "mov %1,1",
                "goto *done",
                "*right",
                "mov %1,2",
                "*done",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        NsScriptEngine.Snapshot snapshot = engine.snapshotState();

        FakeListener freshListener = new FakeListener();
        NsScriptEngine freshEngine = new NsScriptEngine(tmp.getRoot(), freshListener, new java.util.HashMap<>());
        freshEngine.restoreFromSnapshot(snapshot); // leaves WAITING_TAP, same as a plain-tap restore
        assertEquals(VnEngine.State.WAITING_TAP, freshEngine.getState());

        assertTrue(freshEngine.reshowLastChoiceMenu());
        assertEquals(VnEngine.State.WAITING_CHOICE, freshEngine.getState());
        assertEquals(2, freshListener.lastChoices.size());
        assertEquals("Go right", freshListener.lastChoices.get(1));

        freshEngine.choose(1); // "Go right" -> *right -> mov %1,2 -> falls through to *done
        assertEquals("2", freshEngine.getVariablesSnapshot().get("%1"));
        assertEquals("The end", freshListener.textLines.get(freshListener.textLines.size() - 1));
    }

    @Test
    public void midLineMarkersPauseWithinASingleScriptLineBeforeMovingOn() throws IOException {
        // A single line can pause twice mid-sentence
        // before its own trailing '\' finally ends the page and moves to the next script line.
        write(String.join("\n",
                "*start",
                "One@ two@ three\\",
                "Next line\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals("One", listener.textLines.get(0));
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals(0, listener.textClears); // '@' keeps the page

        engine.resumeFromTap(); // still on the same script line: appended, not a new line
        assertEquals(" two", listener.textAppends.get(0));
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals(0, listener.textClears);

        engine.resumeFromTap(); // hits the trailing '\': ends the page
        assertEquals(" three", listener.textAppends.get(1));
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());

        engine.resumeFromTap(); // clears, then moves on to "Next line\"
        assertEquals(1, listener.textClears);
        assertEquals("Next line", listener.textLines.get(1));
    }

    @Test
    public void btnwaitChoiceSetsVariableAndFallsThroughRatherThanJumping() throws IOException {
        // ONScripter-EN's title-screen button pattern:
        // unlike "select", choosing a "btnwait" option never jumps -- it stores the button id and
        // continues to the very next line.
        write(String.join("\n",
                "*start",
                "lsp 1,\":s/36,38,0;#FFFFFF#a9a9a9`Start game\",565,430",
                "lsp 2,\":s/36,38,0;#FFFFFF#a9a9a9`Continue game\",542,470",
                "spbtn 1,1",
                "spbtn 2,2",
                "btnwait %1",
                "mov %2,99",
                "if %1 == 1 goto *pt1",
                "goto *loop",
                "*pt1",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(java.util.Arrays.asList("Start game", "Continue game"), listener.lastChoices);

        engine.choose(0); // "Start game" -> button id 1
        assertEquals("1", engine.getVariablesSnapshot().get("%1"));
        assertEquals("99", engine.getVariablesSnapshot().get("%2")); // fell through to "mov %2,99"
        assertEquals("The end", listener.textLines.get(0));
    }

    @Test
    public void gameJumpsToStartSkippingOverAnyUtilitySubroutinesInBetween() throws IOException {
        // Real ONScripter-EN's "game" command isn't just a mode-flag flip -- it unconditionally
        // jumps, the exact same mechanism "goto" uses. Scripts
        // commonly rely on
        // this to skip over a long run of "defsub"-declared utility subroutines physically sitting
        // between "game" and its own "*start" label, far down the file -- without this jump, a
        // fresh playthrough would instead fall through those subroutine bodies sequentially and
        // never reach the real story content at all.
        write(String.join("\n",
                "*define",
                "game",
                "*utility",
                "mov %1,999", // must NOT run: only reachable via fallthrough, which "game" must skip
                "return",
                "*start",
                "mov %2,1",
                "Real story\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("1", engine.getVariablesSnapshot().get("%2"));
        assertNull(engine.getVariablesSnapshot().get("%1")); // "*utility" body never ran
        assertEquals("Real story", listener.textLines.get(0));
    }

    @Test
    public void aliasesDeclaredInTheDefineHeaderAreRegisteredBeforePlayReachesThem() throws IOException {
        // Real scripts commonly put "numalias"/"stralias" in the "*define"
        // section before "game" -- startPc lands at the "*define" label itself (see
        // NsScript#startPc's doc), so normal execution runs through the header exactly like real
        // NScripter does, registering these along the way.
        write(String.join("\n",
                "*define",
                "numalias money,5",
                "stralias bgcoffee,\"data\\bg_coffee.png\"",
                "game",
                "*start",
                "mov %money,42",
                "bg bgcoffee,10",
                "The end\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("42", engine.getVariablesSnapshot().get("%5")); // numalias-registered slot
        assertEquals(new File(tmp.getRoot(), "data/bg_coffee.png"), listener.lastBackground);
    }

    @Test
    public void aDeclarationReachableOnlyViaGosubInsideTheHeaderStillRegisters() throws IOException {
        // A real script's "*define" section can have its very first line be
        // "gosub *sys_define", a subroutine positioned physically AFTER "game" that registers
        // several "numalias" declarations before "return"ing back into the header. A flat scan over
        // "the lines before startPc" (an earlier, less correct fix) would miss this entirely, since
        // the subroutine body itself sits past that point -- only genuinely running the header
        // (following its own gosub/return like any other control flow) reaches it.
        write(String.join("\n",
                "*define",
                "gosub *setup",
                "game",
                "*start",
                "mov %money,7",
                "The end\\",
                "",
                "*setup",
                "numalias money,9",
                "return",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("7", engine.getVariablesSnapshot().get("%9")); // numalias-registered slot
    }

    @Test
    public void barewordConstantsSurviveASnapshotRoundTrip() throws IOException {
        // Unlike a fresh start() (which reaches "stralias" by actually running the "*define"
        // header), restoreFromSnapshot repositions without re-running anything -- so this has to be
        // captured in the Snapshot itself, the same way numAliases/strAliases already are.
        write(String.join("\n",
                "*define",
                "stralias bgcoffee,\"data\\bg_coffee.png\"",
                "game",
                "*start",
                "The end\\",
                "bg bgcoffee,10",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start(); // stops at "The end\"
        NsScriptEngine.Snapshot snapshot = engine.snapshotState();
        assertEquals("data\\bg_coffee.png", snapshot.barewordConstants.get("bgcoffee"));

        NsScriptEngine restored = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        restored.restoreFromSnapshot(snapshot); // a fresh engine: never itself ran "*define"
        restored.resumeFromTap(); // runs "bg bgcoffee,10" then "Done\"
        assertEquals(new File(tmp.getRoot(), "data/bg_coffee.png"), listener.lastBackground);
    }

    @Test
    public void gosubReturnsToTheLineAfterTheCall() throws IOException {
        write(String.join("\n",
                "*start",
                "gosub *greet",
                "mov %2,1",
                "goto *fin",
                "*greet",
                "mov %1,7",
                "return",
                "*fin",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("7", engine.getVariablesSnapshot().get("%1"));
        assertEquals("1", engine.getVariablesSnapshot().get("%2"));
        assertEquals("Done", listener.textLines.get(0));
    }

    @Test
    public void isPageEndPendingPeeksNextDialogueLineWithoutConsumingIt() throws IOException {
        write(String.join("\n",
                "*start",
                "First\\",
                "Second\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertTrue(engine.isPageEndPending()); // next line ("Second\") is itself a pagewait line
    }

    @Test(timeout = 10_000)
    public void anUnboundedGotoLoopWithNoBlockingCommandEventuallyReturnsControlToTheHost() throws IOException {
        // A script relying on an unimplemented blocking command (real example: "btnwait") inside
        // what's meant to be an interactive loop must not hang the calling thread forever -- see
        // NsScriptEngine.MAX_STEPS_PER_RESUME. "mov"/"goto" are both implemented and non-blocking,
        // so without the safety valve this would spin indefinitely (the JUnit @Test timeout above
        // is a backstop in case the valve itself regresses, not the mechanism under test).
        write(String.join("\n",
                "*loop",
                "mov %1,1",
                "goto *loop",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("1", engine.getVariablesSnapshot().get("%1"));
    }

    @Test
    public void btnwaitWithNoButtonsBlocksThenResolvesToNegativeOneOnTap() throws IOException {
        // Real ONScripter's "btnwait"/"selectbtnwait" always blocks for a click, even with zero
        // registered buttons. A click that misses
        // every button still resolves to a real value (-1), commonly handled via something like
        // "if %BtnRes=-1 ..." for exactly this. Before this was fixed, an empty button list
        // made "btnwait" a silent no-op that kept running instead of blocking at all.
        write(String.join("\n",
                "*start",
                "btnwait %1",
                "if %1 == -1 goto *missed",
                "Hit\\",
                "goto *done",
                "*missed",
                "Missed\\",
                "*done",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState()); // blocked, no buttons registered
        assertTrue(listener.textLines.isEmpty()); // nothing shown yet -- still waiting on the tap

        engine.resumeFromTap();
        assertEquals("-1", engine.getVariablesSnapshot().get("%1"));
        assertEquals("Missed", listener.textLines.get(0));
    }

    @Test
    public void cselDeclaresOptionsCselbtnRegistersThemAndCselgotoJumps() throws IOException {
        // The real custom-select idiom: "csel" declares (text,label) pairs and jumps to
        // "*customsel"; the script lays out one "cselbtn" per option (bounded by "getcselnum");
        // "selectbtnwait" blocks for a click; and "cselgoto" jumps to the clicked option's label,
        // using an index the script itself computes from whatever id "cselbtn" assigned.
        write(String.join("\n",
                "*start",
                "csel \"Go left\",*left,\"Go right\",*right",
                "*customsel",
                "getcselnum %1",
                "cselbtn 0,500,10,10",
                "cselbtn 1,501,10,40",
                "selectbtnwait %2",
                "sub %2,500",
                "cselgoto %2",
                "*left",
                "mov %3,1",
                "goto *done",
                "*right",
                "mov %3,2",
                "*done",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        engine.start();
        assertEquals("2", engine.getVariablesSnapshot().get("%1")); // getcselnum: 2 declared options
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(java.util.Arrays.asList("Go left", "Go right"), listener.lastChoices);

        engine.choose(1); // "Go right" -> button id 501 -> sub 500 -> index 1 -> cselgoto -> *right
        assertEquals("2", engine.getVariablesSnapshot().get("%3"));
        assertEquals("Done", listener.textLines.get(0));
    }

    @Test
    public void forNextLoopsTheBodyOncePerStep() throws IOException {
        // Real ONScripter-EN's "for VAR=FROM to TO [step STEP]" / "next" -- e.g.
        // "for %0=701 to 709 ... csp %0 ... next" is a common idiom used to bulk-clear a numbered
        // sprite range.
        // Before this was implemented, the loop body ran exactly once (with whatever value its
        // variable already held) instead of once per step.
        write(String.join("\n",
                "*start",
                "mov %2,0",
                "for %1=1 to 3",
                "add %2,%1",
                "next",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("6", engine.getVariablesSnapshot().get("%2")); // 1+2+3
        assertEquals("4", engine.getVariablesSnapshot().get("%1")); // one past the last step
        assertEquals("Done", listener.textLines.get(0));
    }

    @Test
    public void forWithAnEmptyRangeStillRunsTheBodyOnceButNeverLoopsBack() throws IOException {
        // Real ONScripter-EN's "for" never jumps anywhere itself -- it just sets the loop variable
        // and pushes a frame, so ordinary sequential execution always runs the body at least once;
        // it's "next" that decides whether to loop back, and an "empty" range just means the FIRST
        // "next" pops the frame
        // instead of looping, not that the body is skipped entirely.
        write(String.join("\n",
                "*start",
                "mov %2,0",
                "for %1=5 to 1",
                "add %2,1",
                "next",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("1", engine.getVariablesSnapshot().get("%2")); // ran once, never looped back
    }

    @Test
    public void forWithANegativeStepCountsDown() throws IOException {
        write(String.join("\n",
                "*start",
                "mov %2,0",
                "for %1=3 to 1 step -1",
                "add %2,%1",
                "next",
                "Done\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());
        engine.start();
        assertEquals("6", engine.getVariablesSnapshot().get("%2")); // 3+2+1
    }

    @Test
    public void restoreStateRepositionsWithoutRerunningSkippedCommands() throws IOException {
        write(String.join("\n",
                "*start",
                "mov %1,1",
                "One\\",
                "mov %1,2",
                "Two\\",
                ""));
        FakeListener listener = new FakeListener();
        NsScriptEngine engine = new NsScriptEngine(tmp.getRoot(), listener, new java.util.HashMap<>());

        java.util.Map<String, String> savedVars = new java.util.HashMap<>();
        savedVars.put("%1", "99");
        engine.restoreState("0.txt", 4, savedVars); // land right on "Two\" without running "mov %1,2"
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        assertEquals("99", engine.getVariablesSnapshot().get("%1"));

        engine.resumeFromTap();
        assertEquals("Two", listener.textLines.get(0));
    }
}
