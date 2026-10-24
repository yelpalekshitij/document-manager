package app.vaultdoc.ocr.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.vaultdoc.ocr.support.TestDocuments;

class ImageDimensionsTest {

    @Test
    void reads_png_jpeg_tiff_headers() throws Exception {
        var image = TestDocuments.textImage("hi", 321, 123);

        assertThat(ImageDimensions.probe(TestDocuments.png(image))).isEqualTo(new ImageDimensions(321, 123, 1));
        assertThat(ImageDimensions.probe(TestDocuments.jpeg(image))).isEqualTo(new ImageDimensions(321, 123, 1));
        assertThat(ImageDimensions.probe(TestDocuments.tiff(image))).isEqualTo(new ImageDimensions(321, 123, 1));
    }

    @Test
    void multi_frame_tiff_reports_largest_frame_and_count() throws Exception {
        byte[] tiff = TestDocuments.multiPageTiff(List.of(
                TestDocuments.textImage("a", 100, 50),
                TestDocuments.textImage("b", 400, 200),
                TestDocuments.textImage("c", 10, 10)));

        assertThat(ImageDimensions.probe(tiff)).isEqualTo(new ImageDimensions(400, 200, 3));
    }

    @Test
    void reads_webp_vp8x_vp8_vp8l_headers() throws Exception {
        assertThat(ImageDimensions.probe(TestDocuments.webpVp8x(4000, 3000))).isEqualTo(new ImageDimensions(4000, 3000, 1));
        assertThat(ImageDimensions.probe(TestDocuments.webpVp8(1024, 768))).isEqualTo(new ImageDimensions(1024, 768, 1));
        assertThat(ImageDimensions.probe(TestDocuments.webpVp8l(16383, 2))).isEqualTo(new ImageDimensions(16383, 2, 1));
    }

    @Test
    void garbage_is_rejected() {
        byte[] riffNotWebp = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        byte[] truncatedWebp = Arrays.copyOf(TestDocuments.webpVp8x(10, 10), 20);
        byte[] badVp8StartCode = TestDocuments.webpVp8(10, 10);
        badVp8StartCode[23] = 0;
        byte[] unknownWebpChunk = TestDocuments.webpVp8x(10, 10);
        unknownWebpChunk[12] = 'Z';
        byte[] truncatedJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        byte[] png = TestDocuments.png(TestDocuments.textImage("x", 50, 50));
        byte[] truncatedPng = Arrays.copyOf(png, 12);

        for (byte[] bytes : List.of(riffNotWebp, truncatedWebp, badVp8StartCode, unknownWebpChunk,
                truncatedJpeg, truncatedPng, "hello".getBytes(StandardCharsets.US_ASCII), new byte[0])) {
            assertThatThrownBy(() -> ImageDimensions.probe(bytes)).isInstanceOf(ImageDecodeException.class);
        }
    }
}
