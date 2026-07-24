package com.example.vndsandroideink;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Opt-in real-sample test: automatically skipped (not failed) when the real sample isn't staged
 * locally -- see the manual pre-req note below and .gitignore.
 *
 * <p>Milestone-4 acceptance test: launches a real sample game ("a_dream_of_summer", kept locally and
 * pushed to /data/local/tmp by the harness -- see the milestone's manual verification notes) end
 * to end through the actual ReaderActivity UI, using the same dev-toggle auto-detection real
 * players would hit. This is a large, genuinely messy real script (13000+ lines, an obfuscated
 * "nscript.dat", assets only present inside "arc.nsa") exercising things a hand-written test
 * script can't: real NsObfuscation decoding, real NsArchiveReader/NsAssetResolver extraction, and
 * NsScriptEngine.MAX_STEPS_PER_RESUME's safety valve against this script's own custom menu-loop
 * pattern (see that constant's doc). The bar here is "doesn't crash and stays responsive," not
 * "plays the story correctly" -- this game's choices/menus are built almost entirely outside the
 * core opcode subset (verified: it never calls "select" at all), so full playability isn't
 * expected yet. Its "defsub"-declared pseudo-commands (e.g. "change_b") and their "getparam"
 * argument-passing ARE now supported (see NsCommandDispatcher's "defsub"/"getparam" handlers).
 */
@RunWith(AndroidJUnit4.class)
public class NsRealGameSmokeTest {

    /** Manual pre-req, not part of the repo (the archive alone is 115MB): push the real sample's
     * nscript.dat/arc.nsa/default.ttf to /data/local/tmp before running this test. Routine full
     * suite runs skip it gracefully via assumeTrue below rather than failing when that staging
     * step hasn't been done -- the other real-sample tests (NsObfuscationTest, NsArchiveReaderTest,
     * NsAssetResolverTest) read straight from a locally-kept sample pack instead and don't
     * need this, but this one needs the files inside the app's own sandbox to exercise the real
     * ReaderActivity/VnImporter-style file layout, hence the staging step. */
    @Test
    public void theRealSampleGameLaunchesAndStaysResponsive() throws IOException {
        assumeTrue("Real sample not staged at /data/local/tmp -- see this test's class doc",
                new File("/data/local/tmp/nscript.dat").isFile()
                        && new File("/data/local/tmp/arc.nsa").isFile());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File vnDir = new File(context.getFilesDir(), "vns/dream_of_summer_it");
        vnDir.mkdirs();
        copy(new File("/data/local/tmp/nscript.dat"), new File(vnDir, "nscript.dat"));
        copy(new File("/data/local/tmp/arc.nsa"), new File(vnDir, "arc.nsa"));
        copy(new File("/data/local/tmp/default.ttf"), new File(vnDir, "default.ttf"));

        Intent intent = new Intent(context, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_VN_DIR, vnDir.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_VN_TITLE, "A Dream of Summer (real sample)");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent)) {
            // engine.start() already ran synchronously during onCreate() by the time launch()
            // returns; reaching this line at all (no crash dialog, no ActivityNotFoundException,
            // no exception propagated from onCreate) is itself the primary thing under test.
            final boolean[] sceneVisible = {false};
            scenario.onActivity(activity -> {
                View scene = activity.findViewById(R.id.sceneContainer);
                sceneVisible[0] = scene != null && scene.getVisibility() == View.VISIBLE;
            });
            assertTrue("expected the scene container to be visible after launch", sceneVisible[0]);

            // Exercise a few real user interactions (tap-to-advance) to confirm the activity
            // stays responsive afterward too, not just at the initial launch instant.
            for (int i = 0; i < 5; i++) {
                onView(withId(R.id.tapCatcher)).perform(click());
            }
            onView(withId(R.id.tapCatcher)).check((view, exception) -> {
                if (exception != null) {
                    throw exception;
                }
                assertTrue(view.isShown());
            });
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
