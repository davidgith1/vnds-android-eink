package io.github.davidgith1.vndsandroideink;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Milestone-5 acceptance test: an NScripter playthrough saved via the resume-slot mechanism (the
 * automatic snapshot {@code ReaderActivity.onPause()} takes -- the same real path a user hits by
 * backgrounding the app or returning to the library, and the one {@code MainActivity}'s "Resume"
 * option will eventually drive once real detection lands) survives a full activity teardown and
 * restarts with matching engine and visual state, through the actual ReaderActivity UI.
 */
@RunWith(AndroidJUnit4.class)
public class NsSaveLoadReaderActivityTest {

    private static final String SCRIPT = String.join("\n",
            "*start",
            "mov %1,0",
            "select \"Say yes\",*yes,\"Say no\",*no",
            "*yes",
            "mov %1,1",
            "goto *done",
            "*no",
            "mov %1,2",
            "*done",
            "if %1==1 Yes was picked!\\",
            "if %1==2 No was picked!\\",
            "The end.\\",
            "");

    private File vnDir;

    @After
    public void tearDown() {
        if (vnDir != null) {
            SaveManager.deleteAll(InstrumentationRegistry.getInstrumentation().getTargetContext(), vnDir.getName());
        }
    }

    @Test
    public void resumeSnapshotSurvivesATeardownAndRestoresEngineAndVisualState() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        vnDir = new File(context.getFilesDir(), "vns/ns_save_load_it_" + System.nanoTime());
        vnDir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(vnDir, "0.txt"))) {
            out.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
        }

        Intent intent = new Intent(context, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_VN_DIR, vnDir.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_VN_TITLE, "NS Save/Load Test");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // First run: advance into the "select", pick "Say yes", land on "Yes was picked!"
        // (WAITING_TAP), then tear the activity down -- onPause() auto-saves the resume slot
        // since canResumeNow() holds at a WAITING_TAP.
        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.tapCatcher)).perform(click()); // "select" appears
            onView(withText("Say yes")).perform(click());
            onView(withId(R.id.bodyText)).check(matches(withText(containsString("Yes was picked"))));
        }
        // ActivityScenario's try-with-resources close() finishes/destroys the activity, running
        // onPause() -> the real resume-snapshot path -- not a call directly into SaveManager.

        assertResumeSlotWasSaved(context);

        // Second run: relaunch pointed at the resume slot explicitly (the same extra
        // MainActivity's "Resume" launch-chooser option would pass).
        Intent resumeIntent = new Intent(context, ReaderActivity.class);
        resumeIntent.putExtra(ReaderActivity.EXTRA_VN_DIR, vnDir.getAbsolutePath());
        resumeIntent.putExtra(ReaderActivity.EXTRA_VN_TITLE, "NS Save/Load Test");
        resumeIntent.putExtra(ReaderActivity.EXTRA_LOAD_SLOT, SaveManager.SLOT_RESUME);
        resumeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(resumeIntent)) {
            // Visual state restored directly from the saved body lines, without re-running anything.
            onView(withId(R.id.bodyText)).check(matches(withText(containsString("Yes was picked"))));

            // Tapping continues the ENGINE from the restored pc, not just the visuals: if pc/vars
            // had been restored wrong, this wouldn't land on "The end." next.
            onView(withId(R.id.tapCatcher)).perform(click());
            onView(withId(R.id.bodyText)).check(matches(withText(containsString("The end"))));
        }
    }

    private void assertResumeSlotWasSaved(Context context) {
        boolean hasResume = io.github.davidgith1.vndsandroideink.nscripter.NsSaveManager
                .hasResume(context, vnDir.getName());
        org.junit.Assert.assertTrue("expected onPause() to have auto-saved the NScripter resume slot", hasResume);
    }
}
