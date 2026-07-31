package io.github.davidgith1.vndsandroideink.nscripter;

/**
 * Decoders for the two real NScripter archive compression schemes {@link NsArchiveReader} itself
 * doesn't understand the container format enough to run ({@code type} 1 = SPB, {@code type} 2 =
 * LZSS -- see real ONScripter-EN's {@code BaseReader.h}: {@code SPB_COMPRESSION = 1},
 * {@code LZSS_COMPRESSION = 2}). An independent implementation of both, informed by real
 * ONScripter-EN's own publicly documented {@code DirectReader::decodeLZSS}/{@code decodeSPB}
 * behavior for interoperability (per this project's usual clean-room policy -- see {@code
 * NsCommandDispatcher}'s own class doc), not guessed at from black-box archive-byte inspection
 * alone, since both are real, specific bit-level algorithms that approach could never fully
 * recover. Real games routinely compress a
 * meaningful slice of their own UI-chrome bitmaps (title screens, menu buttons, window frames) this
 * way -- e.g. a real "Moonshine" install's ENTIRE title screen (logo plus all 4 menu buttons) and a
 * real "a_dream_of_summer" install's window-frame/save-load icon bitmaps are SPB/LZSS-compressed;
 * before these were decoded, none of those ever rendered at all, only ever hitting {@link
 * NsArchiveReader#read}'s "unsupported compression type" failure -- which {@link NsAssetResolver}
 * quietly swallows into "asset just doesn't exist", the same as any genuinely missing file.
 *
 * <p>Neither the archive's own per-entry "compressed size" field, nor the caller, tells this class
 * how the compressed BIT stream itself is framed -- {@link NsArchiveReader.Entry#compressedSize}
 * only bounds how many bytes to read from the archive; the true amount consumed depends on how many
 * bits the algorithm itself needs to reach {@code originalSize} output bytes, which may be fewer
 * than {@code compressedSize} (trailing padding is normal, real ONScripter's own decoders never
 * check for it either).
 */
final class NsArchiveCompression {

    private NsArchiveCompression() {
    }

    // ---- LZSS (type 2) ---------------------------------------------------------------------

    /** Real ONScripter-EN's own LZSS parameters (see its DirectReader.cpp): an 8-bit ring-buffer
     * position field ({@code EI}), a 4-bit match-length field ({@code EJ}), a ring buffer of
     * {@code N = 1 << EI} bytes initialized to all zero, with the encoder's own write cursor
     * starting at {@code N - F} (not 0) -- match references only ever look BACKWARD into
     * already-produced output (including the leading zero-fill), so starting there instead of at 0
     * is what the real encoder/decoder pair agrees on, not an arbitrary choice this port could
     * safely pick differently. */
    private static final int EI = 8;
    private static final int EJ = 4;
    private static final int RING_SIZE = 1 << EI; // 256
    private static final int MAX_MATCH_LEN = (1 << EJ) + 1; // 17 (P=1 in the real source)

    static byte[] decodeLzss(byte[] compressed, int originalLength) {
        byte[] out = new byte[originalLength];
        byte[] ring = new byte[RING_SIZE]; // zero-initialized, matching real decoder's memset
        int r = RING_SIZE - MAX_MATCH_LEN;
        BitReader bits = new BitReader(compressed);
        int count = 0;
        while (count < originalLength) {
            int flag = bits.getBits(1);
            if (flag < 0) {
                break; // ran out of input -- real decoder also just stops early on EOF
            }
            if (flag == 1) {
                int c = bits.getBits(8);
                if (c < 0) {
                    break;
                }
                out[count++] = (byte) c;
                ring[r++] = (byte) c;
                r &= (RING_SIZE - 1);
            } else {
                int i = bits.getBits(EI);
                int j = bits.getBits(EJ);
                if (i < 0 || j < 0) {
                    break;
                }
                for (int k = 0; k <= j + 1 && count < originalLength; k++) {
                    byte c = ring[(i + k) & (RING_SIZE - 1)];
                    out[count++] = c;
                    ring[r++] = c;
                    r &= (RING_SIZE - 1);
                }
            }
        }
        return out;
    }

