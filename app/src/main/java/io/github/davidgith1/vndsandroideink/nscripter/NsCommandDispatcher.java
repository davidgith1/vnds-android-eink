package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The core-subset command table: a plain {@code Map<String, NsCommandHandler>} keyed by mnemonic,
 * so adding a new command later never touches the run loop (see {@link #execute}) -- the same
 * "unknown commands silently no-op" philosophy vnds.ScriptEngine's switch/default already uses,
 * just as a lookup table instead of a switch.
 */
public final class NsCommandDispatcher {

    private NsCommandDispatcher() {
    }

    /** "if"/"notif" condition syntax: an "operand OP operand" comparison, then whitespace, then the
     * consequent command to run when the condition holds -- e.g. "if %1==1 mov %2,3". Real scripts
     * vary: the operator may or may not be padded with spaces on either side
     * (both "if %1==1 ..." and "if %29 = 1 ..." occur), and a bare "=" is accepted as a
     * synonym for "==". Operand group 1 is non-greedy specifically so "==" (etc.) is preferred over
     * a spurious single "=" match partway through it -- see the class-level test coverage for why
     * that ordering matters. */
    private static final Pattern CONDITION =
            Pattern.compile("^(\\S+?)\\s*(==|!=|>=|<=|=|>|<)\\s*(\\S+)\\s+(.*)$");

    /** "for VAR=FROM to TO [step STEP]" syntax -- space-separated with "to"/"step" as literal
     * keywords, NOT the comma-separated grammar every other command uses (see "for"'s own handler
     * doc). Matched against the single NUM_VAR_EXPR argument {@link NsTokenizer#parseArgs} produces
     * for the whole "VAR=FROM to TO..." text (no commas in it to split on), so group 1 here is
     * already missing its leading '%' (stripped by {@link NsArg.Kind#NUM_VAR_EXPR} classification).
     * Real scripts vary: both "%0=701 to 709" and "%0 = 440 to -1520 step -1"
     * (spaces around '=' optional either way) occur. */
    private static final Pattern FOR_LOOP = Pattern.compile(
            "^(\\S+?)\\s*=\\s*(-?\\d+)\\s+to\\s+(-?\\d+)(?:\\s+step\\s+(-?\\d+))?$",
            Pattern.CASE_INSENSITIVE);

    /** Real ONScripter's inline text-embedded control codes -- "!w<ms>" (a plain timed pause),
     * "!d<ms>"/"!d" (a timed pause the player can skip early by clicking -- this host has no
     * click-to-skip-a-running-delay concept, so it's treated the same as "!w"), and "!s<ms>"/"!sd"
     * (sets the per-character text-display speed). Real scripts embed
     * these directly inside dialogue text rather than as their own command line, but in practice
     * they also commonly appear exclusively as WHOLE, standalone lines, always
     * either "!w<N>" or "!s<N>" alone on their own line (e.g. a company-logo intro's
     * "!w2000"/"!w500" pacing pauses before the real menu) -- this dispatcher only recognizes that
     * whole-line shape, not one embedded mid-sentence (this engine shows a whole line/segment at
     * once, with no per-character output to interleave a mid-line pause into). Before this was
     * recognized, such a line fell through as plain dialogue text and was shown verbatim (e.g.
     * literally "!w2000" flashed on screen, permanently, since it has no '\\'/'@' marker of its own
     * to ever stop waiting on) instead of pausing and moving on. */
    private static final Pattern INLINE_WAIT =
            Pattern.compile("^!(?:w(\\d+)|d(\\d*)|s(\\d+)|sd)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, NsCommandHandler> HANDLERS = buildHandlers();

    /**
     * Runs one already-classified line: no-ops for blank/comment/label/tilde lines (labels are
     * pre-indexed at load time, same as vnds.ScriptEngine's "label"/"fi" no-op cases), or hands a
     * statement line to {@link #executeChain} (which is where "if"/"notif"'s consequent re-enters
     * too, since that's just more statement text to run).
     */
    public static void execute(NsLine line, NsExecState state, VnEngine.Listener listener, File vnDir) {
        if (line.type != NsLine.Type.STATEMENT) {
            return; // BLANK/COMMENT/LABEL/TILDE: nothing to do
        }
        executeChain(line.text, state, listener, vnDir);
    }

    /**
     * Real scripts chain multiple commands on one line with ':' (e.g. "dwave 1,\"se.wav\":gosub
     * *foo:goto *bar"), which {@link NsLine} doesn't
     * split on its own since a ':' can also legitimately appear inside a quoted argument. Runs each
     * piece in order, stopping early if a piece blocks execution (a runState change) or jumps
     * elsewhere (goto/gosub/return/jumpf changing {@code state.pc}) -- either way, whatever's left
     * in the chain no longer applies.
     *
     * <p>"if"/"notif" is special-cased <em>before</em> any colon-splitting: its own consequent may
     * itself contain further ':'-chained commands (e.g. "if %1==1 mov %2,7:goto *done"),
     * which must stay conditional on the "if", not get split off
     * as an unconditional sibling statement the way a naive upfront split would produce.
     */
    private static void executeChain(String text, NsExecState state, VnEngine.Listener listener, File vnDir) {
        String remaining = text;
        while (!remaining.isEmpty()) {
            String trimmedRemaining = remaining.trim();
            if (trimmedRemaining.isEmpty()) {
                return;
            }
            String cmd = commandNameOf(trimmedRemaining);
            if (cmd.equals("if") || cmd.equals("notif")) {
                NsLine asIf = NsLine.statement(trimmedRemaining, trimmedRemaining);
                handleConditional(cmd.equals("notif"), asIf.argsText(), state, listener, vnDir);
                return; // consumes everything left in the chain, conditionally
            }

            if (classifyForChain(trimmedRemaining, state) == ChainClassification.DIALOGUE) {
                // Not a command at all (and never will be, however far the line continues) -- the
                // rest of the line, colons included, is one literal dialogue line. Colon-chaining
                // is a real, deliberate feature for command lines (see this method's own doc), but
                // must not also split plain prose that happens to contain a ':' (e.g. "Alice: ..."
                // or "3:00 PM") into spurious extra lines.
                NsDialogue.handle(state, trimmedRemaining, listener, false);
                return;
            }

            int splitAt = topLevelColonIndex(remaining);
            String part = splitAt < 0 ? remaining : remaining.substring(0, splitAt);
            remaining = splitAt < 0 ? "" : remaining.substring(splitAt + 1);
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) {
                continue;
            }
            int pcBefore = state.pc;
            VnEngine.State stateBefore = state.runState;
            runOneStatement(trimmedPart, state, listener, vnDir);
            if (state.runState != stateBefore || state.pc != pcBefore) {
                return;
            }
        }
    }

    /** The candidate command mnemonic for {@code text} if it's lowercase-leading (see {@link
     * #runOneStatement}'s note on why only lowercase-leading text is ever treated as a command),
     * else "" (meaning: definitely not if/notif, don't even look). */
    private static String commandNameOf(String text) {
        if (text.isEmpty() || !Character.isLowerCase(text.charAt(0))) {
            return "";
        }
        return NsLine.statement(text, text).firstToken();
    }

    private enum ChainClassification { COMMAND, DIALOGUE }

    /** Classifies {@code text} (already trimmed) the same way {@link #runOneStatement} would
     * decide to treat it, without executing anything -- used by {@link #executeChain} to decide
     * whether a colon inside it is a real chain separator (COMMAND: either a recognized command/
     * defsub-call, or an unrecognized lowercase mnemonic that {@code runOneStatement} will
     * silently skip -- either way it stays in the existing per-fragment split+dispatch flow) or
     * plain dialogue text (DIALOGUE: shown as one literal line, colons and all, never split). */
    private static ChainClassification classifyForChain(String text, NsExecState state) {
        if (INLINE_WAIT.matcher(text).matches()) {
            return ChainClassification.COMMAND;
        }
        boolean maybeCommand = !text.isEmpty()
                && (Character.isLowerCase(text.charAt(0)) || isShoutedCommandCandidate(text)
                        || isUnderscoreEscapedCommand(text));
        if (!maybeCommand) {
            return ChainClassification.DIALOGUE;
        }
        NsLine line = NsLine.statement(text, text);
        String token = line.firstToken();
        if (isUnderscoreEscapedCommand(text) || HANDLERS.containsKey(token) || state.definedSubs.contains(token)) {
            return ChainClassification.COMMAND;
        }
        // An all-caps line that isn't a real command is shouted dialogue; a lowercase-leading one
        // that isn't a real command is an unrecognized mnemonic runOneStatement silently skips --
        // see that method's own doc for why these two unresolved cases are treated differently.
        return isShoutedCommandCandidate(text) ? ChainClassification.DIALOGUE : ChainClassification.COMMAND;
    }

    /** Index of the first ':' outside a "quoted string", or -1 if none. */
    private static int topLevelColonIndex(String text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ':' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    private static void runOneStatement(String text, NsExecState state, VnEngine.Listener listener, File vnDir) {
        // Command mnemonics are always lowercase; requiring a lowercase leading character before
        // even attempting a dispatcher lookup avoids misreading ordinary capitalized dialogue that
        // happens to start with a word matching a command name (e.g. "Wait, no!" vs the "wait" command).
        // An ALL-CAPS line is also worth a lookup (real scripts sometimes write "SELECT"
        // instead of "select") -- isShoutedCommandCandidate keeps
        // this narrow (every letter must be uppercase), so an ordinary capitalized sentence still
        // reads as dialogue instead of risking a false match.
        // (if/notif never reach here -- executeChain intercepts those before splitting.)
        Matcher waitMatch = INLINE_WAIT.matcher(text);
        if (waitMatch.matches()) {
            handleInlineWait(waitMatch, state, listener);
            return;
        }
        boolean maybeCommand = !text.isEmpty()
                && (Character.isLowerCase(text.charAt(0)) || isShoutedCommandCandidate(text)
                        || isUnderscoreEscapedCommand(text));
        if (!maybeCommand) {
            NsDialogue.handle(state, text, listener, false);
            return;
        }
        NsLine line = NsLine.statement(text, text);
        if (isUnderscoreEscapedCommand(text)) {
            // "_name ..." -- ONScripter-EN's escape hatch for calling a command's TRUE native
            // implementation from inside a "defsub"-declared subroutine that overrides/wraps that
            // same bare name (e.g. "*csp"/"*bgm"/"*ld"
            // subroutines, defsub-registered to shadow the natives "csp"/"bgm"/"ld", each finishing by
            // calling "_csp"/"_bgm"/"_ld" to invoke the real thing). Always resolves straight to
            // HANDLERS, deliberately bypassing state.definedSubs -- that's the whole point of the
            // underscore. Before this, an underscore isn't a lowercase letter, so the ordinary
            // command-vs-dialogue gate above rejected it and the whole line (e.g.
            // "_csp %CspParam") was shown as literal garbage dialogue instead of ever running.
            NsCommandHandler nativeHandler = HANDLERS.get(line.firstToken().substring(1));
            if (nativeHandler != null) {
                nativeHandler.handle(state, NsTokenizer.parseArgs(line.argsText()), listener, vnDir);
            }
            return;
        }
        NsCommandHandler handler = HANDLERS.get(line.firstToken());
        if (handler != null) {
            handler.handle(state, NsTokenizer.parseArgs(line.argsText()), listener, vnDir);
            return;
        }
        if (state.definedSubs.contains(line.firstToken())) {
            // A "defsub"-registered pseudo-command call (e.g. "change_b \"階段夕\"", paired with a
            // "defsub change_b" + "*change_b: getparam $24 ..." declaration)
            // -- same shape as "gosub *name", plus queuing the call's own args for that
            // subroutine's own "getparam" to drain (see NsExecState.pendingSubParams's doc).
            Integer dest = state.labelIndex.get(line.firstToken());
            if (dest != null) {
                state.callStack.push(state.pc);
                state.pendingSubParams = new ArrayList<>(NsTokenizer.parseArgs(line.argsText()));
                state.pc = dest;
            }
            return;
        }
        if (isShoutedCommandCandidate(text)) {
            // An all-caps line that ISN'T a real command is just shouted dialogue, not a mnemonic
            // that happened to fail the lowercase gate -- show it rather than dropping it.
            NsDialogue.handle(state, text, listener, false);
            return;
        }
        // Else: a lowercase-leading but unrecognized mnemonic -- real scripts routinely invoke
        // commands and defsub-declared pseudo-commands well outside this core subset (a single
        // script can easily use hundreds of distinct such calls for its own custom menu system),
        // so an unknown one is silently skipped, never shown as dialogue -- misreading it as prose would
        // flash garbage text at the player. The tradeoff is the mirror image of the comment-
        // stripping one above: a real English-original game's own lowercase-starting dialogue line
        // would also be silently dropped here, pending full command-table coverage.
    }

    /** True if {@code text} leads with an underscore immediately followed by a lowercase letter --
     * ONScripter-EN's escape-hatch convention for calling a command's TRUE native implementation
     * from inside a "defsub"-declared subroutine that overrides/wraps that same bare name (see
     * {@link #runOneStatement}'s "_"-handling branch for the actual dispatch). An underscore isn't
     * itself a lowercase letter, so without this
     * check such a line would fail every other "is this a command" test here and misread as plain
     * dialogue. */
    private static boolean isUnderscoreEscapedCommand(String text) {
        return text.length() > 1 && text.charAt(0) == '_' && Character.isLowerCase(text.charAt(1));
    }

    /** Executes an {@link #INLINE_WAIT}-matched line -- see that constant's own doc for the real
     * semantics being approximated. "!w"/"!d" pause for the given milliseconds, converted to
     * frames the same way the "wait" command already does, and only actually block when
     * {@link NsExecState#delaysEnabled} is on (e-ink/instant-text mode never blocks here either,
     * matching "wait"'s own tolerance). "!s"/"!sd" (text-display speed) has no host surface to
     * apply to -- e-ink mode is always instant, and non-eink typewriter speed is a host Prefs
     * setting the script can't reach -- so it's a safe no-op. */
    private static void handleInlineWait(Matcher m, NsExecState state, VnEngine.Listener listener) {
        String ms = m.group(1) != null ? m.group(1) : m.group(2);
        if (ms == null) {
            return; // "!s<N>"/"!sd": text-speed setting, no host surface, nothing to do
        }
        if (!state.delaysEnabled || ms.isEmpty()) {
            return;
        }
        long millis = Long.parseLong(ms);
        if (millis <= 0) {
            return;
        }
        int frames = Math.round(millis * 60f / 1000f);
        state.runState = VnEngine.State.WAITING_DELAY;
        listener.onDelay(frames);
    }

    /** True if every letter in {@code text} is uppercase (and it has at least one letter) -- the
     * narrow signal used to also try a command-table lookup for an ALL-CAPS line, without risking a
     * false match against an ordinary capitalized English sentence (which mixes case). */
    private static boolean isShoutedCommandCandidate(String text) {
        boolean sawLetter = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                sawLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return sawLetter;
    }

    private static void handleConditional(boolean negate, String argsText, NsExecState state,
                                            VnEngine.Listener listener, File vnDir) {
        Matcher m = CONDITION.matcher(argsText);
        if (!m.matches()) {
            return; // malformed/no consequent: no-op, same tolerance as an unrecognized command
        }
        NsArg left = firstArg(m.group(1));
        String op = m.group(2);
        NsArg right = firstArg(m.group(3));
        boolean result = evalCondition(left, op, right, state);
        String rest = m.group(4);

        // Real NScripter lets an "if" chain further "operand OP operand" comparisons onto the same
        // line, joined with "&" (logical AND) or "|" (logical OR) and combined strictly left to
        // right (no operator-precedence mixing) -- e.g. "if %BtnRes >= 500 & %BtnRes <= %2+499 ..."
        // and 3-deep chains like "if %11=0 & %12=0 & %13=0 return" occur in real scripts. Without
        // this, the "& ..." remainder
        // was misread as the consequent itself: since it starts with '&' (not a lowercase command
        // and not all-uppercase), executeChain's own dialogue-vs-command classification showed it as
        // literal text instead of evaluating it -- and since the first comparison alone was already
        // enough to make the (wrongly parsed) "if" true every time, that produced a menu dispatch
        // never running and the same garbage line being redisplayed on every
        // tap forever. Each loop iteration here peels off one more "CONNECTOR operand OP operand"
        // segment; whatever's left once the text no longer starts that way is the real consequent.
        while (true) {
            char c0 = rest.isEmpty() ? ' ' : rest.charAt(0);
            if (c0 != '&' && c0 != '|') {
                break;
            }
            Matcher next = CONDITION.matcher(rest.substring(1).trim());
            if (!next.matches()) {
                break; // doesn't actually extend the chain -- treat the rest as the consequent,
                       // same tolerance as any other unparseable trailing text in this dispatcher
            }
            NsArg left2 = firstArg(next.group(1));
            String op2 = next.group(2);
            NsArg right2 = firstArg(next.group(3));
            boolean thisResult = evalCondition(left2, op2, right2, state);
            result = c0 == '&' ? (result && thisResult) : (result || thisResult);
            rest = next.group(4);
        }

        if (negate) {
            result = !result;
        }
        if (result) {
            executeChain(rest, state, listener, vnDir);
        }
    }

    private static NsArg firstArg(String token) {
        List<NsArg> parsed = NsTokenizer.parseArgs(token);
        return parsed.isEmpty() ? NsTokenizer.parseArgs("0").get(0) : parsed.get(0);
    }

    private static boolean evalCondition(NsArg left, String op, NsArg right, NsExecState state) {
        int cmp;
        if (left.kind == NsArg.Kind.STR_VAR_EXPR) {
            cmp = NsExpr.string(left, state).compareTo(NsExpr.string(right, state));
        } else {
            cmp = Long.compare(NsExpr.numeric(left, state), NsExpr.numeric(right, state));
        }
        switch (op) {
            case "==":
            case "=": // bare "=" is accepted as a synonym for "==" -- both forms occur in real scripts
                return cmp == 0;
            case "!=":
                return cmp != 0;
            case ">":
                return cmp > 0;
            case "<":
                return cmp < 0;
            case ">=":
                return cmp >= 0;
            case "<=":
                return cmp <= 0;
            default:
                return false;
        }
    }

    private static String stripStar(String label) {
        return label.startsWith("*") ? label.substring(1) : label;
    }

    private static final java.util.Set<String> COLOR_NAMES =
            new java.util.HashSet<>(java.util.Arrays.asList("white", "black", "gray", "grey"));

    private static boolean isColorToken(String s) {
        return s.startsWith("#") || COLOR_NAMES.contains(s.toLowerCase(Locale.ROOT));
    }

    /** Resolves an asset path against the effective base directory -- the VN's own root, unless
     * "nsadir" (see {@link NsExecState#nsaDir}'s own doc) declared a subdirectory to use instead. */
    private static File resolveAsset(NsExecState state, File vnDir, String filename) {
        File base = state.nsaDir.isEmpty() ? vnDir : new File(vnDir, state.nsaDir);
        return NsAssetResolver.resolve(base, stripFileTag(filename));
    }

    /** Resolves a bareword argument through "stralias"'s literal-constant table (see its handler's
     * doc and {@link NsExecState#barewordConstants}) before treating it as a file path -- e.g.
     * "stralias bgcoffee,\"data\\bg_coffee.png\"" then later "bg bgcoffee,10". A "$var"-style
     * argument is resolved to that string variable's actual
     * stored value via {@link NsExpr#string} -- e.g. "*bgm"/"*ld" wrapper
     * subroutines (see {@link #runOneStatement}'s underscore-escape doc) receive their real
     * filename via "getparam" into a string variable, then pass it on as "_bgm $SoundFileName" /
     * "_ld l,$LdParam2tmp3,...". Without routing this through {@code NsExpr.string}, {@code
     * arg.value} for a STR_VAR_EXPR is just the bare variable NAME text with its leading '$'
     * stripped (e.g. literally "SoundFileName"), not the file path actually stored in it -- so
     * every asset load funneled through such a wrapper would resolve to a nonexistent path named
     * after the variable itself instead of the real file. Any other argument kind (a quoted
     * string, an unregistered bareword, ...) resolves the same as before {@code NsExpr.string}'s
     * own pass-through for those kinds. */
    private static String resolveFileArg(NsArg arg, NsExecState state) {
        if (arg.kind == NsArg.Kind.BAREWORD) {
            String constant = state.barewordConstants.get(arg.value);
            if (constant != null) {
                return constant;
            }
        }
        return NsExpr.string(arg, state);
    }

    /** Strips a leading NScripter file-load tag, e.g. ":a;poster.png" -- alpha-blend using the
     * image's corner pixel as its transparent color, a tag commonly seen on
     * character portraits ("ld c,\":a;kana2.png\",3" and similar). The tag has no on-disk/archive
     * representation of its own: left in place, every tagged lookup misses, since the real asset
     * is just "kana2.png", never ":a;kana2.png". */
    private static String stripFileTag(String filename) {
        if (filename.startsWith(":")) {
            int semi = filename.indexOf(';');
            if (semi >= 0) {
                return filename.substring(semi + 1);
            }
        }
        return filename;
    }

    /** Extracts the literal display text from an "lsp"-style ":s/…;…" text-sprite spec (e.g.
     * ":s/36,38,0;#FFFFFF#a9a9a9`Start game" -> "Start game")
     * -- the font-size/pitch and one-or-two "#RRGGBB" color fields are discarded entirely, since
     * this host maps such button sprites onto its native choice UI rather than actually rendering
     * styled text as a sprite. Returns null if {@code value} isn't ":s"-tagged. */
    private static String textSpriteLabel(String value) {
        if (value.length() < 2 || value.charAt(0) != ':' || Character.toLowerCase(value.charAt(1)) != 's') {
            return null;
        }
        int semi = value.indexOf(';');
        if (semi < 0) {
            return null;
        }
        String rest = value.substring(semi + 1);
        while (rest.length() >= 7 && rest.charAt(0) == '#') {
            rest = rest.substring(7);
        }
        return NsDialogue.stripLeadingBacktick(rest);
    }

    /** A rough, human-recognizable stand-in for an image sprite's own filename -- e.g.
     * "dat\menu\hajime.jpg" -> "hajime" -- used only as a "spbtn" fallback placeholder (see its
     * handler) when there's no real text label, so Japanese-authored
     * button graphics ("hajime.jpg" = "start", "tuduki.jpg" = "continue", etc. -- not translated,
     * but still more recognizable than a bare button id) read as something rather than nothing. */
    private static String fileNameHint(String taggedSpec) {
        String path = stripFileTag(taggedSpec);
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** How to decode an "ld"-referenced file's transparency, from its optional ":x;" tag:
     * ":a;" -> alpha mask (double-width, e.g. "ld c,\":a;kana2.png\",3", a pattern commonly used for
     * character portraits); ":c;" -> fully opaque; untagged (e.g.
     * "ld l,\"poster.png\",3") -> real NScripter's own default, a top-left-pixel
     * color-key, same as an explicit ":l;" tag. ":r;" (top-right key) is supported too, though
     * rarer in practice; any other/unrecognized tag falls back to opaque.
     *
     * <p>The type letter isn't always immediately followed by ';' -- real tags can carry extra
     * slash-separated effect parameters first, e.g. ":a/2,0,3;May/System/Title_Text.jpg" (a pattern
     * seen on title-screen text/buttons), same shape {@link
     * #stripFileTag} already tolerates by searching for ';' rather than assuming it's at a fixed
     * offset -- this must scan the same way, not just check position 2, or a tagged file like that
     * one silently falls through to the untagged default instead of being recognized as ":a". */
    private static VnEngine.SpriteTransparency transparencyFor(String filename) {
        if (filename.length() > 1 && filename.charAt(0) == ':') {
            int semi = filename.indexOf(';');
            if (semi > 1) {
                switch (Character.toLowerCase(filename.charAt(1))) {
                    case 'a':
                        return VnEngine.SpriteTransparency.ALPHA_MASK;
                    case 'l':
                        return VnEngine.SpriteTransparency.TOPLEFT_KEY;
                    case 'c':
                        return VnEngine.SpriteTransparency.OPAQUE;
                    default:
                        return VnEngine.SpriteTransparency.OPAQUE;
                }
            }
        }
        return VnEngine.SpriteTransparency.TOPLEFT_KEY; // untagged: NScripter's own default
    }

    /** The cell count from a real ONScripter multi-cell alpha-mask tag, e.g. ":a/2,0,3;file" -> 2.
     * For example, "lsp 0,\":a/2,0,3;
     * May/System/Title_Text.jpg\",0,0" decodes to a 1280-wide image that is genuinely 2 side-by-
     * side [color|mask] cells of 320+320 each -- byte-for-byte identical to each other -- NOT one
     * plain 640+640 pair; treating it as the latter corrupts the alpha across roughly half the
     * image). Only ":a/" (alpha mask with parameters) carries this; a plain ":a;" (no slash) or any
     * other tag/untagged file is 1 cell. See {@link VnEngine.Listener#onSprite}'s own doc for how
     * this is actually used (only cell 0 is ever composited/shown; this host has no sprite-sheet
     * animation of its own). */
    private static int alphaMaskCellsFor(String filename) {
        if (filename.length() > 2 && filename.charAt(0) == ':'
                && Character.toLowerCase(filename.charAt(1)) == 'a' && filename.charAt(2) == '/') {
            int semi = filename.indexOf(';');
            if (semi > 3) {
                String params = filename.substring(3, semi);
                int comma = params.indexOf(',');
                String first = (comma >= 0 ? params.substring(0, comma) : params).trim();
                try {
                    int n = Integer.parseInt(first);
                    if (n > 0) {
                        return n;
                    }
                } catch (NumberFormatException e) {
                    // Malformed cell count: tolerate, fall through to the single-cell default.
                }
            }
        }
        return 1;
    }

    /** True for NScripter's fixed left/center/right character-stand position letters -- real
     * "ld"/"cl" call sites use one of these, never a bare number. */
    private static boolean isStandPositionToken(String token) {
        return token.equalsIgnoreCase("l") || token.equalsIgnoreCase("c") || token.equalsIgnoreCase("r");
    }

    /** Sentinel layer numbers for "ld"'s 3 fixed left/center/right character-stand positions:
     * these are a genuinely
     * SEPARATE, fixed-size internal array from "lsp"/"csp"'s numbered sprites
     * (which real scripts index with their own arbitrary numbers, sometimes well up
     * into the 700s) -- the two never collide in real ONScripter, no matter what
     * numbers a script happens to pick for its "lsp" layers. This engine funnels both through the
     * same onSprite/onSpriteCleared(int layer) callback, so plain 0/1/2 (this constant's own
     * earlier, less correct value) would collide with a real "lsp 0,..."/"lsp 1,..."/"lsp 2,..." --
     * in practice, "lsp 1,...(body)"/"lsp 0,...(head)" sprites were
     * being silently wiped out by every subsequent "bg" command's own "clear the 3 stand positions"
     * step (see the "bg" handler below), since bg had no way to tell an ld-stand-position clear
     * apart from an unrelated numbered lsp layer that just happened to share the number. Chosen far
     * outside any plausible script-chosen sprite number to stay collision-free. */
    // Package-private (not private) so tests can assert on them directly rather than duplicating
    // the literal values.
    static final int TACHI_LAYER_LEFT = Integer.MAX_VALUE - 2;
    static final int TACHI_LAYER_CENTER = Integer.MAX_VALUE - 1;
    static final int TACHI_LAYER_RIGHT = Integer.MAX_VALUE;

    /** Resolves an "ld"/"cl"/"csp" position token to a stable slot index. 'l'/'c'/'r' resolve to the
     * fixed {@link #TACHI_LAYER_LEFT}/{@link #TACHI_LAYER_CENTER}/{@link #TACHI_LAYER_RIGHT}
     * sentinels (kept distinct from each other so e.g. "cl c" can't accidentally clear what "ld l"
     * put up, and distinct from real numbered "lsp" layers -- see those constants' own doc). Falls
     * back to a genuine numeric layer for scripts that do use numbered layers. */
    private static int layerIndexFor(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "l":
                return TACHI_LAYER_LEFT;
            case "c":
                return TACHI_LAYER_CENTER;
            case "r":
                return TACHI_LAYER_RIGHT;
            default:
                try {
                    return Integer.parseInt(token.trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
        }
    }

    /** The pixel-x sentinel for an "ld" position token: real x/y for 'l'/'c'/'r' depends on the
     * decoded image's own size (see {@link VnEngine.Listener}'s AUTO_POSITION_* doc), which this
     * plain-Java engine layer has no way to know -- so the host resolves it instead. A numeric
     * layer has no stand-position convention of its own; it keeps a literal 0. */
    private static int autoPositionXFor(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "l":
                return VnEngine.Listener.AUTO_POSITION_LEFT;
            case "c":
                return VnEngine.Listener.AUTO_POSITION_CENTER;
            case "r":
                return VnEngine.Listener.AUTO_POSITION_RIGHT;
            default:
                return 0;
        }
    }

    /** True if {@code text}'s last non-whitespace character is a ',' outside any quoted string --
     * the signal that a variadic command's (namely "select"'s) argument list continues onto the
     * next physical line rather than ending here. */
    private static boolean endsWithTopLevelComma(String text) {
        String t = text.stripTrailing();
        if (t.isEmpty()) {
            return false;
        }
        boolean inQuotes = false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '"') {
                inQuotes = !inQuotes;
            }
        }
        return !inQuotes && t.charAt(t.length() - 1) == ',';
    }

    /** Parses a "select"-family option list -- "\"text1\",label1,\"text2\",label2,..." -- shared by
     * "select" and "csel" (see their handlers; both dispatch from the same parser in real
     * ONScripter-EN). Real scripts routinely spread this list across several physical lines, each
     * one but the last ending in a trailing comma (e.g. a bare "select" line followed by
     * "\"`Begin\",*s_1,\n\"`Continue\",*load,\n
     * \"`Quit\",*quit") -- the line-per-command dispatch loop only ever hands a handler the command
     * line's own (empty) args, so this pulls in continuation lines directly, advancing state.pc past
     * every one it consumes. Appends parsed (text, label) pairs into {@code outTexts}/{@code
     * outLabels} (both may already be non-empty; this never clears them itself). */
    private static void collectSelectPairs(List<NsArg> initialArgs, NsExecState state,
                                             List<String> outTexts, List<String> outLabels) {
        List<NsArg> allArgs = new ArrayList<>(initialArgs);
        String lastRaw = state.pc > 0 ? state.lines.get(state.pc - 1) : "";
        while ((allArgs.isEmpty() || endsWithTopLevelComma(lastRaw)) && state.pc < state.lines.size()) {
            NsLine next = NsTokenizer.classify(state.lines.get(state.pc));
            if (next.type != NsLine.Type.STATEMENT) {
                break; // a label/blank/comment ends the block: stop rather than misconsume it
            }
            lastRaw = state.lines.get(state.pc);
            allArgs.addAll(NsTokenizer.parseArgs(next.text));
            state.pc++;
        }
        for (int i = 0; i + 1 < allArgs.size(); i += 2) {
            outTexts.add(NsDialogue.stripLeadingBacktick(NsExpr.string(allArgs.get(i), state)));
            outLabels.add(stripStar(allArgs.get(i + 1).value));
        }
    }

    private static Map<String, NsCommandHandler> buildHandlers() {
        Map<String, NsCommandHandler> h = new HashMap<>();

        // ---- Control flow ---------------------------------------------------------------
        // "game" -- marks the end of the "*define" header and the true start of a fresh
        // playthrough. It isn't just a mode-flag flip -- it unconditionally jumps,
        // the exact same mechanism "goto" uses, to a label literally
        // named "*start". Before this was implemented, "game" was an unrecognized lowercase
        // mnemonic that silently no-op'd, so execution just fell through sequentially into
        // whatever came physically next in the source -- typically a long
        // run of "defsub"-declared utility subroutines (csp/bgm/vp/ld1/after_load/customsel) never
        // meant to be reached this way, ending in a dead menu loop that could never progress into
        // the real story (found at its own "*start" label, much further down the file). Tolerates a
        // missing "*start" label the same way "goto" tolerates any unresolved target.
        h.put("game", (state, args, listener, vnDir) -> {
            Integer dest = state.labelIndex.get("start");
            if (dest != null) {
                state.pc = dest;
            }
        });
        h.put("goto", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            String target = stripStar(args.get(0).value);
            Integer dest = state.labelIndex.get(target);
            if (dest != null) {
                state.pc = dest;
            }
        });
        h.put("gosub", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            String target = stripStar(args.get(0).value);
            Integer dest = state.labelIndex.get(target);
            if (dest != null) {
                state.callStack.push(state.pc);
                state.pc = dest;
            }
        });
        h.put("return", (state, args, listener, vnDir) -> {
            if (!state.callStack.isEmpty()) {
                state.pc = state.callStack.pop();
            }
        });
        h.put("jumpf", (state, args, listener, vnDir) -> {
            int i = state.pc;
            while (i < state.lines.size()) {
                if (NsTokenizer.classify(state.lines.get(i)).type == NsLine.Type.TILDE) {
                    state.pc = i + 1;
                    return;
                }
                i++;
            }
            state.pc = state.lines.size(); // no marker found: run off the end, same as VNDS's goto-miss
        });
        // "for VAR=FROM to TO [step STEP]" / "next" -- a real structured loop, NOT comma-separated
        // like every other command's argument list -- e.g.
        // "for %0=701 to 709 ... csp %0 ... next" is a common idiom to bulk-clear a
        // numbered range of sprite layers. Before this was implemented, both were unrecognized
        // lowercase mnemonics that silently no-op'd, so a loop body between them ran exactly once
        // (with whatever stale value its own loop variable already happened to hold) instead of
        // once per FROM..TO step -- e.g. a "clear layers 701..709" cleanup could silently fail
        // to clear most of them, leaving right-click menu button sprites
        // stuck on screen after the menu was supposedly closed.
        h.put("for", (state, args, listener, vnDir) -> {
            if (args.isEmpty() || args.get(0).kind != NsArg.Kind.NUM_VAR_EXPR) {
                return;
            }
            Matcher m = FOR_LOOP.matcher(args.get(0).value);
            if (!m.matches()) {
                return;
            }
            NsArg varArg = new NsArg(NsArg.Kind.NUM_VAR_EXPR, "%" + m.group(1), m.group(1));
            int varIndex = NsExpr.numVarIndex(varArg, state);
            long from = Long.parseLong(m.group(2));
            long to = Long.parseLong(m.group(3));
            long step = m.group(4) != null ? Long.parseLong(m.group(4)) : 1L;
            state.numVars.put(varIndex, from);
            boolean brokeImmediately = (step > 0 && from > to) || (step < 0 && from < to);
            // state.pc already points right after this "for" line (runLoop increments it before
            // dispatch), exactly the resume position real ONScripter's own next_script captures.
            state.forStack.push(new NsExecState.ForFrame(varIndex, to, step, state.pc, brokeImmediately));
        });
        h.put("next", (state, args, listener, vnDir) -> {
            if (state.forStack.isEmpty()) {
                return; // real ONScripter tolerates a stray "next" outside any "for" too
            }
            NsExecState.ForFrame frame = state.forStack.peek();
            long val = state.numVars.getOrDefault(frame.varIndex, 0L);
            if (!frame.brokeImmediately) {
                val += frame.step;
                state.numVars.put(frame.varIndex, val);
            }
            boolean done = frame.brokeImmediately
                    || (frame.step > 0 && val > frame.to)
                    || (frame.step < 0 && val < frame.to);
            if (done) {
                state.forStack.pop();
            } else {
                state.pc = frame.resumePc;
            }
        });
        // "defsub name" -- declares "name" as a subroutine-with-parameters pseudo-command (a real
        // ONScripter-EN extension; a "*sys_define"-style block that
        // "gosub"s once at startup and registers dozens of names this way, e.g.
        // "defsub change_b", is a common pattern). Registration only; the actual dispatch (treating a later bareword
        // call to that name like "gosub *name" plus queuing its args) lives in runOneStatement,
        // since it has to compete with the real HANDLERS lookup there.
        h.put("defsub", (state, args, listener, vnDir) -> {
            if (!args.isEmpty()) {
                state.definedSubs.add(args.get(0).value);
            }
        });
        // "getparam var1,var2,..." -- drains state.pendingSubParams (set by the defsub-pseudo-
        // command call site, see runOneStatement) positionally into the given variables, string or
        // numeric per each target's own kind. A typical "*change_b"-style
        // subroutine starts with a single "getparam $24" to receive its one call argument.
        // Fewer call-site args than vars leaves the remaining vars untouched, same tolerance as an
        // out-of-range/unset variable read elsewhere in this dispatcher.
        h.put("getparam", (state, args, listener, vnDir) -> {
            for (NsArg target : args) {
                if (state.pendingSubParams.isEmpty()) {
                    return;
                }
                NsArg param = state.pendingSubParams.remove(0);
                if (target.kind == NsArg.Kind.STR_VAR_EXPR) {
                    state.strVars.put(NsExpr.strVarIndex(target, state), NsExpr.string(param, state));
                } else {
                    state.numVars.put(NsExpr.numVarIndex(target, state), NsExpr.numeric(param, state));
                }
            }
        });

        // ---- Dialogue formatting ----------------------------------------------------------
        h.put("br", (state, args, listener, vnDir) -> listener.onTextLine(""));
        h.put("cr", (state, args, listener, vnDir) -> listener.onTextLine(""));

        // ---- Variables ----------------------------------------------------------------------
        NsCommandHandler movHandler = (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            NsArg target = args.get(0);
            if (target.kind == NsArg.Kind.STR_VAR_EXPR) {
                int idx = NsExpr.strVarIndex(target, state);
                state.strVars.put(idx, NsExpr.string(args.get(1), state));
            } else {
                int idx = NsExpr.numVarIndex(target, state);
                state.numVars.put(idx, NsExpr.numeric(args.get(1), state));
            }
        };
        h.put("mov", movHandler);
        h.put("mov$", movHandler);

        h.put("add", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            NsArg target = args.get(0);
            if (target.kind == NsArg.Kind.STR_VAR_EXPR) {
                int idx = NsExpr.strVarIndex(target, state);
                state.strVars.put(idx, state.strVars.getOrDefault(idx, "") + NsExpr.string(args.get(1), state));
            } else {
                int idx = NsExpr.numVarIndex(target, state);
                state.numVars.put(idx, state.numVars.getOrDefault(idx, 0L) + NsExpr.numeric(args.get(1), state));
            }
        });
        h.put("sub", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            NsArg target = args.get(0);
            if (target.kind != NsArg.Kind.STR_VAR_EXPR) {
                int idx = NsExpr.numVarIndex(target, state);
                state.numVars.put(idx, state.numVars.getOrDefault(idx, 0L) - NsExpr.numeric(args.get(1), state));
            }
            // Subtracting from a string has no defined meaning here: no-op, same tolerance as an
            // unrecognized modifier gets elsewhere.
        });
        h.put("inc", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            int idx = NsExpr.numVarIndex(args.get(0), state);
            state.numVars.put(idx, state.numVars.getOrDefault(idx, 0L) + 1);
        });
        h.put("dec", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            int idx = NsExpr.numVarIndex(args.get(0), state);
            state.numVars.put(idx, state.numVars.getOrDefault(idx, 0L) - 1);
        });
        // Real NScripter has one unified variable-slot space per index -- slot N is addressable
        // both as a number ("%N") and as a string ("$N"), the same underlying cell either way (e.g.
        // "mov $10,\"VOICE\\ogg\\\"+$VoiceNum+\".ogg\"" then
        // "dwave 0,$10" reference the very same raw index 10 with no alias at all). "numalias"/
        // "stralias" just give that shared slot a friendly name -- scripts commonly declare
        // names (including several
        // only ever read/written as "$name", e.g. "$LdParam2"/"$LdParam2tmp3"/"$SoundFileName") via
        // "numalias" alone, without ever calling "stralias". Registering a name in only one of
        // state.numAliases/state.strAliases (as this dispatcher originally did) left every "$name"
        // reference for such a numalias-only name falling through strAliases's own "unknown alias:
        // default to slot 0" tolerance -- silently colliding every such string variable onto the
        // same slot 0, corrupting filenames built from them (e.g. "$LdParam2tmp3" resolving to an
        // empty/wrong path instead of the real sprite file). Each alias command below now mirrors
        // its registration into both tables so either prefix works regardless of which command a
        // script happens to declare it with.
        h.put("numalias", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            String name = args.get(0).value;
            int slot = (int) NsExpr.numeric(args.get(1), state);
            state.numAliases.put(name, slot);
            state.strAliases.put(name, slot);
        });
        h.put("stralias", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            NsArg value = args.get(1);
            if (value.kind == NsArg.Kind.STRING_LITERAL) {
                // A literal-string 2nd argument (e.g.
                // "stralias bgcoffee,\"data\\bg_coffee.png\"", later referenced bare as
                // "bg bgcoffee,10") defines a bareword text CONSTANT, not a variable-slot alias --
                // see NsExecState.barewordConstants's doc. This mode has no numeric-slot
                // counterpart, so it doesn't touch numAliases/strAliases at all.
                state.barewordConstants.put(args.get(0).value, value.value);
                return;
            }
            String name = args.get(0).value;
            int slot = (int) NsExpr.numeric(value, state);
            state.strAliases.put(name, slot);
            state.numAliases.put(name, slot);
        });

        // ---- Image / background / sprite ---------------------------------------------------
        h.put("bg", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            // Real "bg" always clears the left/center/right character-portrait layers first,
            // before touching the background itself. Those are the TACHI_LAYER_* sentinels (see
            // their own doc), deliberately NOT
            // plain 0/1/2 -- real scripts commonly use "lsp 0,...(head)"/"lsp 1,...(body)" for their
            // numbered sprites, which real ONScripter's separate internal sprite array leaves
            // completely untouched by "bg" (only its own distinct stand-position array gets cleared);
            // clearing plain layers 0/1/2 here silently wiped out those unrelated lsp sprites on
            // every background change, e.g. showing a character's head but not their body.
            listener.onSpriteCleared(TACHI_LAYER_LEFT);
            listener.onSpriteCleared(TACHI_LAYER_CENTER);
            listener.onSpriteCleared(TACHI_LAYER_RIGHT);
            String file = resolveFileArg(args.get(0), state);
            if (file.equals("~")) {
                listener.onBackground(null, 0, VnEngine.SpriteTransparency.OPAQUE, 1);
            } else if (isColorToken(file)) {
                // Solid-color backgrounds ("bg #FFFFFF,..."/"bg white,..." -- a pattern real
                // scripts do use) aren't representable through onBackground(File,int): this
                // engine layer is plain Java with no Bitmap/Canvas access, matching
                // vnds.ScriptEngine's own zero-Android-dependency design. Leaving the current
                // background alone is a safer degradation than clearing to nothing or trying to
                // resolveAsset("white") as a file that doesn't exist. A real fix needs a new
                // Listener callback (e.g. onBackgroundColor) -- not yet added.
            } else {
                // Unlike "ld"/"lsp", a background's transparency is NOT tag-driven: real
                // ONScripter unconditionally sets
                // "bg_info.trans_mode = AnimationInfo::TRANS_COPY" and "bg_info.num_of_cells = 1"
                // every time a new background is set, regardless of any ":a;"/":l;" tag on the
                // filename argument -- the background layer is always a plain opaque copy. Any tag
                // that happens to appear on a "bg"-loaded filename is stripped for path resolution
                // (see resolveAsset/stripFileTag) but otherwise ignored, the same way real
                // ONScripter ignores it here -- honoring it would incorrectly color-key/alpha-mask
                // real background art (it would eat a chunk of the
                // background into a jagged transparent hole wherever the image's own corner pixel
                // color recurs elsewhere in the scene).
                listener.onBackground(resolveAsset(state, vnDir, file), 0, VnEngine.SpriteTransparency.OPAQUE, 1);
            }
        });
        h.put("ld", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            String posToken = args.get(0).value;
            int layer = layerIndexFor(posToken);
            String file = resolveFileArg(args.get(1), state);
            // The 3rd argument is a transition/effect id (e.g. a fade), not a coordinate -- real
            // scripts never pass actual x/y here (every "ld" call site conventionally
            // uses exactly this 3-arg position/file/effect form). 'l'/'c'/'r' get an
            // AUTO_POSITION_* sentinel instead of a literal offset -- see autoPositionXFor's doc --
            // horizontally justified by the host, and bottom-aligned like a real standing character
            // portrait; a bare numeric layer has no such convention, so it keeps a literal (0,0).
            int x = isStandPositionToken(posToken) ? autoPositionXFor(posToken) : 0;
            int y = isStandPositionToken(posToken) ? VnEngine.Listener.AUTO_POSITION_BOTTOM : 0;
            listener.onSprite(layer, x, y, resolveAsset(state, vnDir, file), transparencyFor(file), alphaMaskCellsFor(file));
        });
        NsCommandHandler clearLayerHandler = (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            NsArg arg = args.get(0);
            // "a"/"all" and the fixed "l"/"c"/"r" stand-position letters are bareword tokens with
            // their own special meaning (see isStandPositionToken/layerIndexFor's own docs) -- any
            // other kind of argument, most importantly a "%var" numeric-variable reference (e.g. a
            // "for %0=701 to 709 ... csp %0 ... next" cleanup loop), must be
            // EVALUATED to its actual stored numeric value via NsExpr.numeric, not treated as a
            // literal layer number equal to the variable's own index/name. Before this was fixed,
            // "csp %0" always cleared layer 0 (the literal text after '%'), regardless of what value
            // %0 actually held at the time -- so a loop meant to clear layers 701-709 only ever
            // cleared layer 0, nine times, leaving the real sprites stuck on screen.
            if (arg.kind == NsArg.Kind.BAREWORD) {
                if (arg.value.equalsIgnoreCase("a") || arg.value.equalsIgnoreCase("all")) {
                    listener.onSpriteCleared(-1);
                    return;
                }
                if (isStandPositionToken(arg.value)) {
                    listener.onSpriteCleared(layerIndexFor(arg.value));
                    return;
                }
            }
            listener.onSpriteCleared((int) NsExpr.numeric(arg, state));
        };
        h.put("cl", clearLayerHandler);
        h.put("csp", clearLayerHandler);
        h.put("caption", (state, args, listener, vnDir) -> {
            // Window title text: no host surface for this in a mobile reader, safe no-op.
        });
        h.put("monocro", (state, args, listener, vnDir) -> {
            // The host already forces grayscale in e-ink mode; nothing to layer on top of.
        });
        // "nsadir \"dir\"" -- points asset/archive resolution at a subdirectory of the VN's own
        // root instead of the root itself -- e.g. a script whose actual
        // "arc.nsa" and loose cursor bitmaps sit under "data/", declared via "nsadir \"data\"" in
        // its "*define" header. Before this was recognized, every asset load still looked directly
        // under the VN root (this engine's original, and until now only, assumption), so nothing in
        // that subdirectory -- meaning every image and sound in that game -- ever resolved at all.
        h.put("nsadir", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            state.nsaDir = NsExpr.string(args.get(0), state).replace('\\', '/');
        });

        // ---- Choice ---------------------------------------------------------------------------
        h.put("select", (state, args, listener, vnDir) -> {
            List<String> optionTexts = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            collectSelectPairs(args, state, optionTexts, labels);
            if (optionTexts.isEmpty()) {
                return;
            }
            state.pendingChoiceLabels = labels;
            state.runState = VnEngine.State.WAITING_CHOICE;
            state.lastChoiceOptionTexts = new ArrayList<>(optionTexts);
            state.lastChoiceLabels = new ArrayList<>(labels);
            state.lastChoiceBtnwaitVarIndex = null;
            state.lastChoiceButtonIds = null;
            listener.onChoices(optionTexts);
        });
        // "csel \"text1\",label1,\"text2\",label2,..." -- real ONScripter-EN
        // dispatches "select"/"selnum"/"selgosub"/"csel" all from one shared parser: "csel" parses the exact same
        // option-list syntax as "select" (see collectSelectPairs), but instead of blocking on a
        // click or building native buttons, it just records the (text,label) pairs into
        // state.customSelectTexts/Labels and jumps straight to a fixed "*customsel" label -- where
        // the script itself is expected to lay out custom clickable buttons via "cselbtn" at each
        // index, then resolve the click via "selectbtnwait" (== "btnwait") plus "cselgoto"/
        // "getcselnum" (see those handlers, just below). Before this was implemented, "csel" was a
        // silently-skipped unrecognized command, so nothing ever populated the list those other
        // commands depend on -- meaning a whole custom-select-based menu system (a common pattern:
        // a script's own "*customsel" label, reachable and used for its actual save/load/
        // options screens) could never work at all, regardless of how "cselbtn"/"cselgoto"/
        // "getcselnum" themselves were implemented.
        h.put("csel", (state, args, listener, vnDir) -> {
            state.customSelectTexts = new ArrayList<>();
            state.customSelectLabels = new ArrayList<>();
            collectSelectPairs(args, state, state.customSelectTexts, state.customSelectLabels);
            Integer dest = state.labelIndex.get("customsel");
            if (dest != null) {
                state.pc = dest;
            }
        });

        // ---- Sound / music ----------------------------------------------------------------
        h.put("wave", (state, args, listener, vnDir) -> playSound(args, state, listener, vnDir, 1));
        h.put("playse", (state, args, listener, vnDir) -> playSound(args, state, listener, vnDir, 1));
        h.put("waveloop", (state, args, listener, vnDir) -> playSound(args, state, listener, vnDir, -1));
        // "dwave channel,\"file\"[,times]" -- the actual one-shot-sound mnemonic real
        // scripts commonly use, often exclusively, in place of plain "wave"/"playse". The channel
        // argument has no equivalent in the host's single-sfx-player model
        // (same simplification vnds.ScriptEngine's "sound" already made), so it's just skipped.
        h.put("dwave", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            playSound(args.subList(1, args.size()), state, listener, vnDir, 1);
            // Real "dwave channel,\"file\"<TAB>text" sometimes carries a same-line display-text
            // cue right after the filename (see NsTokenizer.parseArgs's doc for why that arrives
            // here as a separate 3rd NsArg rather than glued onto the filename) -- show it through
            // the ordinary dialogue path instead of silently dropping it. A genuine numeric
            // "times" 3rd argument (e.g. "dwave 0,\"file.ogg\",1") is left alone.
            if (args.size() > 2) {
                NsArg trailing = args.get(2);
                if (trailing.kind != NsArg.Kind.NUMBER_LITERAL && !trailing.value.trim().isEmpty()) {
                    NsDialogue.handle(state, trailing.value, listener, false);
                }
            }
        });
        h.put("dwavestop", (state, args, listener, vnDir) -> listener.onSound(null, 1));
        // Plain "wavestop" (no channel argument) -- the counterpart to "wave"/"waveloop", distinct
        // from the channeled "dwave"/"dwavestop" pair above. The actual stop mnemonic scripts
        // use to end a "waveloop"-driven ambient/BGM loop; without this
        // handler it was an unrecognized lowercase command, silently no-op'd, so the loop never stopped.
        h.put("wavestop", (state, args, listener, vnDir) -> listener.onSound(null, 1));
        // "bgm"/"bgmonce"/"mp3"/"mp3loop"/"mp3save" -- in real ONScripter-EN, all FIVE mnemonics
        // bind to the exact same underlying command, differing only in a looping flag this host's
        // single onMusic(File) callback
        // has no equivalent for (it always just plays/replaces the current track; looping is a host/
        // UI-level playback concern, not something the script layer distinguishes here). Some
        // scripts use "mp3loop"/"mp3" exclusively and never call "bgm" at all -- before
        // these aliases existed, background music using those mnemonics never played.
        NsCommandHandler musicHandler = (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            String file = resolveFileArg(args.get(0), state);
            listener.onMusic(file.equals("~") ? null : resolveAsset(state, vnDir, file));
        };
        h.put("bgm", musicHandler);
        h.put("playbgm", musicHandler);
        h.put("bgmonce", musicHandler);
        h.put("mp3", musicHandler);
        h.put("mp3loop", musicHandler);
        h.put("mp3save", musicHandler);
        NsCommandHandler stopMusicHandler = (state, args, listener, vnDir) -> listener.onMusic(null);
        h.put("mp3stop", stopMusicHandler);
        h.put("bgmstop", stopMusicHandler);
        h.put("mp3fadeout", stopMusicHandler); // no fade support needed under the e-ink no-animation rule
        NsCommandHandler noOp = (state, args, listener, vnDir) -> {
        };
        h.put("vol", noOp);
        h.put("mp3vol", noOp);

        // ---- Misc / effects -----------------------------------------------------------------
        NsCommandHandler clearTextHandler = (state, args, listener, vnDir) -> listener.onTextClear();
        h.put("textclear", clearTextHandler);
        h.put("erasetextwindow", clearTextHandler);
        // "lsp <layer>,<spec>,<x>,<y>" -- real rendering of an ":s/…;…" text-sprite (custom
        // font/size/color, a pattern commonly seen on title-screen buttons, e.g.
        // "lsp 1,\":s/36,38,0;#FFFFFF#a9a9a9`Start game\",565,430") isn't implemented; only the
        // label text is tracked, so a following "spbtn"/"btnwait" button group (see those handlers)
        // can be offered as a native choice menu. Any other "lsp" form (a plain image, ":c;"/":a;"
        // tagged, etc.) is left a no-op, same as before.
        h.put("lsp", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            int layer = (int) NsExpr.numeric(args.get(0), state);
            String spec = resolveFileArg(args.get(1), state);
            String label = textSpriteLabel(spec);
            if (label != null) {
                state.spriteTextLabels.put(layer, label);
                return;
            }
            // Anything else is a real image sprite at an explicit numbered layer -- e.g. scripts
            // sometimes load a background this way ("lsp 50,\":c;dat\\bg\\bg04_1.jpg\",
            // -240,0") rather than via "bg". Same transparency-tag handling as "ld", but with a
            // literal x/y instead of an "l"/"c"/"r" auto-position (this command has no such
            // left/center/right convention of its own).
            state.spriteFileHints.put(layer, fileNameHint(spec));
            if (args.size() < 4) {
                return;
            }
            int x = (int) NsExpr.numeric(args.get(2), state);
            int y = (int) NsExpr.numeric(args.get(3), state);
            listener.onSprite(layer, x, y, resolveAsset(state, vnDir, spec), transparencyFor(spec), alphaMaskCellsFor(spec));
        });
        // "spbtn <layer>,<button id>" / "exbtn <sprite>,<button id>,<hitmask spec>" /
        // "cellcheckexbtn" -- registers a sprite as a clickable button id. In real ONScripter-EN,
        // "spbtn"/"exbtn" both
        // feed the exact same underlying button-click list,
        // just tagged with a different button type internally -- "exbtn" additionally
        // takes a 3rd "hitmask" argument describing a real pixel-region click shape, which this host
        // can't hit-test anyway (same tolerance "spbtn"'s own coordinates already get), so it's
        // simply ignored. "exbtn" is commonly what a title screen uses
        // to register its "Start"/"Continue"/"Option"/"End" buttons; before this was
        // recognized, the whole title screen had no buttons to offer at all, so its own
        // "btnwait2"-based click loop (see that command's doc) blocked forever with nothing to
        // click, silently looping on every tap instead of ever starting the story.
        NsCommandHandler spbtnHandler = (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            int layer = (int) NsExpr.numeric(args.get(0), state);
            int buttonId = (int) NsExpr.numeric(args.get(1), state);
            // A text-labeled ("lsp"-":s/…;…") layer gets its real label; an image-sprite button
            // (a common pattern for save/load/options
            // menus -- this host can't render/hit-test the real button graphics) falls back to that
            // same "lsp" call's own filename (see fileNameHint's doc), or a bare button id as a
            // last resort -- still offered as a native choice either way, so the menu stays
            // navigable rather than silently stranding the player in front of it.
            String label = state.spriteTextLabels.get(layer);
            if (label == null) {
                label = state.spriteFileHints.get(layer);
            }
            state.pendingButtonLabels.add(label != null ? label : ("Button " + buttonId));
            state.pendingButtonIds.add(buttonId);
        };
        h.put("spbtn", spbtnHandler);
        h.put("exbtn", spbtnHandler);
        h.put("cellcheckexbtn", spbtnHandler);
        // "exbtn_d" -- declares a real-time control string real ONScripter uses to dynamically
        // enable/disable individual "exbtn" buttons and pick a cursor icon per frame. This host has no
        // per-frame hit-testing/cursor concept to feed that into -- it just offers whatever "exbtn"
        // itself registered as a native choice regardless -- so it's a safe no-op.
        h.put("exbtn_d", noOp);
        // "btn no,x,y,w,h,srcX,srcY" -- the original, simplest button-registration idiom: a
        // rectangular click region cropped out
        // of the single "btndef"-loaded image, distinct from "spbtn"/"exbtn" (which tag a NUMBERED
        // sprite layer instead -- "btn" has no such layer, every button crops from the same shared
        // image, so there's no lsp-style label to fall back to; see spbtn's own doc). Feeds the same
        // button-click list, so "btnwait"/"btnwait2"/"selectbtnwait" pick it up with no extra
        // plumbing. x/y/w/h/srcX/srcY are all ignored, same tolerance "spbtn"'s own coordinates get
        // (this host can't hit-test/render the real image crop anyway). A typical
        // main menu ("Start"/"Continue"/"Load") registers its buttons exactly this way -- before
        // this was implemented, that menu had nothing to offer at all, so its own "btnwait2"-based
        // click loop blocked forever with no way to progress.
        h.put("btn", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            int buttonId = (int) NsExpr.numeric(args.get(0), state);
            state.pendingButtonLabels.add("Button " + buttonId);
            state.pendingButtonIds.add(buttonId);
        });
        // "btnwait <var>" -- real semantics: blocks until the player clicks one of the
        // "spbtn"-registered sprite buttons, stores its button id into <var>, then falls through to
        // the next line (never jumps, unlike "select"). This host can't hit-test real sprites, so
        // the registered (label, id) pairs are offered as a native choice menu instead -- see
        // NsScriptEngine.choose()'s pendingBtnwaitVarIndex branch for the fall-through/variable-set
        // half of this. A "btnwait" with no "spbtn" registered at all beforehand silently no-ops
        // rather than hanging waiting for a menu that would show nothing.
        NsCommandHandler btnwaitHandler = (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            if (state.pendingButtonLabels.isEmpty()) {
                // Real ONScripter's "btnwait"/"selectbtnwait" ALWAYS blocks waiting for a click,
                // even with zero registered buttons -- its own event loop only ever returns once a
                // valid click/key event
                // arrives; it never short-circuits just because its button list is empty. A click
                // that doesn't land on any registered button-sprite still resolves to a real value
                // there (commonly -1, e.g. a right-click/cancel) -- scripts commonly handle this via
                // something like "if %BtnRes=-1 gosub *rmenu_custom:..." for exactly this
                // case. Before this was fixed, an empty button list made this a silent no-op that
                // kept running rather than blocking, so the script just free-fell through whatever
                // came next (often more subroutine bodies reached this same way) until
                // NsScriptEngine.MAX_STEPS_PER_RESUME's safety valve eventually forced a return --
                // from the player's perspective, nothing ever appeared to happen. This host has no
                // raw screen-tap-position concept separate from its ordinary dialogue tap-to-advance
                // gesture, so it blocks the same way a dialogue line does (WAITING_TAP, no choice
                // menu) and resolves to -1 once the host taps -- see
                // NsScriptEngine.resumeFromTap()'s own pendingBtnwaitVarIndex handling.
                state.pendingBtnwaitVarIndex = NsExpr.numVarIndex(args.get(0), state);
                state.runState = VnEngine.State.WAITING_TAP;
                return;
            }
            state.pendingBtnwaitVarIndex = NsExpr.numVarIndex(args.get(0), state);
            state.pendingChoiceButtonIds = new ArrayList<>(state.pendingButtonIds);
            List<String> optionTexts = new ArrayList<>(state.pendingButtonLabels);
            state.pendingButtonLabels.clear();
            state.pendingButtonIds.clear();
            state.runState = VnEngine.State.WAITING_CHOICE;
            state.lastChoiceOptionTexts = new ArrayList<>(optionTexts);
            state.lastChoiceLabels = null;
            state.lastChoiceBtnwaitVarIndex = state.pendingBtnwaitVarIndex;
            state.lastChoiceButtonIds = new ArrayList<>(state.pendingChoiceButtonIds);
            listener.onChoices(optionTexts);
        };
        h.put("btnwait", btnwaitHandler);
        // "selectbtnwait"/"btnwait2"/"textbtnwait" -- in real ONScripter-EN, the command table binds
        // ALL FOUR of "btnwait"/"btnwait2"/
        // "textbtnwait"/"selectbtnwait" to the exact same underlying command body --
        // they only differ in cosmetic flags real ONScripter uses internally
        // (e.g. whether the button-def image auto-deletes afterward), never in whether/how they
        // block. "btnwait2" in particular is commonly used heavily
        // by a right-click system menu's own button-wait loop (e.g. "*rmenu_custom_btlp"); before this
        // was added, "btnwait2" was an unrecognized lowercase mnemonic that silently no-op'd instead
        // of blocking, so that menu's click was never actually captured -- its %BtnRes variable kept
        // whatever stale value an OUTER "selectbtnwait" loop had last left it at, immediately
        // triggering that menu's own "unrecognized input -> exit" branch every time, and any real
        // per-button dispatch inside the menu (save/load/options/etc.) never ran. This is why the
        // menu appeared to do nothing but hide itself on any tap, reappearing on the next "Next".
        h.put("selectbtnwait", btnwaitHandler);
        h.put("btnwait2", btnwaitHandler);
        h.put("textbtnwait", btnwaitHandler);
        // "cselbtn index,buttonId,x,y" -- registers a clickable button for the "index"-th option
        // declared by the most recent "csel" (see its handler and state.customSelectTexts's doc),
        // feeding the same pendingButtonLabels/pendingButtonIds lists "spbtn" does so
        // "selectbtnwait" (== "btnwait") picks it up with no extra plumbing. In real ONScripter-EN,
        // this looks up the
        // "index"-th real select-link's OWN text (NOT any "lsp"/sprite-based label -- an earlier
        // version of this handler wrongly reused spriteTextLabels/spriteFileHints here, which is a
        // different, unrelated idiom, see "spbtn"'s own doc), and no-ops if that index doesn't
        // exist or has empty text -- same tolerance real ONScripter has (it just returns without
        // registering a button). x/y ignored, same tolerance "spbtn" already has for real
        // (non-hit-testable) coordinates.
        h.put("cselbtn", (state, args, listener, vnDir) -> {
            if (args.size() < 2) {
                return;
            }
            int index = (int) NsExpr.numeric(args.get(0), state);
            int buttonId = (int) NsExpr.numeric(args.get(1), state);
            if (index < 0 || index >= state.customSelectTexts.size()) {
                return;
            }
            String label = state.customSelectTexts.get(index);
            if (label == null || label.isEmpty()) {
                return;
            }
            state.pendingButtonLabels.add(label);
            state.pendingButtonIds.add(buttonId);
        });
        // "getcselnum var" -- sets var to the number of options the most recent "csel" declared.
        // In real ONScripter-EN, this just counts the
        // current select-link list and stores the count, nothing fancier -- commonly used to bound
        // a loop that lays out one "cselbtn" per option.
        h.put("getcselnum", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            int idx = NsExpr.numVarIndex(args.get(0), state);
            state.numVars.put(idx, (long) state.customSelectTexts.size());
        });
        // "cselgoto index" -- jumps to the label the "index"-th "csel"-declared option points to,
        // then clears the select-link list, mirroring real ONScripter's own jump-then-clear
        // sequence. This is
        // the actual dispatch step for the whole "csel"/"cselbtn"/"selectbtnwait" idiom -- e.g. a
        // menu commonly computes this index by subtracting a fixed offset from the
        // button id "selectbtnwait" returned (that offset is a script-side convention, not anything
        // this dispatcher needs to know about). An out-of-range index is a fatal script error in
        // real ONScripter; tolerated here as a no-op instead, same as an unresolved "goto" target
        // elsewhere in this dispatcher.
        h.put("cselgoto", (state, args, listener, vnDir) -> {
            if (args.isEmpty()) {
                return;
            }
            int index = (int) NsExpr.numeric(args.get(0), state);
            if (index < 0 || index >= state.customSelectLabels.size()) {
                return;
            }
            String label = state.customSelectLabels.get(index);
            state.customSelectTexts = new ArrayList<>();
            state.customSelectLabels = new ArrayList<>();
            Integer dest = state.labelIndex.get(label);
            if (dest != null) {
                state.pc = dest;
                listener.onTextClear();
            }
        });
        h.put("save", noOp);
        h.put("load", noOp);
        // "reset" -- real NScripter simulates a fresh launch without actually restarting the
        // process: local variables/call-stack/pending-choice bookkeeping are cleared and execution
        // jumps back to state.startPc (see its own doc), naturally re-running the header and
        // landing back on the title/main menu the same way a cold boot does -- a common pattern is
        // a "*check_reset" confirm dialog, whose "Yes" branch is a bare "reset"
        // (its "No" branch is just "return", needing none of this). Real ONScripter also clears
        // sprite/effect state as part of this, but this dispatcher has no such state of its own
        // beyond what's tracked here -- the re-run header's own "lsp"/"cl a"/"bg" calls (the same
        // ones a cold boot already relies on to draw the title screen correctly) redraw the screen
        // from scratch, the same way any other long-distance "goto" jump already does.
        h.put("reset", (state, args, listener, vnDir) -> {
            state.numVars.clear();
            state.strVars.clear();
            state.callStack.clear();
            state.pendingChoiceLabels = new ArrayList<>();
            state.pendingBtnwaitVarIndex = null;
            state.pendingChoiceButtonIds = new ArrayList<>();
            state.pendingButtonLabels.clear();
            state.pendingButtonIds.clear();
            state.spriteTextLabels.clear();
            state.spriteFileHints.clear();
            state.customSelectTexts = new ArrayList<>();
            state.customSelectLabels = new ArrayList<>();
            state.pendingDialogueRemainder = null;
            state.pendingPageClearOnResume = false;
            state.pendingSubParams = new ArrayList<>();
            state.lastChoiceOptionTexts = null;
            state.lastChoiceLabels = null;
            state.lastChoiceBtnwaitVarIndex = null;
            state.lastChoiceButtonIds = null;
            state.pc = state.startPc;
        });
        // "end" -- real NScripter terminates the whole engine; here it's invoked from a menu/title
        // screen's own "Quit"-style option (a common pattern in menu
        // subroutines), not by reaching a story's natural ending, so it should return to the
        // host's library directly rather than show a "The End" screen. Halts the run loop the same
        // way reaching the end of the script does (see NsScriptEngine.runLoop's own FINISHED
        // check), just via a distinct listener callback.
        h.put("end", (state, args, listener, vnDir) -> {
            state.runState = VnEngine.State.FINISHED;
            listener.onExitToLibrary();
        });
        // "systemcall <sub>" -- real ONScripter's system-menu entry point (save/load/skip/auto/...).
        // Only the "load" subcommand (e.g.
        // "systemcall load") is wired up, to the host's own save-slot Load UI; any other
        // subcommand has no host equivalent yet and is left a no-op, same tolerance as an
        // unrecognized command. Halts the run loop like a blocking "text" line so the host regains
        // control while its Load dialog is up, instead of the script racing ahead underneath it.
        h.put("systemcall", (state, args, listener, vnDir) -> {
            if (args.isEmpty() || !args.get(0).value.equalsIgnoreCase("load")) {
                return;
            }
            state.runState = VnEngine.State.WAITING_TAP;
            listener.onLoadMenuRequested();
        });
        h.put("wait", (state, args, listener, vnDir) -> {
            if (!state.delaysEnabled || args.isEmpty()) {
                return;
            }
            long ms = NsExpr.numeric(args.get(0), state);
            if (ms <= 0) {
                return;
            }
            int frames = Math.round(ms * 60f / 1000f);
            state.runState = VnEngine.State.WAITING_DELAY;
            listener.onDelay(frames);
        });

        return h;
    }

    private static void playSound(List<NsArg> args, NsExecState state, VnEngine.Listener listener, File vnDir, int times) {
        if (args.isEmpty()) {
            return;
        }
        String file = resolveFileArg(args.get(0), state);
        if (file.equals("~")) {
            listener.onSound(null, 1);
        } else {
            listener.onSound(resolveAsset(state, vnDir, file), times);
        }
    }
}
