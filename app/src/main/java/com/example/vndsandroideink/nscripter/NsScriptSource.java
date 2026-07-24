package com.example.vndsandroideink.nscripter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers and loads an NScripter script, either plain-text ("0.txt"/"00.txt" and numbered
 * continuation files) or one of the classic single-file obfuscated containers ("nscript.dat",
 * "pscript.dat"; see {@link NsObfuscation}). A plain-text script is spread across one primary file
 * plus optional numbered continuations, all concatenated into a single line buffer -- unlike
 * VNDS's per-file scripts, NScripter has no cross-file jump, so every {@code goto}/{@code gosub}
 * target is a label somewhere in this one combined buffer. An obfuscated container already holds
 * the whole script in one file, so it never has continuations.
 */
public final class NsScriptSource {

    /** Plain-text primary candidates, in lookup priority order. A ".utf"/".utf.txt" name is an
     * explicit UTF-8 signal; plain ".txt" defaults to Shift-JIS unless a UTF-8 BOM says otherwise
     * (see {@link #detectEncoding}). Tried before the obfuscated candidates, matching real
     * NScripter's own preference for an unencrypted script when both are somehow present. */
    private static final String[] PLAIN_TEXT_CANDIDATES = {
            "0.utf.txt", "0.utf", "00.utf.txt", "00.utf", "0.txt", "00.txt"
    };

    /** Obfuscated single-file candidates: filename to the {@link NsObfuscation.Format} it decodes
     * with -- the text charset applied to the decoded bytes is decided separately, in {@link
     * #loadObfuscated}. */
    private static final Object[][] OBFUSCATED_CANDIDATES = {
            {"nscript.dat", NsObfuscation.Format.NSCRIPT_DAT},
            {"pscript.dat", NsObfuscation.Format.PSCRIPT_DAT},
            {"nscr_sec.dat", NsObfuscation.Format.NSCR_SEC_DAT},
    };

    private static final Pattern CONTINUATION_FILE =
            Pattern.compile("(\\d{1,2})\\.(utf\\.txt|utf|txt)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MODE_DIRECTIVE =
            Pattern.compile("mode\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private NsScriptSource() {
    }

    /** True if {@code vnDir} contains a plain-text NScripter script (see
     * {@link #PLAIN_TEXT_CANDIDATES}) -- excludes obfuscated containers; see {@link #hasAnyScript}. */
    public static boolean hasPlainTextScript(File vnDir) {
        return findPlainTextPrimary(vnDir) != null;
    }

    /** True if {@code vnDir} contains a recognized NScripter script of any kind, plain-text or
     * obfuscated (even a format {@link #load} can't actually decode yet, like nscr_sec.dat --
     * useful for import-time pack recognition, which only needs to know "this is an NScripter
     * pack," not successfully parse it). */
    public static boolean hasAnyScript(File vnDir) {
        return findPlainTextPrimary(vnDir) != null || findObfuscatedPrimary(vnDir) != null;
    }

    /** Loads and concatenates the whole script. Returns an empty {@link NsScript} (no lines, no
     * labels) if no recognized, decodable script exists in {@code vnDir}. */
    public static NsScript load(File vnDir) {
        File plainPrimary = findPlainTextPrimary(vnDir);
        if (plainPrimary != null) {
            return loadPlainText(vnDir, plainPrimary);
        }
        Object[] obfuscated = findObfuscatedPrimary(vnDir);
        if (obfuscated != null) {
            return loadObfuscated((File) obfuscated[0], (NsObfuscation.Format) obfuscated[1]);
        }
        return new NsScript(new ArrayList<>(), new HashMap<>(), StandardCharsets.UTF_8, 0);
    }

    private static NsScript loadPlainText(File vnDir, File primary) {
        Charset encoding = detectEncoding(primary);
        List<File> files = new ArrayList<>();
        files.add(primary);
        files.addAll(findContinuations(vnDir, primary));

        List<String> lines = new ArrayList<>();
        for (File f : files) {
            lines.addAll(readLines(readAllBytes(f), encoding));
        }
        return new NsScript(lines, buildLabelIndex(lines), encoding, findStartPc(lines));
    }

    private static NsScript loadObfuscated(File file, NsObfuscation.Format format) {
        byte[] decoded = NsObfuscation.decode(readAllBytes(file), format);
        // nscript.dat is Shift-JIS (confirmed by a real game's Japanese comment text
        // decoding correctly); pscript.dat is documented as its UTF-8-encoded counterpart.
        Charset encoding = format == NsObfuscation.Format.PSCRIPT_DAT
                ? StandardCharsets.UTF_8 : Charset.forName("Shift_JIS");
        List<String> lines = readLines(decoded, encoding);
        return new NsScript(lines, buildLabelIndex(lines), encoding, findStartPc(lines));
    }

    /**
     * Finds where a fresh playthrough should begin: at the "*define" label, if the script has one
     * (see {@link NsScript#startPc}'s doc) -- NOT right
     * after "game". Real NScripter starts executing at line 0 unconditionally and runs sequentially
     * (following goto/gosub/if like any other point in the script) until "game" flips its internal
     * mode flag from DEFINE_MODE to NORMAL_MODE; "*define" is simply the conventional label marking
     * where a script's real (non-dead-code) content begins, so starting there and continuing
     * straight through "game" like normal execution reproduces that faithfully -- including a
     * "gosub" reachable only from inside that header section (seen in a real script, whose
     * "*define" section itself does {@code gosub *sys_define}, registering several "numalias"
     * declarations used throughout the story, before falling through to "game"). Falls back to
     * right-after-"game" for a script that has "game" but no "*define" label, and to line 0 if
     * neither exists (every hand-written test script in this project's own test suite is like this,
     * and is meant to run from the top).
     */
    private static int findStartPc(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            NsLine line = NsTokenizer.classify(lines.get(i));
            if (line.type == NsLine.Type.LABEL && line.text.equals("define")) {
                return i;
            }
        }
        int gameIndex = findGameIndex(lines);
        return gameIndex < lines.size() ? gameIndex + 1 : 0;
    }