    /** MSB-first bit reader over a byte array -- matches real ONScripter-EN's own {@code getbit}
     * (see DirectReader.cpp): each new byte's bits are consumed from the top (0x80) down, and a
     * multi-bit read accumulates by shifting the running value left and OR-ing in each bit in turn
     * (i.e. the FIRST bit read becomes the HIGH-order bit of the result). Real {@code getbit} also
     * passes each byte through a {@code key_table} substitution before extracting bits -- omitted
     * here since that table is the identity function for a plain, non-obfuscated ".nsa"/".sar" (the
     * only kind {@link NsArchiveReader} opens; see its own class doc), matching real
     * {@code DirectReader}'s own identity-table default when constructed without one. */
    private static final class BitReader {
        private final byte[] data;
        private int bytePos;
        private int bitMask; // 0 means "need to load the next byte"
        private int curByte;

        BitReader(byte[] data) {
            this(data, 0);
        }

        BitReader(byte[] data, int startOffset) {
            this.data = data;
            this.bytePos = startOffset;
        }

        int getBits(int n) {
            int x = 0;
            for (int i = 0; i < n; i++) {
                if (bitMask == 0) {
                    if (bytePos >= data.length) {
                        return -1;
                    }
                    curByte = data[bytePos++] & 0xFF;
                    bitMask = 0x80;
                }
                x <<= 1;
                if ((curByte & bitMask) != 0) {
                    x |= 1;
                }
                bitMask >>= 1;
            }
            return x;
        }
    }

    // ---- SPB (type 1) -----------------------------------------------------------------------

    /** Real ONScripter-EN's "SPB" format (see DirectReader::decodeSPB): a width/height-prefixed,
     * per-color-channel delta-coded bitmap, decoded directly into a real (uncompressed, 24bpp)
     * {@code .bmp} byte stream -- real ONScripter hands the result straight to its own BMP loader,
     * and a plain uncompressed 24bpp BMP is exactly what Android's {@code BitmapFactory} already
     * decodes natively, so emitting the same real BMP bytes here needs no further format
     * translation on this host's side.
     *
     * <p>Decodes one full 8-bit color channel at a time (R, G, then B -- real order), each as an
     * independent delta stream: the first byte of a channel is a literal starting value; from then
     * on, each group of 4 pixels reads a 3-bit "step size" selector, which is either 0 (repeat the
     * previous value 4 times), 7 (a 1-bit sign-only ±1 step), or otherwise selects an
     * {@code m = n + 2}-bit signed-magnitude delta per pixel (or, when that width would be 8, a
     * fresh literal byte instead of a delta at all) -- real {@code decodeSPB}'s own control flow,
     * not a scheme this port invented. Rows alternate scan direction (even rows left-to-right, odd
     * rows right-to-left, a "boustrophedon" pattern) and the whole image is written bottom row
     * first, matching a BMP's own bottom-up native row order -- real {@code decodeSPB} builds the
     * two facts together on purpose, not by coincidence.
     */
    static byte[] decodeSpb(byte[] compressed) {
        // Real decodeSPB reads width/height as two plain byte-aligned big-endian 16-bit values via
        // its own readShort(fp) -- a separate, direct fread, NOT routed through the bit-accumulator
        // state getbit() uses for everything after -- so the bit-stream proper only starts at byte
        // offset 4, not 0.
        int width = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);
        int height = ((compressed[2] & 0xFF) << 8) | (compressed[3] & 0xFF);
        BitReader bits = new BitReader(compressed, 4);
        int widthPad = (4 - (width * 3) % 4) % 4;
        int rowBytes = width * 3 + widthPad;
        int totalSize = rowBytes * height + 54;

        byte[] out = new byte[totalSize];
        // BITMAPFILEHEADER + BITMAPINFOHEADER, matching real decodeSPB's own byte-for-byte layout.
        out[0] = 'B';
        out[1] = 'M';
        writeLe32(out, 2, totalSize);
        out[10] = 54;
        out[14] = 40; // BITMAPINFOHEADER size
        writeLe32(out, 18, width);
        writeLe32(out, 22, height);
        out[26] = 1; // planes
        out[28] = 24; // bits per pixel
        writeLe32(out, 34, totalSize - 54);

