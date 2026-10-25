package app.vaultdoc.ocr.service;

import java.awt.image.BufferedImage;

/**
 * EXIF {@code Orientation} (tag 0x0112) of a JPEG, and the transform that makes
 * the pixels upright. Phone cameras store the sensor's landscape raster and
 * put the rotation in metadata; leptonica (tesseract's image loader) ignores
 * it, so a portrait photo would be OCR'd sideways and come back as noise.
 *
 * <p>The parser only walks JPEG segment headers and one IFD — no external
 * dependency, no decoding. Anything malformed means "no rotation" (1).
 */
final class ExifOrientation {

    private ExifOrientation() {}

    /** Orientation 1..8 from the first EXIF APP1 segment; 1 when absent or unreadable. */
    static int read(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) return 1;
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++;                                    // fill byte
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                pos += 2;                                 // standalone markers carry no length
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) return 1;   // image data / end: no EXIF before it
            int length = u16(b, pos + 2, false);
            int end = pos + 2 + length;
            if (length < 2 || end > b.length) return 1;
            if (marker == 0xE1 && length >= 16 && isExifHeader(b, pos + 4)) {
                return fromTiff(b, pos + 10, end);
            }
            pos = end;
        }
        return 1;
    }

    /** Upright copy of {@code src} for {@code orientation}; {@code src} itself for 1 / unknown values. */
    static BufferedImage apply(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < dst.getHeight(); y++) {
            for (int x = 0; x < dst.getWidth(); x++) {
                int sx;
                int sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }              // mirror horizontal
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }      // rotate 180
                    case 4 -> { sx = x; sy = h - 1 - y; }              // mirror vertical
                    case 5 -> { sx = y; sy = x; }                      // transpose
                    case 6 -> { sx = y; sy = h - 1 - x; }              // rotate 90 CW
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }      // transverse
                    default -> { sx = w - 1 - y; sy = x; }             // 8: rotate 90 CCW
                }
                dst.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return dst;
    }

    private static boolean isExifHeader(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0 && b[at + 5] == 0;
    }

    private static int fromTiff(byte[] b, int start, int end) {
        if (start + 8 > end) return 1;
        boolean little;
        if (b[start] == 'I' && b[start + 1] == 'I') little = true;
        else if (b[start] == 'M' && b[start + 1] == 'M') little = false;
        else return 1;
        if (u16(b, start + 2, little) != 42) return 1;
        long ifdOffset = u32(b, start + 4, little);
        if (ifdOffset < 8 || start + ifdOffset + 2 > end) return 1;
        int ifd = (int) (start + ifdOffset);
        int entries = u16(b, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + 12 * i;
            if (entry + 12 > end) return 1;
            if (u16(b, entry, little) == 0x0112) {
                int type = u16(b, entry + 2, little);
                if (type != 3) return 1;                  // must be SHORT
                int value = u16(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int a = b[at] & 0xFF;
        int c = b[at + 1] & 0xFF;
        return little ? a | c << 8 : a << 8 | c;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long hi = u16(b, at + (little ? 2 : 0), little);
        long lo = u16(b, at + (little ? 0 : 2), little);
        return hi << 16 | lo;
    }
}
