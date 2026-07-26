package io.github.davidgith1.vndsandroideink;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Route/choice/ending completion guides: a user-supplied JSON file (see README.md's Completion
 * guides section for the shape this reads) describing a VN's routes, the choices
 * that lead down each, and the endings they lead to. Stored as a plain file inside the VN's own
 * local dir ({@code filesDir/vns/<vn>/guide.json}) -- not part of the original story pack, but
 * deleted along with it for free ({@code VnImporter.deleteRecursive} already wipes the whole
 * dir). Which boxes the player has checked off is tracked completely separately, in its own
 * SharedPreferences file keyed by vnKey + a stable per-item key, since "what have I done across
 * all my playthroughs" is independent of any single save slot's state.
 */
public final class GuideManager {

    private static final String GUIDE_FILE_NAME = "guide.json";
    private static final String PROGRESS_PREFS_FILE = "vnds_guide_progress";
    /** Sentinel expanded-section key for the top-level "Save Slots" section (routes use their own
     * id instead, which can never collide with this since real ids come straight from the JSON). */
    public static final String SAVE_SLOTS_SECTION_KEY = "__saveSlots__";

    private GuideManager() {
    }

    // ---- Model --------------------------------------------------------------------------------

    public static final class Choice {
        public final String key;
        public final String label;
        /** Extra per-choice context worth a smaller secondary line: whether it's a
         * "characterSelectChoice", a "saveHereFor"/"branchesTo" note, and/or a plain "note";
         * null if none of those were present. */
        public final String detail;
        /** The guide file's own "passed" value -- used only to seed first-import progress (see
         * {@link #importGuide}); the actually-tracked checked state is always read fresh via
         * {@link #isChecked}, never from this. */
        final boolean initialChecked;

        Choice(String key, String label, String detail, boolean initialChecked) {
            this.key = key;
            this.label = label;
            this.detail = detail;
            this.initialChecked = initialChecked;
        }
    }

    public static final class Checkpoint {
        /** An "afterEvent" caption, or a synthesized "Checkpoint N" if the route has several
         * checkpoints and this one didn't name itself; null if there's nothing worth a heading. */
        public final String label;
        public final List<Choice> choices;
        /** Free-text note for anything not itemized as its own checkbox (an "unenumeratedChoices"
         * count and/or a plain "note" field); null if neither was present. */
        public final String info;
        /** The guide's own "createsSave" value -- a save-slot id (see {@link SaveSlotRef}) the
         * player should create a manual save under at this checkpoint, before making its choices;
         * null if the guide named none (Never7's guide uses this at its route-branch points). */
        public final String createsSaveId;

        Checkpoint(String label, List<Choice> choices, String info, String createsSaveId) {
            this.label = label;
            this.choices = choices;
            this.info = info;
            this.createsSaveId = createsSaveId;
        }
    }

    public static final class Ending {
        public final String key;
        public final String label;
        /** How to actually reach this ending: "loadSave", "pivotalChoice"/"pivotalChoiceSequence",
         * "obtainedBy", and/or "note", each on its own line -- null if the guide named none of
         * them (just an id/name, e.g. ECLIPSE's stub entry). */
        public final String detail;
        /** The guide file's own "completed" value; see {@link Choice#initialChecked}. */
        final boolean initialChecked;

        Ending(String key, String label, String detail, boolean initialChecked) {
            this.key = key;
            this.label = label;
            this.detail = detail;
            this.initialChecked = initialChecked;
        }
    }

    public static final class SaveSlotRef {
        public final String key;
        public final String label;
        final boolean initialChecked;

        SaveSlotRef(String key, String label, boolean initialChecked) {
            this.key = key;
            this.label = label;
            this.initialChecked = initialChecked;
        }
    }

