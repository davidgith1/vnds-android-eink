package com.example.vndsandroideink.nscripter;

/**
 * Evaluates the lexical fragments {@link NsTokenizer} produces: numeric arithmetic ("2+%3"),
 * string concatenation ("\"a\"+$2"), and variable-slot resolution (a name is either a plain
 * index, e.g. "3", or a numalias/stralias name standing in for one). Deliberately small --
 * +, -, *, / with standard precedence and parentheses for numbers; + (concatenation) for strings
 * -- covering what real scripts commonly write, not NScripter's complete expression grammar.
 */
public final class NsExpr {

    private NsExpr() {
    }

    // ---- Value evaluation (right-hand side of an assignment, a condition, etc.) --------------

    public static long numeric(NsArg arg, NsExecState state) {
        switch (arg.kind) {
            case NUMBER_LITERAL:
                return parseLongSafe(arg.value);
            case NUM_VAR_EXPR:
                // arg.raw, not arg.value: the parser needs the leading '%' itself (still present in
                // raw) to recognize the operand as a variable read rather than a bare literal --
                // that's exactly what distinguishes "%1" (read var 1) from a literal "1".
                return new NumParser(arg.raw, state).parse();
            case STR_VAR_EXPR:
                return 0; // a string variable has no numeric value; type mismatch, tolerate silently
            case BAREWORD:
            default:
                return resolveNamedConstantOrLiteral(arg.value, state);
        }
    }

    public static String string(NsArg arg, NsExecState state) {
        switch (arg.kind) {
            case STRING_LITERAL:
                return arg.value;
            case STR_VAR_EXPR:
                return new StrParser(arg.raw, state).parse(); // see numeric()'s note on raw vs value
            case NUM_VAR_EXPR:
                return String.valueOf(new NumParser(arg.raw, state).parse());
            case NUMBER_LITERAL:
            case BAREWORD:
            default:
                return arg.value;
        }
    }

    private static long resolveNamedConstantOrLiteral(String token, NsExecState state) {
        Integer idx = state.numAliases.get(token);
        if (idx != null) {
            return state.numVars.getOrDefault(idx, 0L);
        }
        return parseLongSafe(token);
    }

    // ---- Variable-slot resolution (which %N/$N a command's target argument names) -------------

    /** Resolves a NUM_VAR_EXPR target argument (a "mov"/"add"/etc. destination) to a plain
     * variable index: either the digits it already is, or a numalias name's slot. */
    public static int numVarIndex(NsArg arg, NsExecState state) {
        return resolveIndex(leadingIdentifier(arg.value), state.numAliases);
    }

    public static int strVarIndex(NsArg arg, NsExecState state) {
        return resolveIndex(leadingIdentifier(arg.value), state.strAliases);
    }

    private static int resolveIndex(String token, java.util.Map<String, Integer> aliases) {
        if (isDigits(token)) {
            return Integer.parseInt(token);
        }
        Integer idx = aliases.get(token);
        return idx != null ? idx : 0; // unknown alias: tolerate, same as an unknown command
    }

    /** A variable-target token should be a bare identifier ("3" or "money"); if a script somehow
     * writes something stranger there, take just the identifier-shaped prefix and ignore the rest
     * rather than fail outright. */
    private static String leadingIdentifier(String s) {
        int i = 0;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return i == 0 ? s : s.substring(0, i);
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Numeric expression grammar: expr := term (('+'|'-') term)* ; term := factor (('*'|'/') factor)*
    // factor := NUMBER | '%' IDENT | '(' expr ')' -------------------------------------------

    private static final class NumParser {
        private final String s;
        private final NsExecState state;
        private int pos = 0;

        NumParser(String s, NsExecState state) {
            this.s = s;
            this.state = state;
        }

        long parse() {
            return expr();
        }

        private long expr() {
            long v = term();
            while (true) {
                skipSpace();
                if (peek() == '+') {
                    pos++;
                    v += term();
                } else if (peek() == '-') {
                    pos++;
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private long term() {
            long v = factor();
            while (true) {
                skipSpace();
                if (peek() == '*') {
                    pos++;
                    v *= factor();
                } else if (peek() == '/') {
                    pos++;
                    long divisor = factor();
                    v = divisor != 0 ? v / divisor : 0;
                } else {
                    return v;
                }
            }
        }

        private long factor() {
            skipSpace();
            char c = peek();
            if (c == '(') {
                pos++;
                long v = expr();
                skipSpace();
                if (peek() == ')') {
                    pos++;
                }
                return v;
            }
            if (c == '%') {
                pos++;
                String ident = readIdent();
                int idx = resolveIndex(ident, state.numAliases);
                return state.numVars.getOrDefault(idx, 0L);
            }
            if (c == '-') {
                pos++;
                return -factor();
            }
            return readNumber();
        }

        private long readNumber() {
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                // Not a digit: a bareword/alias appearing directly in the expression, e.g. "cnt+1".
                String ident = readIdent();
                return resolveNamedConstantOrLiteral(ident, state);
            }
            return Long.parseLong(s.substring(start, pos));
        }

        private String readIdent() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }

    // ---- String expression grammar: expr := term ('+' term)* ; term := "quoted" | '$' IDENT | BAREWORD

    private static final class StrParser {
        private final String s;
        private final NsExecState state;
        private int pos = 0;

        StrParser(String s, NsExecState state) {
            this.s = s;
            this.state = state;
        }

        String parse() {
            StringBuilder sb = new StringBuilder(term());
            while (true) {
                skipSpace();
                if (peek() == '+') {
                    pos++;
                    sb.append(term());
                } else {
                    return sb.toString();
                }
            }
        }

        private String term() {
            skipSpace();
            char c = peek();
            if (c == '"') {
                pos++;
                int start = pos;
                while (pos < s.length() && s.charAt(pos) != '"') {
                    pos++;
                }
                String v = s.substring(start, pos);
                if (pos < s.length()) {
                    pos++; // consume closing quote
                }
                return v;
            }
            if (c == '$') {
                pos++;
                String ident = readIdent();
                int idx = resolveIndex(ident, state.strAliases);
                return state.strVars.getOrDefault(idx, "");
            }
            String ident = readIdent();
            return ident;
        }

        private String readIdent() {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) != '+' && !Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
