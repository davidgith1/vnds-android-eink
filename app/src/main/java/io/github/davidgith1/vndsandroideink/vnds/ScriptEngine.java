package io.github.davidgith1.vndsandroideink.vnds;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets the VNDS ".scr" script format.
 *
 * <p>Supported commands (everything documented in the VNDS format, source: Digital-Haze, plus
 * cleartext/endscript from the reference BASLQC/vnds engine source): text (including "$name"/
 * "{$name}" variable substitution), bgload, setimg, sound, music, setvar, gsetvar, choice, if/fi,
 * label, goto, jump, delay, random, cleartext, endscript.
 *
 * <p>The engine is a simple line-pointer interpreter over the current script file.
 * {@link #run()} executes commands until it hits something that must wait for the
 * player or a timer: a line of dialogue ({@code text !} / {@code text <line>}), a
 * {@code choice}, or a {@code delay}. The host UI resumes it with {@link #resumeFromTap()},
 * {@link #choose(int)}, or {@link #resumeFromDelay()}.
 */
public class ScriptEngine implements VnEngine {

    /** Matches a variable reference for {@link #substituteVariables}, as either "$name" or
     * "{$name}" -- the braces are an optional delimiter around the reference itself (useful to
     * set the name's end apart from following text, e.g. {@code text {$hour}:0{$minute}}), not
     * literal text: when present, both the opening and closing brace are consumed along with the
     * reference and replaced by the plain value, never left behind in the output. Whichever
     * alternative matches, the name stops at the first character that isn't a
     * letter/digit/underscore. */
    private static final Pattern VAR_REFERENCE =
            Pattern.compile("\\{\\$([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /** "bgload"'s documented default fade length, in frames, when no fadetime argument is given. */
    private static final int DEFAULT_BGLOAD_FADE_FRAMES = 16;

    /** Sentinel passed as {@link Listener#onSprite}'s layer argument: VNDS's setimg has no layer
     * identity of its own (it always appends) -- see {@link VnEngine.Listener#onSprite}. */
    private static final int NO_LAYER = -1;

    private final File vnDir;
    private final Listener listener;
    private final Map<String, List<String>> fileCache = new HashMap<>();
    private final Map<String, Integer> labelIndex = new HashMap<>();
    private final Map<String, String> variables = new HashMap<>();
    /** Variables set via gsetvar: persisted by the host independently of any save slot. */
    private final Map<String, String> globals;
    private final Random random = new Random();

    private List<String> lines = new ArrayList<>();
    private String currentFile;
    private int pc = 0;
    private State state = State.FINISHED;
    /** Whether "delay" actually pauses; see {@link #setDelaysEnabled}. Off by default. */
    private boolean delaysEnabled = false;

    public ScriptEngine(File vnDir, Listener listener, Map<String, String> initialGlobals) {
        this.vnDir = vnDir;
        this.listener = listener;
        this.globals = new HashMap<>(initialGlobals);
    }

    /**
     * Controls whether {@code delay} actually pauses execution. The host decides this based on
     * its own e-ink/animation preferences (e.g. skip delays while e-ink mode's "instant updates"
     * setting is on, honor them otherwise) -- the engine itself has no opinion on timing.
     */
    public void setDelaysEnabled(boolean enabled) {
        this.delaysEnabled = enabled;
    }

    public State getState() {
        return state;
    }

    public String getCurrentFile() {
        return currentFile;
    }

    public int getPc() {
        return pc;
    }

    public Map<String, String> getVariablesSnapshot() {
        return new HashMap<>(variables);
    }

    /** Variables set via gsetvar, snapshotted the same way {@link #getVariablesSnapshot} covers
     * setvar ones -- for the host's own "Variables" viewer/editor, not consulted by the engine
     * itself outside of {@link #evalCondition}. */
    public Map<String, String> getGlobalsSnapshot() {
        return new HashMap<>(globals);
    }

    /** Directly sets a local (setvar) variable's value, bypassing the "=/+/-" modifier a script's
     * own setvar line would use -- for host-driven edits (a debug/inspector UI), not script
     * execution. */
    public void setVariable(String name, String value) {
        variables.put(name, value);
    }

    /** Directly sets a global (gsetvar) variable's value, the same way {@link #setVariable} does
     * for local ones, and notifies the host to persist it -- same as a script's own gsetvar would. */
    public void setGlobal(String name, String value) {
        globals.put(name, value);
        listener.onGlobalsChanged(new HashMap<>(globals));
    }

    /**
     * Peeks (without consuming) whether the next command to run after the current tap is
     * "text ~", i.e. whether resuming from the current WAITING_TAP line will immediately wipe
     * the text box for a new page. Lets the host add extra Auto-advance pause before a page the
     * player is currently reading disappears.
     */
    public boolean isPageEndPending() {
        int i = pc;
        while (i < lines.size()) {
            String t = lines.get(i).trim();
            if (!t.isEmpty()) {
                return t.equals("text ~");
            }
            i++;
        }
        return false;
    }

    /** Starts execution at script/main.scr. */
    public void start() {
        loadFile("main.scr");
        pc = 0;
        state = State.RUNNING;
        run();
    }

    /**
     * Resumes execution at a previously saved position, without re-running any commands.
     * The caller is responsible for restoring the on-screen visuals (background, sprites,
     * text box contents) to match, since jumping straight to {@code savedPc} skips the
     * commands that originally produced them.
     */
    public void restoreState(String fileName, int savedPc, Map<String, String> vars) {
        loadFile(fileName);
        this.pc = Math.max(0, Math.min(savedPc, lines.size()));
        variables.clear();
        variables.putAll(vars);
        state = State.WAITING_TAP;
    }

    public void resumeFromTap() {
        if (state == State.WAITING_TAP) {
            state = State.RUNNING;
            run();
        }
    }

    /** Resumes after the host's scheduled delay has elapsed; see {@link Listener#onDelay}. */
    public void resumeFromDelay() {
        if (state == State.WAITING_DELAY) {
            state = State.RUNNING;
            run();
        }
    }

    /**
     * Lets the host force a WAITING_DELAY-style pause outside of a scripted {@code delay}
     * command -- used from {@link Listener#onTextClear} to hold a "text ~" clear (and the engine)
     * until a voice-synced sound effect finishes playing. Resume with {@link #resumeFromDelay()}.
     * Must only be called synchronously from within a {@code Listener} callback, while {@code run()}
     * is still on the call stack.
     */
    public void pauseForHostTiming() {
        state = State.WAITING_DELAY;
    }

    public void choose(int zeroBasedIndex) {
        if (state != State.WAITING_CHOICE) {
            return;
        }
        variables.put("selected", String.valueOf(zeroBasedIndex + 1));
        state = State.RUNNING;
        run();
    }

    @Override
    public boolean reshowLastChoiceMenu() {
        return false; // VNDS has no "systemcall"-style host action that would ever need this
    }

    private void run() {
        while (state == State.RUNNING) {
            if (pc >= lines.size()) {
                state = State.FINISHED;
                listener.onFinished();
                return;
            }
            String raw = lines.get(pc);
            pc++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            String cmd = parts[0];
            String rest = parts.length > 1 ? parts[1] : "";

            switch (cmd) {
                case "text":
                    handleText(rest);
                    break;
                case "bgload":
                    handleBgload(rest);
                    break;
                case "setimg":
                    handleSetimg(rest);
                    break;
                case "sound":
                    handleSound(rest);
                    break;
                case "music":
                    handleMusic(rest);
                    break;
                case "setvar":
                    applyVarOp(variables, rest);
                    break;
                case "gsetvar":
                    applyVarOp(globals, rest);
                    listener.onGlobalsChanged(new HashMap<>(globals));
                    break;
                case "random":
                    handleRandom(rest);
                    break;
                case "delay":
                    handleDelay(rest);
                    break;
                case "choice":
                    handleChoice(rest);
                    break;
                case "if":
                    if (!evalCondition(rest)) {
                        skipToFi();
                    }
                    break;
                case "fi":
                case "label":
                    // no-ops: labels are resolved up-front into labelIndex
                    break;
                case "goto": {
                    // A goto's own target can itself be a variable reference (a dynamic jump),
                    // same as text/choice arguments.
                    String targetLabel = substituteVariables(rest);
                    pc = labelIndex.containsKey(targetLabel) ? labelIndex.get(targetLabel) : pc;
                    break;
                }
                case "jump":
                    handleJump(rest);
                    break;
                case "cleartext":
                    // The reference engine's "!" (full wipe including history) and default "~"
                    // (soft page-fill up to the visible line count) variants both collapse to the
                    // same thing here: this reader has no engine-managed history buffer (the
                    // host's Text Log is a separate, independent feature) and no fixed visible
                    // line count (text sizing is pixel-budget-based, not line-count-based) -- so
                    // either variant just clears the current page, same as "text ~".
                    listener.onTextClear();
                    break;
                case "endscript":
                    // Reference VNDS engines loop back to script/main.scr on endscript; that fits
                    // the original's title-screen-driven flow, but this reader has no in-engine
                    // title screen to loop to -- treat it the same as reaching physical
                    // end-of-file instead, so the player sees the normal "The End" screen.
                    state = State.FINISHED;
                    listener.onFinished();
                    return;
                default:
                    // Unknown/unsupported command: ignore and continue.
                    break;
            }
        }
    }

    private void handleText(String rest) {
        if (rest.equals("~")) {
            listener.onTextClear();
            return; // non-blocking
        }
        if (rest.equals("!")) {
            state = State.WAITING_TAP; // blocking pause, no text change
            return;
        }
        rest = substituteVariables(rest);
        if (rest.startsWith("@")) {
            // Per the VNDS format, an "@"-prefixed line does NOT require a tap: it shows
            // immediately and falls straight through to whatever command comes next. Only the
            // bracketed "@[ Name ]" form is a speaker tag (normally followed by the actual
            // blocking dialogue line); any other "@..." is just non-blocking dialogue/caption
            // text in its own right (title cards, voice-synced subtitles, etc.) and must not be
            // mistaken for a speaker name.
            String content = rest.substring(1);
            String trimmed = content.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                listener.onSpeaker(trimmed.substring(1, trimmed.length() - 1).trim());
            } else {
                listener.onTextLine(content);
            }
            return;
        }
        listener.onTextLine(rest);
        state = State.WAITING_TAP; // each line of text is a step the player taps through
    }

    /** "bgload file [fadetime]": fadetime is in frames at 60fps, defaulting to 16 when omitted. */
    private void handleBgload(String rest) {
        String[] tokens = rest.split("\\s+");
        String file = tokens[0];
        int fadeFrames = tokens.length > 1 ? parseIntSafe(tokens[1], DEFAULT_BGLOAD_FADE_FRAMES) : DEFAULT_BGLOAD_FADE_FRAMES;
        if (file.equals("~")) {
            listener.onBackground(null, fadeFrames, VnEngine.SpriteTransparency.OPAQUE, 1);
            return;
        }
        listener.onBackground(resolveAsset("background", file), fadeFrames, VnEngine.SpriteTransparency.OPAQUE, 1);
    }

    /**
     * "setimg file x y" draws the image at pixel (x,y) in the VN's declared resolution, layered
     * on top of whatever else is already showing -- unlike bgload/sound/music, the VNDS format
     * doesn't document a "~" removal form for setimg (confirmed absent from every real script
     * seen in sample VNDS packs), so foreground layers are only ever cleared by bgload.
     */
    private void handleSetimg(String rest) {
        String[] tokens = rest.split("\\s+");
        String file = tokens[0];
        if (file.equals("~")) {
            return;
        }
        int x = tokens.length > 1 ? parseIntSafe(tokens[1], 0) : 0;
        int y = tokens.length > 2 ? parseIntSafe(tokens[2], 0) : 0;
        listener.onSprite(NO_LAYER, x, y, resolveAsset("foreground", file), VnEngine.SpriteTransparency.OPAQUE, 1);
    }

    /** "sound file times": times may be a repeat count, or -1 for infinite looping. */
    private void handleSound(String rest) {
        String[] tokens = rest.split("\\s+");
        String file = tokens[0];
        if (file.equals("~")) {
            listener.onSound(null, 1); // stop whatever sound is currently playing
            return;
        }
        int times = tokens.length > 1 ? parseIntSafe(tokens[1], 1) : 1;
        listener.onSound(resolveAsset("sound", file), times);
    }

    private void handleMusic(String rest) {
        String[] tokens = rest.split("\\s+");
        String file = tokens[0];
        if (file.equals("~")) {
            listener.onMusic(null);
        } else {
            listener.onMusic(resolveAsset("sound", file));
        }
    }

    /**
     * Resolves an asset path exactly as the script wrote it, falling back to a case-insensitive
     * match if that exact path doesn't exist. VNDS packs are commonly authored and tested on
     * case-insensitive filesystems (Windows/FAT), so it's routine for a script's path casing to
     * disagree with the actual asset -- which silently fails to load on Android's case-sensitive
     * storage without this fallback. The filename can itself contain subfolders (e.g.
     * "bgm/bgm04.mp3" or "km/km2.png"), so each path segment is resolved -- and case-corrected --
     * one at a time rather than only checking the final filename against the top subfolder.
     */
    private File resolveAsset(String subfolder, String filename) {
        File exact = new File(vnDir, subfolder + "/" + filename);
        if (exact.exists()) {
            return exact;
        }
        File current = new File(vnDir, subfolder);
        for (String segment : filename.split("/")) {
            File next = new File(current, segment);
            if (!next.exists()) {
                File[] siblings = current.listFiles();
                if (siblings != null) {
                    for (File sibling : siblings) {
                        if (sibling.getName().equalsIgnoreCase(segment)) {
                            next = sibling;
                            break;
                        }
                    }
                }
            }
            current = next;
        }
        return current;
    }

    /**
     * "setvar"/"gsetvar" both use "name modifier value" (modifier is "=", "+", or "-"), e.g.
     * "setvar finish = 0" or "gsetvar chapter = 15" -- real scripts always include the modifier
     * token, so treating the second token as the value (as a plain "name value" pair) silently
     * stores the literal modifier string instead of the real value.
     */
    private void applyVarOp(Map<String, String> target, String rest) {
        // Limit 3: the value is everything after "name op ", not just its first whitespace token
        // -- a multi-word string value like "setvar name = John Smith" must be stored whole, not
        // truncated to "John".
        String[] tokens = rest.split("\\s+", 3);
        if (tokens.length >= 2 && tokens[0].equals("~") && tokens[1].startsWith("~")) {
            target.clear();
            return;
        }
        if (tokens.length < 3) {
            return;
        }
        String name = tokens[0];
        String op = tokens[1];
        String value = tokens[2];
        switch (op) {
            case "=":
                target.put(name, value);
                break;
            case "+":
            case "-":
                int current = parseIntSafe(target.get(name), 0);
                int delta = parseIntSafe(value, 0);
                target.put(name, String.valueOf(op.equals("+") ? current + delta : current - delta));
                break;
            default:
                // Unrecognized modifier: ignore, consistent with unknown commands elsewhere.
                break;
        }
    }

    private void handleRandom(String rest) {
        String[] tokens = rest.split("\\s+");
        if (tokens.length < 3) {
            return;
        }
        int low = parseIntSafe(tokens[1], 0);
        int high = parseIntSafe(tokens[2], 0);
        if (high < low) {
            int swap = low;
            low = high;
            high = swap;
        }
        variables.put(tokens[0], String.valueOf(low + random.nextInt(high - low + 1)));
    }

    private void handleDelay(String rest) {
        if (!delaysEnabled) {
            return; // instant: no-op, continue immediately
        }
        int frames = parseIntSafe(rest.trim(), 0);
        if (frames <= 0) {
            return;
        }
        state = State.WAITING_DELAY;
        listener.onDelay(frames);
    }

    private void handleChoice(String rest) {
        rest = substituteVariables(rest);
        String[] options = rest.split("\\|");
        List<String> list = new ArrayList<>();
        for (String o : options) {
            list.add(o.trim());
        }
        state = State.WAITING_CHOICE;
        listener.onChoices(list);
    }

    /** Replaces every "$name" reference in {@code text} with that variable's current value --
     * the VNDS format's "prefix the variable name with $ and it will directly replace it"
     * (documented as applying to a command's own string arguments, e.g. {@code text}/{@code
     * choice}, not to numeric ones like a delay's frame count). A name that's never been set at
     * all resolves to "0", same as {@link #evalCondition}'s default. */
    private String substituteVariables(String text) {
        if (text.indexOf('$') < 0) {
            return text; // fast path: nothing to substitute
        }
        Matcher m = VAR_REFERENCE.matcher(text);
        // StringBuffer, not StringBuilder: the StringBuilder overload of appendReplacement/
        // appendTail only exists since API 34, and this app's minSdk is 24.
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2); // group 1 = "{$name}", group 2 = "$name"
            m.appendReplacement(sb, Matcher.quoteReplacement(lookupVariable(name)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Local (setvar) variables take precedence, then global (gsetvar) ones -- e.g. Red Shift's
     * chapter-unlock checks ("if chapter == 5") read a gsetvar'd variable this way. A variable
     * that was never set at all defaults to numeric 0, not the empty string -- otherwise
     * "if someFlag == 0" (a common way scripts check an unset flag) would fail, since
     * Integer.parseInt("") throws and the string-compare fallback in {@link #evalCondition} would
     * treat "" as < "0". */
    private String lookupVariable(String name) {
        String value = variables.get(name);
        if (value == null) {
            value = globals.get(name);
        }
        return value != null ? value : "0";
    }

    private boolean evalCondition(String rest) {
        // Limit 3: the compared value is everything after "name op ", not just its first
        // whitespace-delimited token -- a value like "if name == John Smith" must compare against
        // the whole "John Smith", not silently truncate to "John".
        String[] tokens = rest.split("\\s+", 3);
        if (tokens.length < 3) {
            return false;
        }
        String varName = tokens[0];
        String op = tokens[1];
        String value = tokens[2];
        String actual = lookupVariable(varName);
        int cmp;
        try {
            cmp = Integer.compare(Integer.parseInt(actual), Integer.parseInt(value));
        } catch (NumberFormatException e) {
            cmp = actual.compareTo(value);
        }
        switch (op) {
            case "==":
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

    /** Called with pc pointing just after an "if" whose condition was false. Skips to after "fi". */
    private void skipToFi() {
        int depth = 1;
        while (pc < lines.size()) {
            String t = lines.get(pc).trim();
            pc++;
            if (t.startsWith("if ") || t.equals("if")) {
                depth++;
            } else if (t.equals("fi")) {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }

    private void handleJump(String rest) {
        String[] tokens = rest.split("\\s+");
        String file = tokens[0];
        String label = tokens.length > 1 ? tokens[1] : null;
        loadFile(file);
        pc = (label != null && labelIndex.containsKey(label)) ? labelIndex.get(label) : 0;
    }

    private void loadFile(String fileName) {
        List<String> cached = fileCache.get(fileName);
        if (cached == null) {
            cached = readLines(new File(vnDir, "script/" + fileName));
            fileCache.put(fileName, cached);
        }
        this.lines = cached;
        this.currentFile = fileName;
        rebuildLabelIndex();
    }

    private void rebuildLabelIndex() {
        labelIndex.clear();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("label ")) {
                labelIndex.put(t.substring("label ".length()).trim(), i);
            }
        }
    }

    private static List<String> readLines(File file) {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException e) {
            // Missing/unreadable script file: treat as empty so the engine just finishes.
        }
        return result;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
