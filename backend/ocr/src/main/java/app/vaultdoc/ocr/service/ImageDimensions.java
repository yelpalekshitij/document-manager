package app.vaultdoc.ocr.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Pixel size of an image read from its <em>header only</em> — the raster is
 * never decoded, so a decompression bomb ("1 KB PNG that inflates to 4 GB")
 * is rejected by the pixel cap before anything allocates for it.
 *
 * <p>PNG/JPEG/TIFF go through ImageIO's readers (header parsing only). The JDK
 * has no WebP reader, so the RIFF/VP8* header is parsed by hand; tesseract
 * itself reads WebP via leptonica + libwebp.
 *
 * @param width  widest frame
 * @param height tallest frame
 * @param frames number of frames/pages (multi-page TIFF), 1 otherwise
 */
public record ImageDimensions(int width, int height, int frames) {

    public long pixels() {
        return (long) width * height;
    }

    public static ImageDimensions probe(byte[] bytes) throws ImageDecodeException {
        if (bytes == null || bytes.length == 0) {
            throw new ImageDecodeException("image is empty");
        }
        if (isRiff(bytes)) {
            return probeWebp(bytes);
        }
        return probeImageIo(bytes);
    }

    private static ImageDimensions probeImageIo(byte[] bytes) throws ImageDecodeException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new ImageDecodeException("unrecognised image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                int frames = reader.getNumImages(true);
                if (frames <= 0) throw new ImageDecodeException("image has no frames");
                int width = 0;
                int height = 0;
                for (int i = 0; i < frames; i++) {
                    width = Math.max(width, reader.getWidth(i));
                    height = Math.max(height, reader.getHeight(i));
                }
                if (width <= 0 || height <= 0) throw new ImageDecodeException("image has no pixels");
                return new ImageDimensions(width, height, frames);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new ImageDecodeException("image header unreadable: " + OcrText.summarize(e), e);
        }
    }

    private static boolean isRiff(byte[] b) {
        return b.length >= 4 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
    }

    /** See https://developers.google.com/speed/webp/docs/riff_container. */
    private static ImageDimensions probeWebp(byte[] b) throws ImageDecodeException {
        if (b.length < 16 || !(b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')) {
            throw new ImageDecodeException("RIFF container is not WebP");
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X" -> {
                require(b, 30);
                return new ImageDimensions(1 + le24(b, 24), 1 + le24(b, 27), 1);
            }
            case "VP8 " -> {
                require(b, 30);
                if ((b[23] & 0xFF) != 0x9d || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2a) {
                    throw new ImageDecodeException("WebP VP8 start code missing");
                }
                int width = ((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF;
                int height = ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF;
                if (width == 0 || height == 0) throw new ImageDecodeException("WebP has no pixels");
                return new ImageDimensions(width, height, 1);
            }
            case "VP8L" -> {
                require(b, 25);
                if ((b[20] & 0xFF) != 0x2f) throw new ImageDecodeException("WebP VP8L signature missing");
                long bits = (b[21] & 0xFFL) | (b[22] & 0xFFL) << 8 | (b[23] & 0xFFL) << 16 | (b[24] & 0xFFL) << 24;
                return new ImageDimensions(1 + (int) (bits & 0x3FFF), 1 + (int) ((bits >> 14) & 0x3FFF), 1);
            }
            default -> throw new ImageDecodeException("unknown WebP chunk '" + chunk.strip() + "'");
        }
    }

    private static void require(byte[] b, int length) throws ImageDecodeException {
        if (b.length < length) throw new ImageDecodeException("WebP header truncated");
    }

    private static int le24(byte[] b, int offset) {
        return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF) << 16;
    }
}
