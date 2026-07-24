package com.example.vndsandroideink.nscripter;

/** The screen resolution an NScripter script declares via its leading ";mode" header directive
 * (see {@link NsScriptSource#peekResolution}), or the classic NScripter default if it declares
 * none. */
public final class NsResolution {

    public static final NsResolution DEFAULT = new NsResolution(640, 480);

    public final int width;
    public final int height;

    public NsResolution(int width, int height) {
        this.width = width;
        this.height = height;
    }
}
