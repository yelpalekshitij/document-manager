package app.vaultdoc.ocr.service;

import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import app.vaultdoc.ocr.support.TestDocuments;

class PageTextExtractorTest {

    @Test
    void splits_text_per_page_including_pages_without_content() throws Exception {
        byte[] pdf = TestDocuments.pdf(List.of("alpha page text", TestDocuments.BLANK_PAGE, "gamma page text"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(PageTextExtractor.extract(doc, 100))
                    .containsExactly("alpha page text", "", "gamma page text");
        }
    }

    @Test
    void stops_after_max_pages() throws Exception {
        byte[] pdf = TestDocuments.pdf(List.of("one", "two", "three"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(PageTextExtractor.extract(doc, 2)).containsExactly("one", "two");
        }
    }

    @Test
    void many_pages_are_extracted_in_a_single_pass() throws Exception {
        // 3000 pages: the old per-page getText() loop walked the page tree once
        // per page (≈ 9M visits); one pass is linear. Bound is generous — the
        // assertion that matters is the per-page alignment.
        List<String> pages = java.util.stream.IntStream.rangeClosed(1, 3000).mapToObj(i -> "page " + i).toList();
        byte[] pdf = TestDocuments.pdf(pages);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            long started = System.nanoTime();
            List<String> text = PageTextExtractor.extract(doc, 5000);
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(text).hasSize(3000);
            assertThat(text.get(0)).isEqualTo("page 1");
            assertThat(text.get(2999)).isEqualTo("page 3000");
            assertThat(millis).isLessThan(30_000);
        }
    }
}
