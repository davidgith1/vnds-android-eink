package io.github.davidgith1.vndsandroideink.vnds;

import static org.junit.Assert.assertEquals;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers the "save/load while a choice is on screen" path added to support persisting a
 * WAITING_CHOICE pause -- {@link ScriptEngine#restoreStateAtChoice} is the counterpart to {@link
 * ScriptEngine#restoreState} used when the engine was mid-choice at save time (see {@code
 * ReaderActivity#saveToSlot}/{@code #loadFromSlot}).
 */
public class ScriptEngineChoiceSaveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void writeScript(String name, String content) throws IOException {
        File scriptDir = new File(tmp.getRoot(), "script");
        scriptDir.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(scriptDir, name)), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    /** Minimal listener: only records what this test needs (choices shown, text lines seen). */
    private static final class RecordingListener implements VnEngine.Listener {
        final List<List<String>> choiceCalls = new ArrayList<>();
        final List<String> textLines = new ArrayList<>();

        @Override public void onSpeaker(String name) { }
        @Override public void onTextLine(String line) { textLines.add(line); }
        @Override public void onTextAppend(String moreText) { }
        @Override public void onTextClear() { }
        @Override public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) { }
        @Override public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) { }
        @Override public void onSpriteCleared(int layer) { }
        @Override public void onSound(File soundFileOrNull, int times) { }
        @Override public void onMusic(File musicFileOrNull) { }
        @Override public void onChoices(List<String> options) { choiceCalls.add(new ArrayList<>(options)); }
        @Override public void onDelay(int frames) { }
        @Override public void onGlobalsChanged(Map<String, String> globals) { }
        @Override public void onFinished() { }
        @Override public void onExitToLibrary() { }
        @Override public void onLoadMenuRequested() { }
    }

    @Test
    public void restoreStateAtChoice_redisplaysMenuAndRoutesTheChosenOption() throws IOException {
        writeScript("main.scr", String.join("\n",
                "text First line",
                "choice Go left|Go right",
                "if selected == 1",
                "text You went left",
                "fi",
                "if selected == 2",
                "text You went right",
                "fi",
                ""));
        RecordingListener listener = new RecordingListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());
        engine.start();
        assertEquals(VnEngine.State.WAITING_TAP, engine.getState());
        engine.resumeFromTap(); // advances past "text First line" into "choice ..."
        assertEquals(VnEngine.State.WAITING_CHOICE, engine.getState());
        assertEquals(List.of("Go left", "Go right"), listener.choiceCalls.get(0));

        // Simulate what a save slot captures mid-choice, and load it into a brand new engine
        // instance (a fresh process, same as a real app restart) instead of resuming this one.
        String savedFile = engine.getCurrentFile();
        int savedPc = engine.getPc();
        Map<String, String> savedVars = engine.getVariablesSnapshot();
        List<String> savedOptions = listener.choiceCalls.get(0);

        RecordingListener freshListener = new RecordingListener();
        ScriptEngine freshEngine = new ScriptEngine(tmp.getRoot(), freshListener, new HashMap<>());
        freshEngine.restoreStateAtChoice(savedFile, savedPc, savedVars, savedOptions);

        assertEquals(VnEngine.State.WAITING_CHOICE, freshEngine.getState());
        assertEquals(List.of("Go left", "Go right"), freshListener.choiceCalls.get(0));

        freshEngine.choose(1); // zero-based: "Go right"
        assertEquals(List.of("You went right"), freshListener.textLines);
    }
}
