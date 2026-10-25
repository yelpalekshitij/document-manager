package app.vaultdoc.ocr.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.StorageKey;

class RoutingOcrServiceTest {

    private final OcrService pdf = mock(OcrService.class);
    private final OcrService image = mock(OcrService.class);
    private final RoutingOcrService router = new RoutingOcrService(pdf, image);
    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "x");

    @Test
    void routes_pdf_to_pdf_engine() {
        OcrResult done = OcrResult.done("pdf text", 1);
        when(pdf.extract(key, "application/pdf")).thenReturn(done);

        assertThat(router.extract(key, "application/pdf")).isSameAs(done);
        verify(image, never()).extract(any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "image/png, image/png",
            "image/jpeg, image/jpeg",
            "image/jpg, image/jpeg",
            "image/tiff, image/tiff",
            "image/webp, image/webp",
    })
    void routes_supported_images_to_image_engine(String incoming, String delegated) {
        OcrResult done = OcrResult.done("img text", 1);
        when(image.extract(key, delegated)).thenReturn(done);

        assertThat(router.extract(key, incoming)).isSameAs(done);
        verify(pdf, never()).extract(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Application/PDF;version=1.7", "APPLICATION/PDF", " application/pdf ", "application/pdf; charset=binary"})
    void normalised_pdf_mime_routes_to_pdf_engine(String incoming) {
        OcrResult done = OcrResult.done("t", 1);
        when(pdf.extract(key, "application/pdf")).thenReturn(done);

        assertThat(router.extract(key, incoming)).isSameAs(done);
        verify(image, never()).extract(any(), anyString());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "IMAGE/PNG | image/png",
            "' image/png ; q=1' | image/png",
            "Image/JPEG | image/jpeg",
            "' IMAGE/TIFF ' | image/tiff",
    })
    void normalised_image_mime_routes_to_image_engine(String incoming, String delegated) {
        OcrResult done = OcrResult.done("t", 1);
        when(image.extract(key, delegated)).thenReturn(done);

        assertThat(router.extract(key, incoming)).isSameAs(done);
        verify(pdf, never()).extract(any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "image/x-png, image/png",
            "image/pjpeg, image/jpeg",
            "image/tif, image/tiff",
            "image/x-tiff, image/tiff",
    })
    void routes_common_mime_aliases(String alias, String canonical) {
        OcrResult done = OcrResult.done("t", 1);
        when(image.extract(key, canonical)).thenReturn(done);

        assertThat(router.extract(key, alias)).isSameAs(done);
        verify(pdf, never()).extract(any(), anyString());
    }

    @Test
    void engine_out_of_memory_is_failed_not_propagated() {
        when(pdf.extract(key, "application/pdf")).thenThrow(new OutOfMemoryError("Java heap space"));

        OcrResult result = router.extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).containsIgnoringCase("memory");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "image/heic",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
    })
    void unsupported_mime_is_skipped_with_reason(String mime) {
        OcrResult result = router.extract(key, mime);

        assertThat(result.status()).isEqualTo(OcrStatus.SKIPPED);
        assertThat(result.failureReason()).contains(mime).contains("not supported");
        verify(pdf, never()).extract(any(), anyString());
        verify(image, never()).extract(any(), anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missing_mime_is_skipped_with_reason(String mime) {
        OcrResult result = router.extract(key, mime);

        assertThat(result.status()).isEqualTo(OcrStatus.SKIPPED);
        assertThat(result.failureReason()).containsIgnoringCase("no MIME type");
    }

    @Test
    void engine_crash_is_surfaced_as_failed() {
        when(image.extract(key, "image/png")).thenThrow(new IllegalStateException("boom"));

        OcrResult result = router.extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("boom");
    }

    @Test
    void engine_returning_null_is_failed() {
        when(pdf.extract(key, "application/pdf")).thenReturn(null);

        OcrResult result = router.extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).isNotBlank();
    }
}