    /** The index of the top-level "game" command, or {@code lines.size()} if the script has none --
     * shared by {@link #findStartPc} (as its own fallback) and {@link #peekTitleInfo} (whose
     * "caption"/"versionstr" scan is bounded by this, not by {@link NsScript#startPc}, since
     * {@code startPc} may now point at "*define" itself, before either of those commands). */
    private static int findGameIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            NsLine line = NsTokenizer.classify(lines.get(i));
            if (line.type == NsLine.Type.STATEMENT && line.firstToken().equals("game")) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * Peeks the resolution a script declares via a leading ";mode<width>" comment directive
     * (e.g. ";mode400" -> 400x300), scanning only the leading run of comment/blank lines before
     * the first real content -- matching how real NScripter scripts place such headers before any
     * label/command content. Falls back to {@link NsResolution#DEFAULT} if absent, if {@code
     * vnDir} has no recognized script, or if it's an obfuscated format {@link #load} can't decode.
     * Cheap enough to call standalone, without a full {@link #load}, since {@code ReaderActivity}
     * needs it before constructing the engine.
     */
    public static NsResolution peekResolution(File vnDir) {
        List<String> lines;
        try {
            NsScript script = load(vnDir);
            lines = script.lines;
        } catch (RuntimeException e) {
            return NsResolution.DEFAULT; // e.g. an nscr_sec.dat NsObfuscation can't decode yet
        }
        for (String raw : lines) {
            NsLine line = NsTokenizer.classify(raw);
            if (line.type == NsLine.Type.BLANK) {
                continue;
            }
            if (line.type != NsLine.Type.COMMENT) {
                break; // real content starts: headers are only ever leading comment lines
            }
            Matcher m = MODE_DIRECTIVE.matcher(line.text);
            if (m.find()) {
                int width = Integer.parseInt(m.group(1));
                if (width > 0) {
                    return new NsResolution(width, Math.round(width * 0.75f));
                }
            }
        }
        return NsResolution.DEFAULT;
    }

    /** A script's self-reported title/subtitle, from its "caption"/"versionstr" header commands
     * (see {@link #peekTitleInfo}) -- either half may be null if the script never declares it. */
    public static final class NsTitleInfo {
        public static final NsTitleInfo EMPTY = new NsTitleInfo(null, null);

        public final String title;
        public final String subtitle;

        public NsTitleInfo(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * Peeks a script's own declared title/subtitle: "caption" (seen in real scripts,
     * e.g. {@code caption "The Answer"}) sets the title, and "versionstr"'s 2nd argument
     * (e.g. {@code versionstr "The Answer"," version 1.0-en"}) supplies the subtitle -- its 1st
     * argument is typically just the title repeated, so it's only used as a title fallback when
     * "caption" itself is absent. Both commands always appear in the "*define" header section,
     * before the top-level "game" command that marks where actual play begins -- scanning is
     * bounded to that point (via {@link #findGameIndex}, not {@link NsScript#startPc}, which may
     * now point earlier, at "*define" itself -- see its own doc) so a coincidental same-named
     * bareword later in the real story is never mistaken for one. Cheap enough to call standalone
     * at import time, the same way {@link #peekResolution} is.
     */
    public static NsTitleInfo peekTitleInfo(File vnDir) {
        NsScript script;
        try {
            script = load(vnDir);
        } catch (RuntimeException e) {
            return NsTitleInfo.EMPTY; // e.g. an nscr_sec.dat NsObfuscation can't decode yet
        }
        String title = null;
        String subtitle = null;
        int limit = findGameIndex(script.lines);
        for (int i = 0; i < limit; i++) {
            NsLine line = NsTokenizer.classify(script.lines.get(i));
            if (line.type != NsLine.Type.STATEMENT) {
                continue;
            }
            String token = line.firstToken();
            if (token.equals("caption") && title == null) {
                List<NsArg> args = NsTokenizer.parseArgs(line.argsText());
                if (!args.isEmpty()) {
                    title = args.get(0).value.trim();
                }
            } else if (token.equals("versionstr")) {
                List<NsArg> args = NsTokenizer.parseArgs(line.argsText());
                if (args.size() > 1) {
                    subtitle = args.get(1).value.trim();
                }
                if (title == null && !args.isEmpty()) {
                    title = args.get(0).value.trim();
                }
            }
        }
        return new NsTitleInfo(
                title == null || title.isEmpty() ? null : title,
                subtitle == null || subtitle.isEmpty() ? null : subtitle);
    }

    private static File findPlainTextPrimary(File vnDir) {
        for (String name : PLAIN_TEXT_CANDIDATES) {
            File f = new File(vnDir, name);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** @return {@code {File, NsObfuscation.Format}}, or null if none of the obfuscated candidate
     * filenames exist in {@code vnDir}. */
    private static Object[] findObfuscatedPrimary(File vnDir) {
        for (Object[] candidate : OBFUSCATED_CANDIDATES) {
            File f = new File(vnDir, (String) candidate[0]);
            if (f.isFile()) {
                return new Object[]{f, candidate[1]};
            }
        }
        return null;
    }

    /** Numbered continuation files sharing the primary's suffix style (".txt" vs ".utf"/".utf.txt"),
     * sorted numerically -- e.g. primary "0.txt" pulls in "1.txt", "2.txt", ... "99.txt" if present. */
    private static List<File> findContinuations(File vnDir, File primary) {
        String primarySuffix = primarySuffix(primary.getName());
        File[] siblings = vnDir.listFiles();
        List<File> result = new ArrayList<>();
        if (siblings == null) {
            return result;
        }
        List<int[]> numbered = new ArrayList<>(); // {number, index into result-to-be}
        List<File> candidates = new ArrayList<>();
        for (File f : siblings) {
            if (!f.isFile() || f.getName().equalsIgnoreCase(primary.getName())) {
                continue;
            }
            Matcher m = CONTINUATION_FILE.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            if (!m.group(2).equalsIgnoreCase(primarySuffix)) {
                continue; // keep the continuation series consistent with the primary's own encoding
            }
            int number = Integer.parseInt(m.group(1));
            if (number == 0) {
                continue; // "0"/"00" is the primary itself, never a continuation
            }
            numbered.add(new int[]{number, candidates.size()});
            candidates.add(f);
        }
        numbered.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] entry : numbered) {
            result.add(candidates.get(entry[1]));
        }
        return result;
    }

    private static String primarySuffix(String primaryName) {
        Matcher m = CONTINUATION_FILE.matcher(primaryName);
        return m.matches() ? m.group(2) : "txt";
    }

    /** UTF-8 if the filename says so or a UTF-8 BOM is present; Shift-JIS otherwise -- NScripter's
     * classic plain-text default, long predating UTF-8 becoming common in Japanese authoring tools. */
    private static Charset detectEncoding(File file) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).contains(".utf")) {
            return StandardCharsets.UTF_8;
        }
        byte[] head = readHead(file, 3);
        if (head.length == 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return Charset.forName("Shift_JIS");
    }

    private static byte[] readHead(File file, int maxBytes) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[maxBytes];
            int read = in.read(buf);
            return read <= 0 ? new byte[0] : java.util.Arrays.copyOf(buf, read);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] readAllBytes(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0]; // missing/unreadable file: treat as empty, same fallback vnds.ScriptEngine uses
        }
    }

    private static List<String> readLines(byte[] bytes, Charset encoding) {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), encoding))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    line = stripBom(line);
                    first = false;
                }
                result.add(line);
            }
        } catch (IOException e) {
            // In-memory stream: not expected to fail, but stay consistent with the rest of this
            // class's tolerant-of-anything philosophy rather than propagating.
        }
        return result;
    }

    private static String stripBom(String firstLine) {
        return !firstLine.isEmpty() && firstLine.charAt(0) == '\uFEFF' ? firstLine.substring(1) : firstLine;
    }

    private static Map<String, Integer> buildLabelIndex(List<String> lines) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            NsLine line = NsTokenizer.classify(lines.get(i));
            if (line.type == NsLine.Type.LABEL) {
                index.put(line.text, i);
            }
        }
        return index;
    }
}
