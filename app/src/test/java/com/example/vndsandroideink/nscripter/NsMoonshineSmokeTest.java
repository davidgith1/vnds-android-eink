package com.example.vndsandroideink.nscripter;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.example.vndsandroideink.engine.VnEngine;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in real-sample test: automatically skipped (not failed) when no local sample pack is
 * present -- see .gitignore.
 *
 * <p>JVM-level real-game smoke test for a real "Moonshine" NScripter sample kept locally for manual
 * testing (a plain-text "0.txt" + "arc.nsa"
 * NScripter pack -- unlike "a_dream_of_summer", which needs manual
 * /data/local/tmp staging for {@code NsRealGameSmokeTest} since it's too big to keep loaded that way,
 * Moonshine is small enough to read straight off disk, the same way {@code NsObfuscationTest}/
 * {@code NsArchiveReaderTest} do). Drives {@link NsScriptEngine} directly (no Activity/UI), advancing
 * through taps and cycling through offered choices, purely to surface real crashes/exceptions in a
 * large, genuinely-authored script this project's hand-written unit tests can't exercise. The bar is
 * "doesn't throw and terminates," not "plays the story correctly."
 *
 * <p>Tracing this test against the real script (before the fixes below existed) surfaced four real
 * dispatcher bugs, all now fixed in {@code NsCommandDispatcher}: (1) a compound "if A & B ..."
 * condition -- real NScripter syntax for chaining comparisons, used 38 times in this one sample --
 * was misparsed, showing its own script text as garbage dialogue in a tight loop; (2) the
 * underscore-escaped "_name" convention (calling a command's true native implementation from inside a
 * "defsub" wrapper of the same name) wasn't recognized as a command at all, so lines like
 * "_bgm $SoundFileName" were shown as literal dialogue instead of running; (3) {@code
 * resolveFileArg} never actually evaluated a "$var" file argument, returning the bare variable NAME
 * text instead of its stored value; (4) "numalias"-declared names weren't mirrored into the string-
 * variable alias table, so every "$name" reference for a numalias-only name (this sample never calls
 * "stralias" at all) collided onto slot 0.
 *
 * <p>One further gap remains, deliberately left alone: Moonshine's custom-select menu system
 * ("cselbtn"/"selectbtnwait", already implemented) depends on "cselgoto" to jump to the clicked
 * option's own label -- "cselgoto" itself is unimplemented (its real jump-target semantics aren't
 * verifiable without reading ONScripter-EN's own source), so after any choice the script loops back to
 * "*csel_lp" and "selectbtnwait" no-ops (no buttons re-registered) instead of blocking again. This is
 * the same class of already-accepted limitation {@code NsRealGameSmokeTest} documents for
 * "a_dream_of_summer" -- {@link NsScriptEngine#runLoop}'s own step-count safety valve means this
 * never actually hangs the host, it just stops making narrative progress.
 */
public class NsMoonshineSmokeTest {

    private static final File REAL_SAMPLE_DIR = findRealSampleDir();

    private static File findRealSampleDir() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "Onscripter examples/Moonshine");
            if (new File(candidate, "0.txt").isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static final class RecordingListener implements VnEngine.Listener {
        final List<String> log = new ArrayList<>();
        String lastSpeaker;

        private void note(String s) {
            log.add(s);
            if (log.size() > 40) {
                log.remove(0);
            }
        }

        @Override public void onSpeaker(String name) { lastSpeaker = name; note("speaker: " + name); }
        @Override public void onTextLine(String line) { note("text: " + line); }
        @Override public void onTextAppend(String moreText) { note("+text: " + moreText); }
        @Override public void onTextClear() { note("clear"); }
        @Override public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
            note("bg: " + imageFile);
        }
        @Override public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
            note("sprite[" + layer + "]: " + imageFile);
        }
        @Override public void onSpriteCleared(int layer) { note("spriteCleared: " + layer); }
        @Override public void onSound(File soundFileOrNull, int times) { note("sound: " + soundFileOrNull); }
        @Override public void onMusic(File musicFileOrNull) { note("music: " + musicFileOrNull); }
        @Override public void onChoices(List<String> options) { note("choices: " + options); }
        @Override public void onDelay(int frames) { note("delay: " + frames); }
        @Override public void onGlobalsChanged(Map<String, String> globals) { }
        @Override public void onFinished() { note("finished"); }
        @Override public void onExitToLibrary() { note("exitToLibrary"); }
        @Override public void onLoadMenuRequested() { note("loadMenuRequested"); }
    }

    @Test
    public void theRealSampleGameRunsWithoutThrowing() {
        assumeTrue("Moonshine sample not found relative to the test working directory", REAL_SAMPLE_DIR != null);

        RecordingListener listener = new RecordingListener();
        NsScriptEngine engine = new NsScriptEngine(REAL_SAMPLE_DIR, listener, new HashMap<>());

        // Bounded low enough to run in a few seconds even in the worst case (each resume can burn a
        // full NsScriptEngine.MAX_STEPS_PER_RESUME before yielding back, ~100ms observed) -- see the
        // "cselgoto" limitation in this class's own doc for why that worst case is expected to be hit
        // repeatedly here, not a sign of a new problem. Cycles through offered choices (rather than
        // always picking the first) so different runs exercise different branches.
        int maxSteps = 200;
        int steps = 0;
        int choiceCounter = 0;
        try {
            engine.start();
            while (engine.getState() != VnEngine.State.FINISHED && steps < maxSteps) {
                steps++;
                switch (engine.getState()) {
                    case WAITING_TAP:
                        engine.resumeFromTap();
                        break;
                    case WAITING_CHOICE:
                        engine.choose(choiceCounter++ % 5);
                        break;
                    case WAITING_DELAY:
                        engine.resumeFromDelay();
                        break;
                    case RUNNING:
                    case FINISHED:
                        break;
                }
            }
        } catch (RuntimeException e) {
            throw new AssertionError("Moonshine threw at step " + steps + ", pc=" + engine.getPc()
                    + "\nrecent log:\n" + String.join("\n", listener.log), e);
        }

        assertTrue("expected at least some dialogue to have been shown", !listener.log.isEmpty());
    }
}
