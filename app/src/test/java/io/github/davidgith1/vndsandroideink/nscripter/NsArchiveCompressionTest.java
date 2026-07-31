package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Regression coverage for {@link NsArchiveCompression#decodeSpb}'s bit-stream consumption, using a
 * small hand-built synthetic bitstream rather than a real game asset -- exercises a specific real
 * bug independent of any one sample pack being present locally.
 */
public class NsArchiveCompressionTest {

    @Test
    public void decodesAllThreeChannelsCorrectlyWhenTheFinalDeltaGroupOverrunsTheChannel() throws Exception {
        // Real decodeSPB's own inner per-group loop always reads all 4 sub-values from the bit
        // stream unconditionally, even past the channel's own logical width*height end -- see
        // NsArchiveCompression.decodeSpb's own doc for why (its decode buffer is allocated
        // "width*height+4" for exactly this overrun). A 2x2 image (width*height=4, itself a
        // multiple of 4 -- the same class of size a real 800x600 SPB title screen also has) hits
        // this on literally its first and only delta group: the group starts at the post-literal
        // count of 1, so its 4th sub-value lands at index 4 -- one past the last valid index (3).
        // An earlier version of this decoder stopped READING (not just writing) once the array
        // index would go out of bounds, silently consuming 3 bits fewer than the real encoder
        // produced for that channel -- desyncing every channel decoded after it. Encodes 3 flat-ish
        // channels (R starting at 10, G at 50, B at 90, each stepping +2 per pixel via a repeated
        // n=1/k=3 delta) and checks that G and B -- which only decode correctly if R's (and G's)
        // full group, overrun pixel included, was correctly consumed from the stream -- come out as
        // their own real values instead of misread bits borrowed from the previous channel's tail.
        BitWriter w = new BitWriter();
        int[] literals = {10, 50, 90};
        for (int literal : literals) {
            w.writeBits(literal, 8);
            w.writeBits(1, 3); // n = 1 -> m = n + 2 = 3
            for (int j = 0; j < 4; j++) {
                w.writeBits(3, 3); // k = 3 (binary 011): odd -> c += (k>>1)+1 == c+2
            }
        }
        byte[] bitstream = w.toByteArray();

        byte[] compressed = new byte[4 + bitstream.length];
        compressed[0] = 0;
        compressed[1] = 2; // width = 2
        compressed[2] = 0;
        compressed[3] = 2; // height = 2
        System.arraycopy(bitstream, 0, compressed, 4, bitstream.length);

        byte[] bmp = NsArchiveCompression.decodeSpb(compressed);

        // Expected per-channel decode-order values: [literal, literal+2, literal+4, literal+6]
        // (the would-be 5th value, literal+8, is the overrun pixel: read from the stream, but never
        // stored). See this test's own doc for the destination-offset derivation (54-byte header,
        // rowBytes=8 for a 2-wide/24bpp/2-byte-padded row, boustrophedon row 1 reversed).
        assertEquals(10, bmp[62] & 0xFF); // (row0,col0) R
        assertEquals(50, bmp[63] & 0xFF); // (row0,col0) G
        assertEquals(90, bmp[64] & 0xFF); // (row0,col0) B
        assertEquals(12, bmp[65] & 0xFF); // (row0,col1) R
        assertEquals(52, bmp[66] & 0xFF); // (row0,col1) G
        assertEquals(92, bmp[67] & 0xFF); // (row0,col1) B
        assertEquals(14, bmp[57] & 0xFF); // (row1,col0) R
        assertEquals(54, bmp[58] & 0xFF); // (row1,col0) G
        assertEquals(94, bmp[59] & 0xFF); // (row1,col0) B
        assertEquals(16, bmp[54] & 0xFF); // (row1,col1) R
        assertEquals(56, bmp[55] & 0xFF); // (row1,col1) G
        assertEquals(96, bmp[56] & 0xFF); // (row1,col1) B
    }

    /** MSB-first bit packer matching {@code NsArchiveCompression}'s own {@code BitReader}
     * convention: the first bit written becomes the highest-order bit consumed. */
    private static final class BitWriter {
        private final java.util.List<Byte> bytes = new java.util.ArrayList<>();
        private int curByte = 0;
        private int bitCount = 0;

        void writeBits(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                int bit = (value >> i) & 1;
                curByte = (curByte << 1) | bit;
                bitCount++;
                if (bitCount == 8) {
                    bytes.add((byte) curByte);
                    curByte = 0;
                    bitCount = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitCount > 0) {
                curByte <<= (8 - bitCount);
                bytes.add((byte) curByte);
                bitCount = 0;
            }
            byte[] out = new byte[bytes.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = bytes.get(i);
            }
            return out;
        }
    }
}
