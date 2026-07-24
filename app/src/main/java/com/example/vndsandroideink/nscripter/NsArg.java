package com.example.vndsandroideink.nscripter;

/**
 * One lexical argument out of a command's comma-separated argument list (see
 * {@link NsTokenizer#parseArgs}). Purely lexical -- e.g. a {@link Kind#NUM_VAR_EXPR}'s
 * {@link #value} is the raw expression text after the '%' sigil ("1+cnt"), not yet evaluated;
 * evaluating it against live variable state is the command dispatcher's job (later milestone).
 */
public final class NsArg {

    public enum Kind {
        /** A "quoted string" literal; {@link #value} has the surrounding quotes stripped. */
        STRING_LITERAL,
        /** A %-prefixed numeric-variable reference or expression, e.g. "%3", "%cnt+1". */
        NUM_VAR_EXPR,
        /** A $-prefixed string-variable reference or expression, e.g. "$3", "$name". */
        STR_VAR_EXPR,
        /** A bare integer literal, e.g. "42" or "-1". */
        NUMBER_LITERAL,
        /** Anything else: a label reference ("*start"), filename, bareword flag, etc. */
        BAREWORD
    }

    public final Kind kind;
    /** The original token text, exactly as it appeared (including any sigil/quotes). */
    public final String raw;
    /** The interpreted value: quotes stripped for {@link Kind#STRING_LITERAL}, the sigil stripped
     * for {@link Kind#NUM_VAR_EXPR}/{@link Kind#STR_VAR_EXPR}, otherwise same as {@link #raw}. */
    public final String value;

    NsArg(Kind kind, String raw, String value) {
        this.kind = kind;
        this.raw = raw;
        this.value = value;
    }

    @Override
    public String toString() {
        return kind + "(" + value + ")";
    }
}
