package io.github.davidgith1.vndsandroideink.nscripter;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/** The result of {@link NsScriptSource#load}: every script line concatenated into one buffer
 * (NScripter has no per-file jump/goto -- {@code gosub}/{@code goto} always target a label
 * somewhere in this same combined buffer), plus a pre-built label index. */
public final class NsScript {

    public final List<String> lines;
    /** Label name (without the leading '*') to line index within {@link #lines}. */
    public final Map<String, Integer> labelIndex;
    public final Charset encoding;
    /** Where a fresh playthrough should actually begin -- see {@link NsScriptSource#findStartPc}.
     * In real NScripter scripts, everything before the "game" command is
     * subroutine/definition content (menu handlers, effect declarations) meant to be jumped *into*
     * later via goto/gosub, not fallen through linearly from line 0 -- running it at startup wanders
     * through thousands of lines of dead menu logic instead of ever reaching the real story. */
    public final int startPc;

    NsScript(List<String> lines, Map<String, Integer> labelIndex, Charset encoding, int startPc) {
        this.lines = lines;
        this.labelIndex = labelIndex;
        this.encoding = encoding;
        this.startPc = startPc;
    }
}
