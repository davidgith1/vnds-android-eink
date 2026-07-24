package io.github.davidgith1.vndsandroideink.nscripter;

/**
 * Decodes NScripter's classic single-file obfuscated script containers. This is trivial
 * byte-scrambling, not real encryption -- confirmed by XORing a real "nscript.dat" from an
 * NScripter game byte-for-byte with 0x84 and getting
 * back readable script text (";value101", ";gameid ...", real command lines).
 */
public final class NsObfuscation {

    public enum Format {
        /** "nscript.dat" -- verified: every byte XORed with 0x84. */
        NSCRIPT_DAT,
        /** "pscript.dat" -- documented as sharing nscript.dat's encrypt_mode (1), differing only
         * in the text encoding (UTF-8) applied to the decoded bytes afterward. Not independently
         * verified against a real sample the way NSCRIPT_DAT was -- treat with a bit less
         * confidence, though the transform itself is the same one that IS verified. */
        PSCRIPT_DAT,
        /** "nscr_sec.dat" -- a different obfuscation variant (encrypt_mode 2) whose transform
         * hasn't been verified against any real sample. Deliberately unimplemented rather than
         * guessed: {@link #decode} throws for this format instead of silently producing garbage. */
        NSCR_SEC_DAT
    }

    /** Verified against a real sample -- see the class doc. */
    private static final int MODE1_XOR = 0x84;

    private NsObfuscation() {
    }

    public static byte[] decode(byte[] raw, Format format) {
        switch (format) {
            case NSCRIPT_DAT:
            case PSCRIPT_DAT:
                return xor(raw, MODE1_XOR);
            case NSCR_SEC_DAT:
            default:
                throw new UnsupportedOperationException(
                        "nscr_sec.dat's obfuscation (encrypt_mode 2) is not yet supported -- its "
                                + "transform hasn't been verified against a real sample, unlike "
                                + "nscript.dat/pscript.dat's confirmed 0x84 XOR.");
        }
    }

    private static byte[] xor(byte[] data, int mask) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ mask);
        }
        return out;
    }
}
