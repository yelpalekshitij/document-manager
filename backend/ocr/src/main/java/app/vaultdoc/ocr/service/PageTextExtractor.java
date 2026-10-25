package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Text layer of a PDF, one entry per page, in a <em>single</em> stripper pass.
 *
 * <p>Calling {@code PDFTextStripper.getText} once per page (with start/end
 * page set) walks the whole page tree on every call — O(N²) in the page
 * count, so a 50 MB PDF of 200k empty pages pinned the single OCR consumer
 * for hours. Here the page boundaries are captured from the stripper's own
 * {@code startPage}/{@code endPage} hooks instead.
 */
final class PageTextExtractor extends PDFTextStripper {

    private final StringWriter buffer = new StringWriter();
    private final String[] pages;

    private PageTextExtractor(int pageCount) {
        this.pages = new String[pageCount];
        Arrays.fill(pages, "");
        setEndPage(pageCount);
    }

    /**
     * @param maxPages only the first {@code maxPages} pages are extracted
     * @return stripped text per page; pages without a content stream map to ""
     */
    static List<String> extract(PDDocument doc, int maxPages) throws IOException {
        int limit = Math.max(0, Math.min(maxPages, doc.getNumberOfPages()));
        if (limit == 0) return List.of();
        PageTextExtractor extractor = new PageTextExtractor(limit);
        extractor.writeText(doc, extractor.buffer);
        return new ArrayList<>(Arrays.asList(extractor.pages));
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        buffer.getBuffer().setLength(0);
        super.startPage(page);
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        super.endPage(page);
        int index = getCurrentPageNo() - 1;
        if (index >= 0 && index < pages.length) {
            pages[index] = buffer.toString().strip();
        }
        buffer.getBuffer().setLength(0);
    }
}
