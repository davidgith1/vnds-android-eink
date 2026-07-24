package com.example.vndsandroideink.nscripter;

/**
 * One classified line from an NScripter script buffer -- the output of {@link NsTokenizer#classify}.
 * Classification is purely syntactic (based on the line's leading character); whether a
 * {@link Type#STATEMENT} line turns out to be a recognized command or plain dialogue text is
 * decided later, by the command dispatcher, once it knows which mnemonics it supports (see
 * {@link #firstToken}).
 */
public final class NsLine {

    public enum Type {
        BLANK,
        /** Starts with ';'. {@link #text} is the comment body (';' stripped, untrimmed). */
        COMMENT,
        /** Starts with '*'. {@link #text} is the label name. */
        LABEL,
        /** A line whose entire trimmed content is "~" -- a jumpf skip-target marker. */
        TILDE,
        /** Anything else: either a command invocation or a bare dialogue line. {@link #text} is
         * the full trimmed original line, untouched. */
        STATEMENT
    }

    public final Type type;
    /** Meaning depends on {@link #type}; see each constant's doc. */
    public final String text;
    /** The original, unmodified source line (pre-trim), kept for error messages/debugging. */
    public final String raw;

    private NsLine(Type type, String text, String raw) {
        this.type = type;
        this.text = text;
        this.raw = raw;
    }

    static NsLine blank(String raw) {
        return new NsLine(Type.BLANK, "", raw);
    }

    static NsLine comment(String body, String raw) {
        return new NsLine(Type.COMMENT, body, raw);
    }

    static NsLine label(String name, String raw) {
        return new NsLine(Type.LABEL, name, raw);
    }

    static NsLine tilde(String raw) {
        return new NsLine(Type.TILDE, "~", raw);
    }

    static NsLine statement(String trimmed, String raw) {
        return new NsLine(Type.STATEMENT, trimmed, raw);
    }

    /**
     * For a {@link Type#STATEMENT} line, the candidate command mnemonic: the leading run of
     * ASCII letters/digits/underscore, lowercased. The dispatcher looks this up against its
     * known-command table; if absent, the whole line ({@link #text}) is dialogue, not a command.
     *
     * <p>A command name is terminated by the first character outside [a-zA-Z0-9_] --
     * NOT specifically whitespace or a comma. Real scripts routinely write a command's first
     * (quoted) argument with no separator at all before it, e.g. {@code bg"e\zigzag.jpg",12} or
     * {@code caption"My Game"} (a pattern seen used throughout a real script's
     * header) -- stopping only at whitespace/comma (an earlier, less correct version of this
     * method) would swallow the whole {@code "e\zigzag.jpg"} quoted string into the "command name"
     * itself, producing a bareword that matches nothing in the dispatcher's command table, so e.g.
     * every "bg"-loaded background in that game silently failed to load at all.
     */
    public String firstToken() {
        if (type != Type.STATEMENT) {
            return "";
        }
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // Strictly ASCII, matching real ONScripter's own check exactly -- NOT Character#
            // isLetterOrDigit, which is Unicode-aware and would swallow an unquoted non-ASCII
            // bareword (e.g. Japanese text) directly abutting the mnemonic into the "command name"
            // itself instead of stopping there.
            boolean isAsciiLetterDigitOrUnderscore = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!isAsciiLetterDigitOrUnderscore) {
                break;
            }
            i++;
        }
        return text.substring(0, i).toLowerCase(java.util.Locale.ROOT);
    }

    /** For a {@link Type#STATEMENT} line, everything after {@link #firstToken()}, with any single
     * separating whitespace/comma consumed and the rest left untouched (so a dialogue fallback can
     * still recover the exact original text via {@link #text} instead). */
    public String argsText() {
        if (type != Type.STATEMENT) {
            return "";
        }
        String token = firstToken();
        String rest = text.substring(token.length());
        if (!rest.isEmpty() && (rest.charAt(0) == ',' || Character.isWhitespace(rest.charAt(0)))) {
            rest = rest.substring(1);
        }
        return rest.trim();
    }

    @Override
    public String toString() {
        return type + ":" + text;
    }
}
