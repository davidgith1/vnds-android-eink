package io.github.davidgith1.vndsandroideink;

/** VNDB (https://vndb.org) metadata for a VN, manually linked via its VNDB id (e.g. "v7"). */
public class VndbMeta {

    public final String id;
    public final String title;
    /** Alternate/original-script title; null if VNDB doesn't have one on file. */
    public final String altTitle;
    /** Release date as "YYYY-MM-DD", "YYYY-MM", or "YYYY"; null if unknown. */
    public final String released;
    /** Bayesian rating, 10-100 scale; null if not enough votes yet. */
    public final Double rating;
    /** Rough length category, 1 (very short) to 5 (very long); null if unknown. */
    public final Integer length;
    /** Average play time in minutes; null if unknown. */
    public final Integer lengthMinutes;

    public VndbMeta(String id, String title, String altTitle, String released,
                     Double rating, Integer length, Integer lengthMinutes) {
        this.id = id;
        this.title = title;
        this.altTitle = altTitle;
        this.released = released;
        this.rating = rating;
        this.length = length;
        this.lengthMinutes = lengthMinutes;
    }

    public String url() {
        return "https://vndb.org/" + id;
    }

    /** VNDB's 1-5 length category as the text VNDB itself uses for it. */
    public static String lengthLabel(int length) {
        switch (length) {
            case 1: return "Very short";
            case 2: return "Short";
            case 3: return "Medium";
            case 4: return "Long";
            case 5: return "Very long";
            default: return "";
        }
    }
}
