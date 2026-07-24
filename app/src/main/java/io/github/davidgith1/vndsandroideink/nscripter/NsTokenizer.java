package io.github.davidgith1.vndsandroideink.nscripter;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw NScripter script lines/argument text into structured pieces: {@link #classify} for a
 * whole line (label/comment/tilde-marker/statement), {@link #parseArgs} for a command's
 * comma-separated argument list. Pure lexical analysis -- no variable lookup or expression
 * evaluation happens here (see {@link NsArg}'s doc).
 */
public final class NsTokenizer {

    private NsTokenizer() {
    }

    /** Classifies one raw script line. Never returns null. */
    public static NsLine classify(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return NsLine.blank(rawLine);
        }
        char first = trimmed.charAt(0);
        if (first == ';') {
            return NsLine.comment(trimmed.substring(1), rawLine);
        }
        if (first == '*') {
            return NsLine.label(parseLabelName(trimmed.substring(1)), rawLine);
        }
        if (trimmed.equals("~")) {
            return NsLine.tilde(rawLine);
        }
        return NsLine.statement(stripTrailingComment(trimmed), rawLine);
    }

    /** Real scripts routinely trail a command with an unquoted ";comment" on the same line (e.g.
     * {@code dwave 1,"se.wav";<SE cue>}). Strips it
     * before the line is treated as a statement, the same way a whole-line ";comment" never
     * reaches {@link NsLine.Type#STATEMENT}. A quoted ';' (inside "...") doesn't count. This does
     * mean a literal ';' used as ASCII punctuation in dialogue text gets truncated too -- an
     * accepted tradeoff since trailing-comment stripping is a script-wide NScripter convention,
     * not command-specific, and real dialogue in the format's usual (Japanese) source material
     * doesn't use ASCII semicolons as punctuation. */
    private static String stripTrailingComment(String text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ';' && !inQuotes) {
                return text.substring(0, i).trim();
            }
        }
        return text;
    }

    /** A label name runs up to the first character outside [a-zA-Z0-9_] -- the same ASCII-only
     * character class {@link NsLine#firstToken()} uses for a command mnemonic. Real scripts
     * routinely write a same-line trailing comment directly after a label with no space at all,
     * e.g. {@code *syuryo;０．５秒ディレイしてます。} -- stopping only at
     * whitespace/comma (an earlier, less correct version of this method) would swallow the whole
     * ";..." comment into the label's own registered name, so any "goto"/"gosub" reference to the
     * short name alone (e.g. "goto *syuryo") could never resolve it. */
    private static String parseLabelName(String afterStar) {
        int i = 0;
        while (i < afterStar.length()) {
            char c = afterStar.charAt(i);
            boolean isAsciiLetterDigitOrUnderscore = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!isAsciiLetterDigitOrUnderscore) {
                break;
            }
            i++;
        }
        return afterStar.substring(0, i);
    }

    /**
     * Splits a command's argument text on top-level commas (commas inside a "quoted string" don't
     * split) and classifies each resulting token. An empty {@code argsText} yields an empty list
     * (a zero-argument command, e.g. a bare "click" line).
     */
    public static List<NsArg> parseArgs(String argsText) {
        List<NsArg> result = new ArrayList<>();
        if (argsText.isEmpty()) {
            return result;
        }
        for (String token : splitTopLevelCommas(argsText)) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.charAt(0) == '"') {
                int close = t.indexOf('"', 1);
                if (close >= 0 && close < t.length() - 1) {
                    // A properly closed quoted string with more (unquoted) text trailing in the
                    // same top-level-comma slot -- e.g. "dwave 0,\"file.ogg\"<TAB>Some text"
                    // (real ONScripter "dwave" voice-with-caption usage puts a
                    // tab-separated display-text cue right after the filename, not as its own
                    // comma-separated argument). Without this, the whole blob fails the
                    // "token ends with a quote" check below and falls through to one corrupted
                    // BAREWORD, breaking both the file path and dropping the text. Emit the string
                    // and its trailing text as two separate args instead.
                    String quoted = t.substring(0, close + 1);
                    String trailing = t.substring(close + 1).trim();
                    result.add(classifyArg(quoted));
                    if (!trailing.isEmpty()) {
                        result.add(new NsArg(NsArg.Kind.BAREWORD, trailing, trailing));
                    }
                    continue;
                }
            }
            result.add(classifyArg(t));
        }
        return result;
    }

    private static List<String> splitTopLevelCommas(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static NsArg classifyArg(String token) {
        if (token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"') {
            return new NsArg(NsArg.Kind.STRING_LITERAL, token, token.substring(1, token.length() - 1));
        }
        if (token.charAt(0) == '%') {
            return new NsArg(NsArg.Kind.NUM_VAR_EXPR, token, token.substring(1));
        }
        if (token.charAt(0) == '$') {
            return new NsArg(NsArg.Kind.STR_VAR_EXPR, token, token.substring(1));
        }
        if (isIntegerLiteral(token)) {
            return new NsArg(NsArg.Kind.NUMBER_LITERAL, token, token);
        }
        return new NsArg(NsArg.Kind.BAREWORD, token, token);
    }

    private static boolean isIntegerLiteral(String s) {
        int start = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (start >= s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
