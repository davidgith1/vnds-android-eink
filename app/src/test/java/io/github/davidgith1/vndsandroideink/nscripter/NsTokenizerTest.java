package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NsTokenizerTest {

    @Test
    public void classifiesBlankLine() {
        assertEquals(NsLine.Type.BLANK, NsTokenizer.classify("   ").type);
        assertEquals(NsLine.Type.BLANK, NsTokenizer.classify("").type);
    }

    @Test
    public void classifiesComment() {
        NsLine line = NsTokenizer.classify(";mode400 widescreen test");
        assertEquals(NsLine.Type.COMMENT, line.type);
        assertEquals("mode400 widescreen test", line.text);
    }

    @Test
    public void classifiesLabel() {
        NsLine line = NsTokenizer.classify("*start");
        assertEquals(NsLine.Type.LABEL, line.type);
        assertEquals("start", line.text);
    }

    @Test
    public void labelNameStopsAtWhitespace() {
        NsLine line = NsTokenizer.classify("*chapter1 ; the beginning");
        assertEquals(NsLine.Type.LABEL, line.type);
        assertEquals("chapter1", line.text);
    }

    @Test
    public void labelNameStopsAtATrailingCommentWithNoSpaceBeforeIt() {
        // Real ONScripter-EN terminates a label name at the first character outside [a-zA-Z0-9_] --
        // scripts commonly write a same-line comment directly after a label with no space at all, e.g.
        // "*syuryo;０．５秒ディレイしてます。". Before this was fixed, the whole ";..." comment
        // was swallowed into the registered label name, so "goto *syuryo" could never resolve it.
        NsLine line = NsTokenizer.classify("*syuryo;half a second delay comment");
        assertEquals(NsLine.Type.LABEL, line.type);
        assertEquals("syuryo", line.text);
    }

    @Test
    public void classifiesTildeMarker() {
        assertEquals(NsLine.Type.TILDE, NsTokenizer.classify("~").type);
        assertEquals(NsLine.Type.TILDE, NsTokenizer.classify("  ~  ").type);
    }

    @Test
    public void classifiesStatementAndExtractsFirstToken() {
        NsLine line = NsTokenizer.classify("mov %1,3");
        assertEquals(NsLine.Type.STATEMENT, line.type);
        assertEquals("mov", line.firstToken());
        assertEquals("%1,3", line.argsText());
    }

    @Test
    public void bareDialogueLineIsAlsoAStatement() {
        // Command-vs-dialogue disambiguation happens later, at the dispatcher: the tokenizer
        // itself only reports the syntactic shape and the candidate first token.
        NsLine line = NsTokenizer.classify("Hello, world!\\");
        assertEquals(NsLine.Type.STATEMENT, line.type);
        assertEquals("hello", line.firstToken());
        assertEquals("Hello, world!\\", line.text);
    }

    @Test
    public void parseArgsSplitsOnTopLevelCommasOnly() {
        List<NsArg> args = NsTokenizer.parseArgs("\"a, b\",%1,$2");
        assertEquals(3, args.size());
        assertEquals(NsArg.Kind.STRING_LITERAL, args.get(0).kind);
        assertEquals("a, b", args.get(0).value);
        assertEquals(NsArg.Kind.NUM_VAR_EXPR, args.get(1).kind);
        assertEquals("1", args.get(1).value);
        assertEquals(NsArg.Kind.STR_VAR_EXPR, args.get(2).kind);
        assertEquals("2", args.get(2).value);
    }

    @Test
    public void parseArgsClassifiesNumbersAndBarewords() {
        List<NsArg> args = NsTokenizer.parseArgs("bg.png,-1,label_name");
        assertEquals(3, args.size());
        assertEquals(NsArg.Kind.BAREWORD, args.get(0).kind);
        assertEquals(NsArg.Kind.NUMBER_LITERAL, args.get(1).kind);
        assertEquals(-1, Integer.parseInt(args.get(1).value));
        assertEquals(NsArg.Kind.BAREWORD, args.get(2).kind);
    }

    @Test
    public void parseArgsCapturesNumericExpressionRaw() {
        // Arithmetic is not evaluated here -- just captured as raw text after the sigil.
        List<NsArg> args = NsTokenizer.parseArgs("%cnt+1");
        assertEquals(1, args.size());
        assertEquals(NsArg.Kind.NUM_VAR_EXPR, args.get(0).kind);
        assertEquals("cnt+1", args.get(0).value);
    }

    @Test
    public void parseArgsOfEmptyTextIsEmptyList() {
        assertTrue(NsTokenizer.parseArgs("").isEmpty());
    }

    @Test
    public void parseArgsRecognizesClosedQuoteWithTrailingUnquotedText() {
        // Real "dwave channel,\"file\"<TAB>text" usage: the filename's closing quote isn't the
        // last character of its top-level-comma slot -- must not fall through to one corrupted
        // BAREWORD (see NsTokenizer.parseArgs's doc).
        List<NsArg> args = NsTokenizer.parseArgs("0,\"file.ogg\"\tSome text");
        assertEquals(3, args.size());
        assertEquals(NsArg.Kind.NUMBER_LITERAL, args.get(0).kind);
        assertEquals(NsArg.Kind.STRING_LITERAL, args.get(1).kind);
        assertEquals("file.ogg", args.get(1).value);
        assertEquals(NsArg.Kind.BAREWORD, args.get(2).kind);
        assertEquals("Some text", args.get(2).value);
    }

    @Test
    public void parseArgsNormalQuotedStringEndingTheTokenIsUnaffected() {
        List<NsArg> args = NsTokenizer.parseArgs("0,\"file.ogg\"");
        assertEquals(2, args.size());
        assertEquals(NsArg.Kind.STRING_LITERAL, args.get(1).kind);
        assertEquals("file.ogg", args.get(1).value);
    }

    @Test
    public void parseArgsTreatsBacktickAsAStringDelimiterLikeDoubleQuote() {
        // Real ONScripter-EN's ScriptHandler::parseStr reads a `backtick string` exactly like a
        // "quoted string" -- a literal run up to its matching close, commas and all. A real
        // Tsukihime "select" line ("select `Yes, I agree to all three terms.`, *termsagree, ...")
        // used to have its embedded comma split the option text in two, corrupting both the
        // displayed choice text and the label that followed it.
        List<NsArg> args = NsTokenizer.parseArgs("`Yes, I agree to all three terms.`,*termsagree");
        assertEquals(2, args.size());
        assertEquals(NsArg.Kind.STRING_LITERAL, args.get(0).kind);
        assertEquals("Yes, I agree to all three terms.", args.get(0).value);
        assertEquals(NsArg.Kind.BAREWORD, args.get(1).kind);
        assertEquals("*termsagree", args.get(1).value);
    }

    @Test
    public void stripTrailingCommentIgnoresASemicolonInsideABacktickString() {
        NsLine line = NsTokenizer.classify("`Wait; don't go.`;a real trailing comment");
        assertEquals(NsLine.Type.STATEMENT, line.type);
        assertEquals("`Wait; don't go.`", line.text);
    }
}