        int[] channel = new int[width * height];
        for (int ch = 0; ch < 3; ch++) {
            int count = 0;
            int c = bits.getBits(8);
            channel[count++] = c;
            while (count < width * height) {
                int n = bits.getBits(3);
                if (n == 0) {
                    // Same "always advance the logical pixel count by a full group of 4, only gate
                    // the ARRAY WRITE" reasoning as the n!=0 branch below -- this branch reads no
                    // extra bits either way, but underclaiming here still throws off how many total
                    // groups the outer while loop thinks are left, one iteration at a time, which
                    // over a long image can itself accumulate into a bit-consumption mismatch by the
                    // end of the channel (letting a LATER extra/missing group run when the real
                    // decoder's own count already would have stopped, or vice versa).
                    for (int j = 0; j < 4; j++) {
                        if (count < width * height) {
                            channel[count] = c;
                        }
                        count++;
                    }
                    continue;
                }
                int m = (n == 7) ? bits.getBits(1) + 1 : n + 2;
                // Real decodeSPB's own inner loop always runs all 4 iterations UNCONDITIONALLY --
                // it never checks its output position against width*height at all (that's exactly
                // why real ONScripter's own decode buffer is allocated "width*height+4", not
                // "width*height": the very last group commonly finishes 1-3 pixels PAST the nominal
                // end, and the real decoder just keeps that slack rather than ever short-circuiting
                // the group). Gating this loop on "count < width*height" (an earlier version of this
                // port did, to avoid overrunning THIS array, which unlike real decodeSPB's is sized
                // exactly width*height) stopped it from reading the LAST group's trailing sub-pixel's
                // bits from the stream at all whenever width*height is a multiple of 4 (count always
                // starts each group at 4k+1, so the group straddling width*height needs all 4 reads
                // even though only some of them still have room to store) -- silently consuming
                // fewer bits than the real encoder produced there, desyncing the shared BitReader's
                // position for every channel decoded after this one. A real, common case this hits:
                // any image whose width*height is itself a multiple of 4 (e.g. a real 800x600 title
                // screen, 480000 total pixels) -- the R channel could still look plausible (delta
                // decoding from a wrong seed often still looks like a smooth gradient), while G and B
                // read from further and further off -- producing exactly the shattered, ghosted,
                // chromatic-aberration-like corruption seen on a real "narcissu_web_edition" SPB
                // title screen before this was found. Bounding only the WRITE (not the bit read)
                // below keeps this array-safe while still consuming bits exactly like the real
                // decoder does.
                for (int j = 0; j < 4; j++) {
                    if (m == 8) {
                        c = bits.getBits(8);
                    } else {
                        int k = bits.getBits(m);
                        if ((k & 1) != 0) {
                            c += (k >> 1) + 1;
                        } else {
                            c -= (k >> 1);
                        }
                        c &= 0xFF;
                    }
                    if (count < width * height) {
                        channel[count] = c;
                    }
                    count++;
                }
            }

            // Bottom row first (BMP native order), boustrophedon column order, 3-byte BGR stride
            // with channel "ch" landing at byte offset "ch" within each pixel -- see this method's
            // own doc for why this matches decodeSPB's own bufIndex arithmetic exactly.
            int destBase = 54 + rowBytes * (height - 1) + ch;
            int srcIndex = 0;
            for (int row = 0; row < height; row++) {
                boolean reverse = (row & 1) != 0;
                int rowStart = destBase - rowBytes * row;
                // A reversed row's FIRST decoded pixel lands at its RIGHTMOST screen column, then
                // moves left as more pixels decode -- not the other way around (see this method's
                // own doc: real decodeSPB's own pbuf arithmetic hands a reversed row's "if" branch
                // pbuf already sitting at that row's rightmost pixel, decrementing from there).
                int rowRightmost = rowStart + (width - 1) * 3;
                for (int col = 0; col < width; col++) {
                    int dest = reverse ? rowRightmost - col * 3 : rowStart + col * 3;
                    out[dest] = (byte) channel[srcIndex++];
                }
            }
        }
        return out;
    }

    private static void writeLe32(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
