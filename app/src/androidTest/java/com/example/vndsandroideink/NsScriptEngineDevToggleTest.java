package com.example.vndsandroideink;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * End-to-end check of {@code ReaderActivity}'s dev-toggle NScripter wiring (see
 * {@code ReaderActivity#initEngine}): a plain-text script with no VNDS {@code img.ini}/{@code
 * script/} folder should auto-select {@code NsScriptEngine} and play normally through the same
 * UI VNDS packs use -- dialogue taps, page clears, and a "select" choice.
 */
@RunWith(AndroidJUnit4.class)
public class NsScriptEngineDevToggleTest {

    private static final String SCRIPT = String.join("\n",
            "*start",
            "mov %1,0",
            "Welcome to the milestone 3 test script.\\",
            "@This line should not clear the page.",
            "Nice to meet you.\\",
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

    @Test
    public void playsThroughAHandWrittenNScripterScript() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File vnDir = new File(context.getFilesDir(), "vns/nstest_it");
        vnDir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(vnDir, "0.txt"))) {
            out.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
        }

        Intent intent = new Intent(context, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_VN_DIR, vnDir.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_VN_TITLE, "NS Dev-Toggle Test");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.bodyText)).check(matches(
                    withText(containsString("Welcome to the milestone 3 test script"))));

            onView(withId(R.id.tapCatcher)).perform(click()); // clears page, shows the "@..." line
            onView(withId(R.id.bodyText)).check(matches(
                    withText(containsString("This line should not clear the page"))));

            onView(withId(R.id.tapCatcher)).perform(click()); // auto-continues into "Nice to meet you.\"
            onView(withId(R.id.bodyText)).check(matches(withText(containsString("Nice to meet you"))));

            onView(withId(R.id.tapCatcher)).perform(click()); // clears page, advances into "select"
            onView(withId(R.id.choicesPanel)).check(matches(isDisplayed()));

            onView(withText("Say yes")).perform(click());
            onView(withId(R.id.bodyText)).check(matches(withText(containsString("Yes was picked"))));
        }
    }
}
