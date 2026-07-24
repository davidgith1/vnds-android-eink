package io.github.davidgith1.vndsandroideink;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;
import io.github.davidgith1.vndsandroideink.nscripter.NsSaveManager;
import io.github.davidgith1.vndsandroideink.nscripter.NsScriptEngine;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Covers two reader UI bugs, both about a modal being dismissed without losing what was on screen
 * before it opened: the hardware/gesture back button skipping the "confirm before leaving if not
 * resumable" warning the menu's own Library row already has, and canceling a "systemcall load"
 * dialog (opened from inside a script's own select menu) losing that choice menu.
 */
@RunWith(AndroidJUnit4.class)
public class ReaderActivityBackAndLoadCancelTest {

    private File vnDir;

    @After
    public void tearDown() {
        if (vnDir != null) {
            SaveManager.deleteAll(InstrumentationRegistry.getInstrumentation().getTargetContext(), vnDir.getName());
        }
    }

    @Test
    public void hardwareBackShowsConfirmDialogWhenNotResumable() throws IOException {
        // A "select" menu is on screen (WAITING_CHOICE, not WAITING_TAP) -- not a resumable moment,
        // so back should warn before leaving, the same way the menu's own Library row already does.
        String script = String.join("\n",
                "*start",
                "select \"A\",*a,\"B\",*b",
                "*a",
                "End A\\",
                "*b",
                "End B\\",
                "");
        Intent intent = launchIntent("back_confirm", script);

        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.choicesPanel)).check(matches(isDisplayed()));

            pressBack();

            onView(withText(R.string.confirm_library_title)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void cancelingSystemcallLoadDialogRedisplaysChoiceMenu() throws IOException {
        // "systemcall load" is reached from inside a select menu's own target -- verify canceling
        // the resulting Load dialog (with save data present, so it actually opens rather than
        // short-circuiting to the "nothing to load" toast) redisplays that same choice menu instead
        // of leaving the reader stuck with no choice buttons on screen.
        String script = String.join("\n",
                "*start",
                "select \"Play\",*play,\"Load game\",*loadit",
                "*play",
                "Playing\\",
                "*loadit",
                "systemcall load",
                "Should not be reached immediately\\",
                "");
        Intent intent = launchIntent("load_cancel", script);
        seedOccupiedSaveSlot();

        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withText("Play")).check(matches(isDisplayed()));
            onView(withText("Load game")).perform(click());

            // The systemcall-load dialog is now open; cancel it via its own Close button.
            onView(withText(R.string.close)).perform(click());

            // The original choice menu must be back on screen, not a blank/stuck reader.
            onView(withText("Play")).check(matches(isDisplayed()));
            onView(withText("Load game")).check(matches(isDisplayed()));
        }
    }

    private Intent launchIntent(String label, String script) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        vnDir = new File(context.getFilesDir(), "vns/ns_" + label + "_it_" + System.nanoTime());
        vnDir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(vnDir, "0.txt"))) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
        }
        Intent intent = new Intent(context, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_VN_DIR, vnDir.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_VN_TITLE, "Back/Load Cancel Test");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Seeds manual save slot 1 so {@code ReaderActivity.openLoadDialog} finds occupied save data
     * and actually opens {@link SaveSlotDialog} instead of short-circuiting to the "nothing to
     * load" toast (the branch that already worked correctly before this fix). */
    private void seedOccupiedSaveSlot() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NsScriptEngine scratch = new NsScriptEngine(vnDir, new NoOpListener(), new HashMap<>());
        scratch.start();
        NsSaveManager.save(context, vnDir.getName(), 1, scratch, null,
                VnEngine.SpriteTransparency.OPAQUE, 1, null, new ArrayList<>(), "",
                new ArrayList<>());
    }

    private static final class NoOpListener implements VnEngine.Listener {
        @Override public void onSpeaker(String name) { }
        @Override public void onTextLine(String line) { }
        @Override public void onTextAppend(String moreText) { }
        @Override public void onTextClear() { }
        @Override public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) { }
        @Override public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) { }
        @Override public void onSpriteCleared(int layer) { }
        @Override public void onSound(File soundFileOrNull, int times) { }
        @Override public void onMusic(File musicFileOrNull) { }
        @Override public void onChoices(java.util.List<String> options) { }
        @Override public void onDelay(int frames) { }
        @Override public void onGlobalsChanged(java.util.Map<String, String> globals) { }
        @Override public void onFinished() { }
        @Override public void onExitToLibrary() { }
        @Override public void onLoadMenuRequested() { }
    }
}
