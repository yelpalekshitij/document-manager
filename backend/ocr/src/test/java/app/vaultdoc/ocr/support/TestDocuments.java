package app.vaultdoc.ocr.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Test fixtures generated at test time with Java2D + PDFBox — no binary blobs
 * checked into the repo.
 */
public final class TestDocuments {

    private TestDocuments() {}

    /** Marker for {@link #pdf(List)}: a page with no content stream at all (e.g. a blank separator page). */
    public static final Object BLANK_PAGE = new Object();

    /** Black text on white, large font so real tesseract reads it reliably. */
    public static BufferedImage textImage(String text, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, height / 4)));
            g.drawString(text, 20, height / 2 + height / 8);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static byte[] png(BufferedImage image) {
        return encode(image, "png");
    }

    public static byte[] jpeg(BufferedImage image) {
        return encode(image, "jpeg");
    }

    /**
     * JPEG with an EXIF APP1 segment carrying only the Orientation tag (0x0112),
     * as phone cameras write it: pixels stored as shot, rotation in metadata.
     */
    public static byte[] jpegWithExifOrientation(BufferedImage image, int orientation) {
        byte[] jpeg = jpeg(image);
        // TIFF header (little-endian) + IFD0 with one SHORT entry + next-IFD 0.
        byte[] tiff = {
                'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0};
        byte[] exifId = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifId.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                   // SOI
        out.write(0xFF);
        out.write(0xE1);                                         // APP1
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exifId, 0, exifId.length);
        out.write(tiff, 0, tiff.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    public static byte[] tiff(BufferedImage image) {
        return encode(image, "tiff");
    }

    /** Multi-frame TIFF via the JDK's TIFF writer sequence support. */
    public static byte[] multiPageTiff(List<BufferedImage> pages) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (BufferedImage page : pages) {
                writer.writeToSequence(new IIOImage(page, null, null), null);
            }
            writer.endWriteSequence();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * Minimal WebP container with a VP8X chunk declaring the canvas size. Not a
     * decodable bitstream — enough for header probing.
     */
    public static byte[] webpVp8x(int width, int height) {
        byte[] b = new byte[30];
        ascii(b, 0, "RIFF");
        le32(b, 4, b.length - 8);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8X");
        le32(b, 16, 10);
        le24(b, 24, width - 1);
        le24(b, 27, height - 1);
        return b;
    }

    /** Lossy (simple format) WebP header: frame tag + start code + 14-bit dims. */
    public static byte[] webpVp8(int width, int height) {
        byte[] b = new byte[30];
        ascii(b, 0, "RIFF");
        le32(b, 4, b.length - 8);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8 ");
        le32(b, 16, 10);
        b[23] = (byte) 0x9d;
        b[24] = (byte) 0x01;
        b[25] = (byte) 0x2a;
        b[26] = (byte) (width & 0xFF);
        b[27] = (byte) ((width >> 8) & 0x3F);
        b[28] = (byte) (height & 0xFF);
        b[29] = (byte) ((height >> 8) & 0x3F);
        return b;
    }

    /** Lossless WebP header: 0x2f signature + packed 14-bit (dim - 1) fields. */
    public static byte[] webpVp8l(int width, int height) {
        byte[] b = new byte[25];
        ascii(b, 0, "RIFF");
        le32(b, 4, b.length - 8);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8L");
        le32(b, 16, 5);
        b[20] = 0x2f;
        int w = width - 1;
        int h = height - 1;
        long bits = (w & 0x3FFFL) | ((h & 0x3FFFL) << 14);
        b[21] = (byte) (bits & 0xFF);
        b[22] = (byte) ((bits >> 8) & 0xFF);
        b[23] = (byte) ((bits >> 16) & 0xFF);
        b[24] = (byte) ((bits >> 24) & 0xFF);
        return b;
    }

    /**
     * One page per entry: a string draws as real PDF text, a BufferedImage as an
     * image-only page, {@link #BLANK_PAGE} as a page without any content stream.
     */
    public static byte[] pdf(List<?> pages) {
        try (PDDocument doc = new PDDocument()) {
            for (Object content : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                if (content == BLANK_PAGE) continue;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    if (content instanceof String text) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(50, 700);
                        stream.showText(text);
                        stream.endText();
                    } else if (content instanceof BufferedImage image) {
                        PDImageXObject xobj = LosslessFactory.createFromImage(doc, image);
                        stream.drawImage(xobj, 50, 400, image.getWidth(), image.getHeight());
                    }
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A single page of the given size (points) with no content at all. */
    public static byte[] blankPdf(float widthPt, float heightPt) {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(widthPt, heightPt)));
            return save(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Letter page whose only content is an image XObject that <em>declares</em>
     * {@code width × height} samples but carries a few bytes of data — the
     * "small page, gigantic embedded image" decompression-bomb shape.
     */
    public static byte[] pdfWithDeclaredImage(int width, int height) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDImageXObject image = new PDImageXObject(doc, new ByteArrayInputStream(new byte[] {0, 0, 0, 0}),
                    COSName.FLATE_DECODE, width, height, 8, PDDeviceGray.INSTANCE);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.drawImage(image, 50, 50, 100, 100);
            }
            return save(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encryptedPdf(String text, String userPassword) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-" + userPassword, userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return save(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ascii(byte[] b, int offset, String s) {
        for (int i = 0; i < s.length(); i++) b[offset + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] b, int offset, int v) {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >> 8);
        b[offset + 2] = (byte) (v >> 16);
        b[offset + 3] = (byte) (v >> 24);
    }

    private static void le24(byte[] b, int offset, int v) {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >> 8);
        b[offset + 2] = (byte) (v >> 16);
    }
}