    public static final class Route {
        public final String id;
        public final String name;
        public final String category; // nullable
        /** Free-text unlock/structure context, one line each: "prerequisiteRoutes",
         * "prerequisiteEndings" (either shape -- a flat array, or Tsukihime's
         * {requireOneOf, requireAll} object), "unlocksOnCompletion", "prerequisiteNote",
         * "accessedVia", and "onlyOneEndingNote". Empty (not null) if the route named none. */
        public final List<String> infoLines;
        /** Sort key only ("recommendedOrder"); routes are pre-sorted by this in {@link #parseGuide}
         * so nothing downstream needs to re-sort. */
        final int recommendedOrder;
        public final List<Checkpoint> checkpoints;
        public final List<Ending> endings;

        Route(String id, String name, String category, List<String> infoLines, int recommendedOrder,
              List<Checkpoint> checkpoints, List<Ending> endings) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.infoLines = infoLines;
            this.recommendedOrder = recommendedOrder;
            this.checkpoints = checkpoints;
            this.endings = endings;
        }
    }

    public static final class Guide {
        public final String gameName; // nullable
        /** "recommendedOrderNote", if the guide explained its route ordering; null otherwise. */
        public final String orderNote;
        /** "source"/"rating"/"generatedNote"/"trueEndNote" joined onto one line, for "about this
         * guide" context; null if none were present. */
        public final String metaNote;
        public final List<Route> routes;
        public final List<SaveSlotRef> saveSlots;

        Guide(String gameName, String orderNote, String metaNote, List<Route> routes, List<SaveSlotRef> saveSlots) {
            this.gameName = gameName;
            this.orderNote = orderNote;
            this.metaNote = metaNote;
            this.routes = routes;
            this.saveSlots = saveSlots;
        }
    }

    // ---- File I/O -------------------------------------------------------------------------

    /** Keys for standalone guides (see {@link StandaloneGuideManager}) always carry this prefix,
     * so {@link #guideDir} can tell them apart from a real VN's key without a separate parameter
     * threaded through every method. The colon is deliberate, not decorative: a VN's key is always
     * {@code VnImporter.sanitize(folderName)}, which maps every character outside
     * {@code [a-zA-Z0-9._-]} to '_' -- so no real VN key can *ever* contain a colon, let alone
     * start with one, regardless of what the original folder was named. (A plain "sg_" prefix
     * would NOT have this guarantee: a VN folder literally named e.g. "sg_something" sanitizes to
     * a key starting with "sg_" too, and would collide with this check.) */
    private static final String STANDALONE_KEY_PREFIX = "sg:";

    public static String newStandaloneKey() {
        return STANDALONE_KEY_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static File guideDir(Context context, String key) {
        return key.startsWith(STANDALONE_KEY_PREFIX)
                ? new File(new File(context.getFilesDir(), "standalone_guides"), key)
                : new File(context.getFilesDir(), "vns/" + key);
    }

    private static File guideFile(Context context, String key) {
        return new File(guideDir(context, key), GUIDE_FILE_NAME);
    }

    public static boolean hasGuide(Context context, String key) {
        return guideFile(context, key).exists();
    }

    /** Deletes the imported guide file and all its checked-off progress + remembered UI state
     * (which sections were expanded, scroll position) for a VN. Irreversible: the caller is
     * expected to have already confirmed with the user. For a standalone entry's guide, this
     * leaves the (now guide-less) entry itself in place -- see
     * {@link StandaloneGuideManager#deleteEntry} to remove the whole entry instead. */
    public static void deleteGuide(Context context, String key) {
        guideFile(context, key).delete();
        clearProgress(context, key);
    }

    /** Fully removes a standalone guide entry's on-disk directory (the guide.json and anything
     * else under it). A VN-attached guide needs no equivalent: its guide.json lives inside the
     * VN's own directory, deleted whole by {@code VnImporter.deleteRecursive}. */
    static void deleteStandaloneGuideDir(Context context, String standaloneKey) {
        deleteRecursive(guideDir(context, standaloneKey));
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    /** Copies the picked file in place as this VN's guide (overwriting any previous one) after
     * confirming it at least parses as JSON with a "routes" array -- anything else is rejected
     * before ever touching local storage. Existing checked-off progress is left untouched (keyed
     * by route/choice/ending id, so it carries over as long as the new file reuses the same ids);
     * any item the guide itself already marks "passed"/"completed" is seeded as checked too, but
     * only if it has no tracked progress yet -- re-importing an updated guide must never revert
     * something the player already checked off back to whatever the file's own default was. */
    public static void importGuide(Context context, String vnKey, Uri uri) throws IOException, JSONException {
        String text;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open the picked file");
            }
            text = readAll(in);
        }
        JSONObject root = new JSONObject(text); // throws JSONException if not valid JSON
        if (!root.has("routes")) {
            throw new JSONException("Not a recognizable guide file (missing \"routes\")");
        }
        Guide guide = parseGuide(root);
        File dest = guideFile(context, vnKey);
        File dir = dest.getParentFile();
        if (dir != null) {
            dir.mkdirs(); // no-op for a VN's already-existing directory; required for a fresh standalone one
        }
        try (OutputStream out = new FileOutputStream(dest)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        seedInitialProgress(context, vnKey, guide);
    }

    private static void seedInitialProgress(Context context, String vnKey, Guide guide) {
        for (Route route : guide.routes) {
            for (Checkpoint checkpoint : route.checkpoints) {
                for (Choice choice : checkpoint.choices) {
                    if (choice.initialChecked && !hasProgress(context, vnKey, choice.key)) {
                        setChecked(context, vnKey, choice.key, true);
                    }
                }
            }
            for (Ending ending : route.endings) {
                if (ending.initialChecked && !hasProgress(context, vnKey, ending.key)) {
                    setChecked(context, vnKey, ending.key, true);
                }
            }
        }
        for (SaveSlotRef slot : guide.saveSlots) {
            if (slot.initialChecked && !hasProgress(context, vnKey, slot.key)) {
                setChecked(context, vnKey, slot.key, true);
            }
        }
    }

    /** Parses the currently-imported guide, or null if none is imported / it's unreadable. */
    public static Guide loadGuide(Context context, String vnKey) {
        File file = guideFile(context, vnKey);
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return parseGuide(new JSONObject(readAll(in)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    // ---- Parsing ----------------------------------------------------------------------------

    private static Guide parseGuide(JSONObject root) throws JSONException {
        String gameName = root.optString("game", null);
        String orderNote = root.optString("recommendedOrderNote", null);
        String metaNote = joinNonEmpty(" ", root.optString("source", null), root.optString("rating", null),
                root.optString("generatedNote", null), root.optString("trueEndNote", null));

        List<Route> routes = new ArrayList<>();
        JSONArray routesJson = root.optJSONArray("routes");
        if (routesJson != null) {
            for (int i = 0; i < routesJson.length(); i++) {
                routes.add(parseRoute(routesJson.getJSONObject(i), i));
            }
        }
        // Stable sort: routes without a "recommendedOrder" (default Integer.MAX_VALUE) keep their
        // original relative order instead of all piling up identically at the end in JSON order.
        routes.sort((a, b) -> Integer.compare(a.recommendedOrder, b.recommendedOrder));

        List<SaveSlotRef> saveSlots = new ArrayList<>();
        JSONArray saveSlotsJson = root.optJSONArray("saveSlots");
        if (saveSlotsJson != null) {
            for (int i = 0; i < saveSlotsJson.length(); i++) {
                saveSlots.add(parseSaveSlot(saveSlotsJson.getJSONObject(i), i));
            }
        }

        return new Guide(gameName, orderNote, metaNote, routes, saveSlots);
    }

    private static SaveSlotRef parseSaveSlot(JSONObject slotJson, int index) throws JSONException {
        String id = slotJson.optString("id", String.valueOf(index));
        String createdIn = slotJson.optString("createdIn", null);
        List<String> uses = jsonArrayToStrings(slotJson.optJSONArray("usedToStart"));
        StringBuilder label = new StringBuilder("Slot ").append(id);
        if (createdIn != null && !createdIn.isEmpty()) {
            label.append(" -- created in ").append(createdIn);
        }
        if (!uses.isEmpty()) {
            label.append(", starts: ").append(String.join(", ", uses));
        }
        return new SaveSlotRef("saveslot::" + id, label.toString(), slotJson.optBoolean("created", false));
    }

    private static Route parseRoute(JSONObject routeJson, int routeIndex) throws JSONException {
        String id = routeJson.optString("id", "route" + routeIndex);
        String name = routeJson.optString("name", id);
        String category = routeJson.optString("category", null);
        int recommendedOrder = routeJson.optInt("recommendedOrder", Integer.MAX_VALUE);

        List<String> infoLines = new ArrayList<>();
        List<String> prereqRoutes = jsonArrayToStrings(routeJson.optJSONArray("prerequisiteRoutes"));
        if (!prereqRoutes.isEmpty()) {
            infoLines.add("Requires route(s): " + String.join(", ", prereqRoutes));
        }
        Object prereqEndingsRaw = routeJson.opt("prerequisiteEndings");
        if (prereqEndingsRaw instanceof JSONObject) {
            // Tsukihime's shape: {"requireOneOf": [...], "requireAll": [...]}.
            JSONObject obj = (JSONObject) prereqEndingsRaw;
            List<String> oneOf = jsonArrayToStrings(obj.optJSONArray("requireOneOf"));
            List<String> all = jsonArrayToStrings(obj.optJSONArray("requireAll"));
            if (!oneOf.isEmpty()) {
                infoLines.add("Requires one ending from: " + String.join(", ", oneOf));
            }
            if (!all.isEmpty()) {
                infoLines.add("Requires all endings: " + String.join(", ", all));
            }
        } else if (prereqEndingsRaw instanceof JSONArray) {
            // Never7's shape: a flat array, implicitly "all of these".
            List<String> all = jsonArrayToStrings((JSONArray) prereqEndingsRaw);
            if (!all.isEmpty()) {
                infoLines.add("Requires all endings: " + String.join(", ", all));
            }
        }
        List<String> unlocksOn = jsonArrayToStrings(routeJson.optJSONArray("unlocksOnCompletion"));
        if (!unlocksOn.isEmpty()) {
            infoLines.add("Completing this unlocks: " + String.join(", ", unlocksOn));
        }
        addIfPresent(infoLines, routeJson.optString("prerequisiteNote", null));
        String accessedVia = routeJson.optString("accessedVia", null);
        if (accessedVia != null && !accessedVia.isEmpty()) {
            infoLines.add("Access via: " + accessedVia);
        }
        if (routeJson.optBoolean("onlyOneEnding", false)) {
            String oneEndingNote = routeJson.optString("onlyOneEndingNote", null);
            infoLines.add(oneEndingNote != null && !oneEndingNote.isEmpty()
                    ? oneEndingNote : "This route has only one ending.");
        }

        List<Checkpoint> checkpoints = new ArrayList<>();
        JSONArray checkpointsJson = routeJson.optJSONArray("checkpoints");
        boolean multipleCheckpoints = checkpointsJson != null && checkpointsJson.length() > 1;
        if (checkpointsJson != null) {
            for (int c = 0; c < checkpointsJson.length(); c++) {
                checkpoints.add(parseCheckpoint(id, checkpointsJson.getJSONObject(c), c, multipleCheckpoints));
            }
        }

        List<Ending> endings = new ArrayList<>();
        JSONArray endingsJson = routeJson.optJSONArray("endings");
        if (endingsJson != null) {
            for (int e = 0; e < endingsJson.length(); e++) {
                endings.add(parseEnding(id, endingsJson.getJSONObject(e), e));
            }
        }

        return new Route(id, name, category, infoLines, recommendedOrder, checkpoints, endings);
    }

    private static Ending parseEnding(String routeId, JSONObject endingJson, int endingIndex) throws JSONException {
        String endingId = endingJson.optString("id", routeId + "_ending" + endingIndex);
        String endingName = endingJson.optString("name", endingId);
        String type = endingJson.optString("type", null);
        String label = type != null && !type.isEmpty() ? endingName + " (" + type + ")" : endingName;

        List<String> detailLines = new ArrayList<>();
        String loadSave = endingJson.optString("loadSave", null);
        if (loadSave != null && !loadSave.isEmpty()) {
            detailLines.add("Load save " + loadSave);
        }
        JSONArray sequence = endingJson.optJSONArray("pivotalChoiceSequence");
        if (sequence != null && sequence.length() > 0) {
            List<String> steps = new ArrayList<>();
            for (int i = 0; i < sequence.length(); i++) {
                steps.add(formatPivotalChoice(sequence.getJSONObject(i)));
            }
            detailLines.add(String.join("  →  ", steps));
        } else {
            JSONObject single = endingJson.optJSONObject("pivotalChoice");
            if (single != null) {
                detailLines.add(formatPivotalChoice(single));
            }
        }
        addIfPresent(detailLines, endingJson.optString("obtainedBy", null));
        addIfPresent(detailLines, endingJson.optString("note", null));

        String detail = detailLines.isEmpty() ? null : String.join("\n", detailLines);
        return new Ending(routeId + "::" + endingId, label, detail, endingJson.optBoolean("completed", false));
    }

    /** Renders one "pick this / don't pick this" step from a "pivotalChoice" or
     * "pivotalChoiceSequence" entry. */
    private static String formatPivotalChoice(JSONObject choiceJson) {
        String text = choiceJson.optString("text", "?");
        boolean pick = choiceJson.optBoolean("pick", true);
        String result = "“" + text + "”" + (choiceJson.has("pick") && !pick ? " (do NOT pick)" : "");
        String note = choiceJson.optString("note", null);
        return note != null && !note.isEmpty() ? result + " -- " + note : result;
    }

    private static Checkpoint parseCheckpoint(String routeId, JSONObject checkpointJson, int checkpointIndex,
                                               boolean multipleCheckpoints) throws JSONException {
        String afterEvent = checkpointJson.optString("afterEvent", null);
        String label = afterEvent != null && !afterEvent.isEmpty() ? afterEvent
                : multipleCheckpoints ? "Checkpoint " + (checkpointIndex + 1) : null;

        List<Choice> choices = new ArrayList<>();
        JSONArray choicesJson = checkpointJson.optJSONArray("choices");
        if (choicesJson != null) {
            for (int i = 0; i < choicesJson.length(); i++) {
                JSONObject choiceJson = choicesJson.getJSONObject(i);
                String text = choiceJson.optString("text", "?");
                int num = choiceJson.optInt("num", -1);
                String choiceLabel = num >= 0 ? num + ". " + text : text;
                String key = routeId + "::c" + checkpointIndex + "::" + i;
                choices.add(new Choice(key, choiceLabel, formatChoiceDetail(choiceJson), choiceJson.optBoolean("passed", false)));
            }
        }

        int unenumerated = checkpointJson.optInt("unenumeratedChoices", 0);
        String note = checkpointJson.optString("note", null);
        String info;
        if (unenumerated > 0) {
            String text = "+" + unenumerated + " more choice" + (unenumerated == 1 ? "" : "s") + " not itemized";
            info = joinNonEmpty(" -- ", text, note);
        } else {
            info = (note != null && !note.isEmpty()) ? note : null;
        }

        String createsSaveId = checkpointJson.optString("createsSave", null);
        if (createsSaveId != null && createsSaveId.isEmpty()) {
            createsSaveId = null;
        }

        return new Checkpoint(label, choices, info, createsSaveId);
    }

    /** Extra per-choice context worth a smaller secondary line under its checkbox row:
     * "characterSelectChoice", "saveHereFor", "branchesTo", and/or a plain "note". */
    private static String formatChoiceDetail(JSONObject choiceJson) {
        List<String> parts = new ArrayList<>();
        if (choiceJson.optBoolean("characterSelectChoice", false)) {
            parts.add("Character-select choice");
        }
        String saveHereFor = choiceJson.optString("saveHereFor", null);
        if (saveHereFor != null && !saveHereFor.isEmpty()) {
            parts.add("Save here for: " + saveHereFor);
        }
        String branchesTo = choiceJson.optString("branchesTo", null);
        if (branchesTo != null && !branchesTo.isEmpty()) {
            parts.add("Branches to: " + branchesTo);
        }
        addIfPresent(parts, choiceJson.optString("note", null));
        return parts.isEmpty() ? null : String.join("  --  ", parts);
    }

    private static List<String> jsonArrayToStrings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optString(i));
            }
        }
        return result;
    }

    private static void addIfPresent(List<String> lines, String value) {
        if (value != null && !value.isEmpty()) {
            lines.add(value);
        }
    }

    private static String joinNonEmpty(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---- Progress (checked-state) -----------------------------------------------------------

    private static SharedPreferences progressPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PROGRESS_PREFS_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isChecked(Context context, String vnKey, String itemKey) {
        return progressPrefs(context).getBoolean(vnKey + "." + itemKey, false);
    }

    /** Whether this item has ever been explicitly set (checked or unchecked) -- distinct from
     * {@link #isChecked}, which can't tell "never touched" apart from "explicitly unchecked"
     * since both read back as false. Only used to gate {@link #seedInitialProgress}. */
    private static boolean hasProgress(Context context, String vnKey, String itemKey) {
        return progressPrefs(context).contains(vnKey + "." + itemKey);
    }

    public static void setChecked(Context context, String vnKey, String itemKey, boolean checked) {
        progressPrefs(context).edit().putBoolean(vnKey + "." + itemKey, checked).apply();
    }

    /** Wipes all checked-off progress for a VN -- called from {@link VnImporter#deleteLocal} so
     * nothing lingers once the VN itself is gone. The guide file itself needs no separate cleanup
     * there: it lives inside the VN's own directory, which that method already deletes whole. */
    public static void clearProgress(Context context, String vnKey) {
        SharedPreferences.Editor editor = progressPrefs(context).edit();
        String prefix = vnKey + ".";
        for (String key : progressPrefs(context).getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    // ---- Remembered UI state (which tree sections were expanded, scroll position) -----------

    private static final String UI_EXPANDED_KEY_SUFFIX = ".uiExpanded";
    private static final String UI_SCROLL_KEY_SUFFIX = ".uiScrollY";

    /** Which route/save-slots sections were left expanded the last time the guide was open, so
     * reopening it restores the same tree shape instead of collapsing everything again. Keyed by
     * the same route ids used for progress, plus a fixed sentinel for the save-slots section. */
    public static Set<String> getExpandedSections(Context context, String vnKey) {
        Set<String> result = new HashSet<>();
        String json = progressPrefs(context).getString(vnKey + UI_EXPANDED_KEY_SUFFIX, null);
        if (json != null) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    result.add(array.getString(i));
                }
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    public static void setExpandedSections(Context context, String vnKey, Set<String> expandedSectionKeys) {
        JSONArray array = new JSONArray();
        for (String key : expandedSectionKeys) {
            array.put(key);
        }
        progressPrefs(context).edit().putString(vnKey + UI_EXPANDED_KEY_SUFFIX, array.toString()).apply();
    }

    /** How far down the guide's scroll view the player had scrolled, restored the same way. */
    public static int getScrollPosition(Context context, String vnKey) {
        return progressPrefs(context).getInt(vnKey + UI_SCROLL_KEY_SUFFIX, 0);
    }

    public static void setScrollPosition(Context context, String vnKey, int scrollY) {
        progressPrefs(context).edit().putInt(vnKey + UI_SCROLL_KEY_SUFFIX, scrollY).apply();
    }
}
