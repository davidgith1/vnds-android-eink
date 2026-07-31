package io.github.davidgith1.vndsandroideink.vnds;

import static org.junit.Assert.assertEquals;
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
import java.util.HashMap;

public class ScriptEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String content) throws IOException {
        File scriptDir = new File(tmp.getRoot(), "script");
        scriptDir.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(scriptDir, "main.scr")), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    // bgload/setimg/sound/music never called substituteVariables on their file argument at all
    // (unlike text/goto/choice, which do) -- so a script using a "$name" reference as an asset
    // path (a real, observed pattern in Kanon's VNDS pack, e.g. "setvar DATEIMAGE = \"SDT0116.png\""
    // then "setimg $DATEIMAGE 0 0") showed the literal, unresolved "$DATEIMAGE" text as the asset
    // path instead of the real filename, so the asset silently failed to load.

    @Test
    public void bgloadResolvesADollarVariableReferenceInTheFilename() throws IOException {
        write(String.join("\n",
                "setvar bgname = BG1.jpg",
                "bgload $bgname",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("BG1.jpg", listener.lastBackground.getName());
    }

    @Test
    public void setimgResolvesADollarVariableReferenceInTheFilename() throws IOException {
        write(String.join("\n",
                "setvar spritename = SPR1.png",
                "setimg $spritename 10 20",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals(1, listener.spriteFiles.size());
        assertEquals("SPR1.png", listener.spriteFiles.get(0).getName());
    }

    @Test
    public void soundAndMusicResolveADollarVariableReferenceInTheFilename() throws IOException {
        write(String.join("\n",
                "setvar sfxname = SFX1.ogg",
                "setvar bgmname = BGM1.ogg",
                "sound $sfxname 1",
                "music $bgmname",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("SFX1.ogg", listener.lastSound.getName());
        assertEquals("BGM1.ogg", listener.lastMusic.getName());
    }

    // Real packs (Kanon again) also use a "$name[1000]"-style reference -- always the same literal
    // numeric index, never a computed one, used as a fixed scratch string register rather than a
    // real dynamic array -- which VAR_REFERENCE didn't recognize at all (its name pattern stopped
    // at the first "[", leaving "[1000]" behind unsubstituted) even once the asset handlers above
    // were fixed to call substituteVariables in the first place.

    @Test
    public void aBracketIndexedVariableNameResolvesAsOneLiteralKey() throws IOException {
        write(String.join("\n",
                "setvar strS[1000] = BG2.jpg",
                "bgload $strS[1000]",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("BG2.jpg", listener.lastBackground.getName());
    }

    // Real packs (Kanon's own script) commonly wrap a setvar/gsetvar string value in double
    // quotes (e.g. "setvar strS[1000] = \"BG003B.jpg\""), same as a quoted string literal
    // elsewhere in the format -- stored verbatim, that put the literal quote characters into the
    // resolved asset path (e.g. "background/\"BG003B.jpg\""), which can never exist on disk.

    @Test
    public void setvarStripsAWrappingQuotePairFromItsStringValue() throws IOException {
        write(String.join("\n",
                "setvar bgname = \"BG3.jpg\"",
                "bgload $bgname",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("BG3.jpg", listener.lastBackground.getName());
    }

    @Test
    public void setvarLeavesAMidValueQuoteAloneNotJustAWrappingPair() throws IOException {
        write(String.join("\n",
                "setvar greeting = Say \"hi\" now",
                "text $greeting",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("Say \"hi\" now", listener.textLines.get(0));
    }

    // Real packs (Kanon again) use "jump $RETFILE"-style dynamic jump targets (a "return address"
    // variable set before entering a shared subroutine-like script, per Kanon's own
    // "setvar RETFILE = \"SEEN0326.scr\"" then later "jump $RETFILE") -- handleJump never called
    // substituteVariables either, so this jumped to a file literally named "$RETFILE", which
    // doesn't exist, silently ending the story early instead of returning to the real caller file.

    @Test
    public void jumpResolvesADollarVariableReferenceInItsTargetFile() throws IOException {
        File scriptDir = new File(tmp.getRoot(), "script");
        scriptDir.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(scriptDir, "other.scr")), StandardCharsets.UTF_8)) {
            w.write(String.join("\n", "text Reached the real target file.", ""));
        }
        write(String.join("\n",
                "setvar RETFILE = other.scr",
                "jump $RETFILE",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("Reached the real target file.", listener.textLines.get(0));
    }

    @Test
    public void aBraceDelimitedBracketIndexedVariableReferenceAlsoResolves() throws IOException {
        write(String.join("\n",
                "setvar strS[7] = hello",
                "text {$strS[7]} world",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("hello world", listener.textLines.get(0));
    }

    // Real packs (Never7's "setvar v_a1_08 = selected" choice-index copy, Ever17's equivalent
    // "setvar v_b3 = selected2") rely on a bareword setvar/gsetvar value copying another
    // variable's own value, not the literal source name -- per the VNDS wiki's own choice/setvar
    // text, "$" is only documented as needed to use a variable "in other commands" (interpolating
    // it into a string argument like text), not for setvar's own value operand. Before this was
    // resolved, "setvar v_b3 = selected2" stored the literal string "selected2" (or, once that hit
    // the "+"/"-" numeric parse, silently fell back to 0), breaking every "if v_b3 == ..." branch
    // downstream.

    @Test
    public void setvarCopiesAnotherVariablesValueWhenTheRightHandSideIsABareword() throws IOException {
        write(String.join("\n",
                "choice A|B",
                "if selected == 1",
                "    setvar selected2 = 0",
                "fi",
                "if selected == 2",
                "    setvar selected2 = 1",
                "fi",
                "setvar v_b3 = selected2",
                "text done",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();
        engine.choose(1); // pick "B" (selected=2 -> selected2=1)

        assertEquals("1", engine.getVariablesSnapshot().get("v_b3"));
    }

    // Same underlying idiom, for "if"/"fi": the VNDS format's own documented note on if/fi (see
    // this project's CLAUDE.md) says the right-hand operand "may be either" a literal or a
    // variable -- confirmed against real VNDSx 1.4.9 ("1 == varthatissetto1" evaluates true when
    // that variable holds 1). A real pack (Never7's "if v_a1_03 < v_a1_00", comparing two of its
    // own tracking variables) only makes sense if the right-hand bareword is resolved the same
    // way the left-hand one always is.

    @Test
    public void ifConditionResolvesABarewordRightHandSideAsAVariable() throws IOException {
        write(String.join("\n",
                "setvar hour = 10",
                "setvar curfew = 9",
                "if hour < curfew",
                "    text too early",
                "fi",
                "if hour > curfew",
                "    text past curfew",
                "fi",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals(1, listener.textLines.size());
        assertEquals("past curfew", listener.textLines.get(0));
    }

    // A bareword that never matches an actually-assigned variable must still behave as a plain
    // literal, exactly as before -- otherwise an ordinary name/string comparison like
    // "if name == John" would wrongly treat "John" as an (unset, defaulting-to-0) variable
    // reference instead of the literal text it obviously is.

    @Test
    public void aBarewordThatIsNotAKnownVariableStaysALiteralInSetvarAndIf() throws IOException {
        write(String.join("\n",
                "setvar greeting = hello",
                "if greeting == hello",
                "    text matched the literal",
                "fi",
                ""));
        FakeListener listener = new FakeListener();
        ScriptEngine engine = new ScriptEngine(tmp.getRoot(), listener, new HashMap<>());

        engine.start();

        assertEquals("hello", engine.getVariablesSnapshot().get("greeting"));
        assertEquals(1, listener.textLines.size());
        assertEquals("matched the literal", listener.textLines.get(0));
    }
}
