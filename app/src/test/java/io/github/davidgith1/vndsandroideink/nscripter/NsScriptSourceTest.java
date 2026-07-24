package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class NsScriptSourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String name, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(tmp.getRoot(), name)), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    @Test
    public void noScriptFoundYieldsEmptyResult() {
        assertFalse(NsScriptSource.hasPlainTextScript(tmp.getRoot()));
        NsScript script = NsScriptSource.load(tmp.getRoot());
        assertTrue(script.lines.isEmpty());
        assertTrue(script.labelIndex.isEmpty());
    }

    @Test
    public void loadsSinglePrimaryFile() throws IOException {
        write("0.txt", "*start\nHello\\\ngoto *start\n");
        assertTrue(NsScriptSource.hasPlainTextScript(tmp.getRoot()));
        NsScript script = NsScriptSource.load(tmp.getRoot());
        assertEquals(3, script.lines.size());
        assertEquals(0, (int) script.labelIndex.get("start"));
    }

    @Test
    public void concatenatesNumberedContinuationFilesInNumericOrder() throws IOException {
        write("0.txt", "*start\n");
        write("2.txt", "second\n");
        write("10.txt", "tenth\n");
        write("1.txt", "first\n");
        NsScript script = NsScriptSource.load(tmp.getRoot());
        // "*start" (0.txt) then 1.txt, 2.txt, 10.txt in that numeric order -- not lexicographic
        // (which would wrongly put "10.txt" before "2.txt").
        assertEquals(4, script.lines.size());
        assertEquals("first", script.lines.get(1));
        assertEquals("second", script.lines.get(2));
        assertEquals("tenth", script.lines.get(3));
    }

    @Test
    public void utfVariantContinuationsDontMixWithPlainTxtPrimary() throws IOException {
        // Primary is "0.txt" (Shift-JIS family); a stray "1.utf" continuation must not be pulled
        // in, since it belongs to a "0.utf"-primary series that doesn't exist here.
        write("0.txt", "*start\n");
        write("1.txt", "plain continuation\n");
        write("1.utf", "should not be included\n");
        NsScript script = NsScriptSource.load(tmp.getRoot());
        assertEquals(2, script.lines.size());
        assertEquals("plain continuation", script.lines.get(1));
    }

    @Test
    public void utfPrimaryTakesPriorityAndDecodesAsUtf8() throws IOException {
        write("0.txt", "should be ignored\n");
        write("0.utf.txt", "*start\nこんにちは\\\n");
        NsScript script = NsScriptSource.load(tmp.getRoot());
        assertEquals(StandardCharsets.UTF_8, script.encoding);
        assertEquals("こんにちは\\", script.lines.get(1));
    }

    @Test
    public void peekResolutionReadsLeadingModeDirective() throws IOException {
        write("0.txt", ";mode400\n;gameid test\n*start\n");
        NsResolution res = NsScriptSource.peekResolution(tmp.getRoot());
        assertEquals(400, res.width);
        assertEquals(300, res.height);
    }

    @Test
    public void peekResolutionDefaultsWhenNoModeDirective() throws IOException {
        write("0.txt", "*start\nHello\\\n");
        NsResolution res = NsScriptSource.peekResolution(tmp.getRoot());
        assertEquals(NsResolution.DEFAULT.width, res.width);
        assertEquals(NsResolution.DEFAULT.height, res.height);
    }

    @Test
    public void peekResolutionStopsAtFirstNonCommentLine() throws IOException {
        // A mode directive appearing after real content (not a leading header) must not count.
        write("0.txt", "*start\n;mode800\n");
        NsResolution res = NsScriptSource.peekResolution(tmp.getRoot());
        assertEquals(NsResolution.DEFAULT.width, res.width);
    }

    @Test
    public void peekTitleInfoReadsCaptionAndVersionstrSubtitle() throws IOException {
        // Seen in real sample scripts: "caption \"X\"" sets the title, and
        // "versionstr"'s 2nd argument (its 1st is usually just the title again) is the subtitle.
        write("0.txt", "*define\ncaption \"The Answer\"\n"
                + "versionstr \"The Answer\",\" version 1.0-en\"\ngame\n*start\n");
        NsScriptSource.NsTitleInfo info = NsScriptSource.peekTitleInfo(tmp.getRoot());
        assertEquals("The Answer", info.title);
        assertEquals("version 1.0-en", info.subtitle);
    }

    @Test
    public void peekTitleInfoFallsBackToVersionstrFirstArgWhenNoCaption() throws IOException {
        write("0.txt", "*define\nversionstr \"Fallback Title\",\"copyright someone\"\ngame\n*start\n");
        NsScriptSource.NsTitleInfo info = NsScriptSource.peekTitleInfo(tmp.getRoot());
        assertEquals("Fallback Title", info.title);
        assertEquals("copyright someone", info.subtitle);
    }

    @Test
    public void peekTitleInfoIgnoresCaptionAfterGameStarts() throws IOException {
        // "caption"/"versionstr" only ever appear in the header, before "game" -- a coincidental
        // later occurrence (unlikely, but not impossible) must not be picked up as the real title.
        write("0.txt", "*define\ngame\n*start\ncaption \"Not the real title\"\n");
        NsScriptSource.NsTitleInfo info = NsScriptSource.peekTitleInfo(tmp.getRoot());
        assertEquals(null, info.title);
        assertEquals(null, info.subtitle);
    }

    @Test
    public void labelIndexCoversLabelsAcrossContinuationFiles() throws IOException {
        write("0.txt", "*start\ngoto *later\n");
        write("1.txt", "*later\nThe end\\\n");
        NsScript script = NsScriptSource.load(tmp.getRoot());
        List<String> lines = script.lines;
        assertEquals("*later", lines.get(2));
        assertEquals(2, (int) script.labelIndex.get("later"));
    }
}
