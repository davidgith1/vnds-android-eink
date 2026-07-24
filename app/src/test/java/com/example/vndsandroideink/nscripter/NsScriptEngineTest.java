package com.example.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.vndsandroideink.engine.VnEngine;

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
