package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Opt-in real-sample test: automatically skipped (not failed) when no local sample pack is
 * present -- see .gitignore.
 *
 * <p>Verifies the nscript.dat XOR transform against a real NScripter game's nscript.dat
 * rather than only a hand-constructed
 * fixture -- this is the fact the whole clean-room reimplementation rests on, so it's worth
 * pinning down against genuine data, not just the documented byte value. Skipped automatically
 * when the sample pack isn't present locally (see .gitignore -- kept locally for manual testing,
 * not part of the repo).
 */
public class NsObfuscationTest {

    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final File REAL_SAMPLE = findRealSample();

    private static File findRealSample() {
        // Walk upward from the working directory (varies: repo root when run from the IDE, the
        // "app" module dir under Gradle) looking for the sample pack, if kept locally.
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "Onscripter examples/a_dream_of_summer/nscript.dat");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    public void decodesTheRealBundledSampleIntoReadableScriptText() throws IOException {
        assumeTrue("Real sample pack not found relative to the test working directory", REAL_SAMPLE != null);
        byte[] raw = readAll(REAL_SAMPLE);
        byte[] decoded = NsObfuscation.decode(raw, NsObfuscation.Format.NSCRIPT_DAT);
        String text = new String(decoded, 0, Math.min(decoded.length, 200), SHIFT_JIS);
        assertTrue("decoded text should start with a ';value' header directive, got: " + text,
                text.startsWith(";value"));
        assertTrue("decoded text should contain the game's gameid line",
                text.contains(";gameid A Dream of Summer"));
    }

    @Test
    public void decodedTextTokenizesIntoSensibleScriptLines() throws IOException {
        assumeTrue("Real sample pack not found relative to the test working directory", REAL_SAMPLE != null);
        byte[] raw = readAll(REAL_SAMPLE);
        byte[] decoded = NsObfuscation.decode(raw, NsObfuscation.Format.NSCRIPT_DAT);
        String text = new String(decoded, SHIFT_JIS);
        String[] rawLines = text.split("\n", -1);

        int labels = 0, comments = 0, statements = 0;
        for (String raw2 : rawLines) {
            NsLine line = NsTokenizer.classify(raw2);
            switch (line.type) {
                case LABEL: labels++; break;
                case COMMENT: comments++; break;
                case STATEMENT: statements++; break;
                default: break;
            }
        }
        // Loose sanity bounds, not exact counts: a real, large game script should have plenty of
        // each of these, and finding a real "*rclk" label (seen during manual inspection) confirms
        // the label index actually indexes something meaningful.
        assertTrue("expected many labels in a real game script, got " + labels, labels > 20);
        assertTrue("expected many comments, got " + comments, comments > 20);
        assertTrue("expected many statement lines, got " + statements, statements > 100);
    }

    @Test
    public void loadedViaNsScriptSourceProducesTheSameLabelIndex() {
        assumeTrue("Real sample pack not found relative to the test working directory", REAL_SAMPLE != null);
        File vnDir = REAL_SAMPLE.getParentFile();
        assertTrue(NsScriptSource.hasAnyScript(vnDir));
        assertEquals(false, NsScriptSource.hasPlainTextScript(vnDir)); // it's obfuscated, not plain text
        NsScript script = NsScriptSource.load(vnDir);
        assertTrue("expected the real script to decode into a substantial line count, got " + script.lines.size(),
                script.lines.size() > 1000);
        assertTrue("expected a *rclk label (seen during manual inspection) to be indexed",
                script.labelIndex.containsKey("rclk"));
    }

    /**
     * Regression test for a real bug found after shipping: this sample's first ~3025 lines are
     * subroutine/menu-handler definitions meant to be gosub/goto'd into later, before a "*define"
     * label begins the real header section -- starting execution at line 0 wandered through that
     * dead menu logic instead, producing a black screen with no dialogue and no way to progress.
     * NsScript.startPc must skip that dead-code dump, landing at "*define" itself.
     *
     * <p>A second, related bug found later: an earlier fix instead skipped all the way past the
     * header's own "game" command -- which also skips the header's live setup content (confirmed
     * in this exact sample: its "*define" section does {@code gosub *sys_define}, registering
     * "numalias" declarations the story references throughout), silently breaking anything that
     * depends on it. startPc must land at "*define" and let normal execution carry it through
     * "game" like real NScripter does, not skip past "game" outright.
     */
    @Test
    public void startPcLandsAtTheDefineLabelSkippingOnlyTheDeadMenuCode() {
        assumeTrue("Real sample pack not found relative to the test working directory", REAL_SAMPLE != null);
        NsScript script = NsScriptSource.load(REAL_SAMPLE.getParentFile());
        assertTrue("startPc should be well past the ~3025-line dead subroutine dump, got " + script.startPc,
                script.startPc > 3000);
        NsLine atStart = NsTokenizer.classify(script.lines.get(script.startPc));
        assertEquals("startPc should land exactly at the \"*define\" label",
                NsLine.Type.LABEL, atStart.type);
        assertEquals("define", atStart.text);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) {
                off += n;
            }
            return buf;
        }
    }
}
