package com.example.vndsandroideink;

import java.io.File;

/** A visual novel discovered under the user-chosen library folder, already imported locally. */
public class VnEntry {

    /** Which interpreter this pack needs -- decided once, at import time (see {@code
     * VnImporter}'s detection predicates), and persisted alongside the local copy (a small
     * ".engine" marker file next to ".imported") so re-scanning an already-imported folder never
     * needs to re-run detection against files that may have since been extracted/rearranged. */
    public enum EngineType { VNDS, NSCRIPTER }

    public final String title;
    /** A pack's own self-reported secondary text -- currently only NScripter's "versionstr" (see
     * {@code NsScriptSource#peekTitleInfo}); VNDS packs never set this. Null if the pack declares
     * none. Always shown on its own row in the library list, independent of {@code vndbMeta} --
     * fetching VNDB info must never overwrite or hide it. */
    public final String subtitle;
    public final File localDir;
    /** The pack's own "icon.png" (the VNDS titlescreen icon), if it shipped one; null falls back
     * to a generic book icon rather than the much larger "thumbnail.png" screenshot. */
    public final File icon;
    /** Whether there's an always-current resume snapshot to offer instead of starting over. */
    public final boolean hasResume;
    /** Total wall-clock time spent reading this VN, summed across every session; 0 if never opened. */
    public final long playMillis;
    /** VNDB metadata, if the user has manually linked this VN to a VNDB id; null otherwise. */
    public final VndbMeta vndbMeta;
    public final EngineType engineType;

    public VnEntry(String title, String subtitle, File localDir, File icon, boolean hasResume, long playMillis,
                    VndbMeta vndbMeta, EngineType engineType) {
        this.title = title;
        this.subtitle = subtitle;
        this.localDir = localDir;
        this.icon = icon;
        this.hasResume = hasResume;
        this.playMillis = playMillis;
        this.vndbMeta = vndbMeta;
        this.engineType = engineType;
    }
}
