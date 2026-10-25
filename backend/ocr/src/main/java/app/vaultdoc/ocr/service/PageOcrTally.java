package app.vaultdoc.ocr.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Book-keeping for OCR'ing a document page by page (scanned PDF pages, TIFF
 * frames). A failure is <em>page-local</em>: the page is recorded and the loop
 * moves on, so one bad page never throws away the text of the others.
 * Environment failures (tesseract missing, no language, no temp dir) are
 * <em>document-wide</em>: they are recorded once and every later page is
 * marked failed without retrying.
 *
 * <p>Everything that ends up not searchable is turned into a user-visible
 * note — nothing is dropped silently.
 */
final class PageOcrTally {

    private static final int MAX_LISTED_PAGES = 10;

    private final int maxPages;
    private final Duration budget;
    private final List<Integer> failed = new ArrayList<>();
    private final List<Integer> overCap = new ArrayList<>();
    private final List<Integer> overBudget = new ArrayList<>();
    private String firstFailure;
    private String documentFailure;
    private int attempts;

    PageOcrTally(int maxPages, Duration budget) {
        this.maxPages = maxPages;
        this.budget = budget;
    }

    /**
     * Whether page {@code pageNo} (1-based) should be OCR'd now. When not, the
     * reason (earlier document-wide failure, page cap, time budget) is recorded.
     */
    boolean shouldAttempt(int pageNo, boolean budgetExhausted) {
        if (documentFailure != null) {
            failed.add(pageNo);
            return false;
        }
        if (attempts >= maxPages) {
            overCap.add(pageNo);
            return false;
        }
        if (budgetExhausted) {
            overBudget.add(pageNo);
            return false;
        }
        attempts++;
        return true;
    }

    void failed(int pageNo, String reason, boolean documentWide) {
        failed.add(pageNo);
        if (firstFailure == null) firstFailure = reason;
        if (documentWide) documentFailure = reason;
    }

    boolean anyFailed() {
        return !failed.isEmpty();
    }

    /** For a document where no page produced text: {@code "<what> failed on page(s) …: <reason>"}. */
    String failureReason(String what) {
        return what + " failed on " + pages(failed) + ": " + firstFailure;
    }

    /** What was not made searchable, as user-readable sentences; empty when everything was OCR'd. */
    List<String> notes(String unit) {
        List<String> notes = new ArrayList<>();
        if (!failed.isEmpty()) {
            notes.add("OCR failed on " + pages(failed) + " (" + firstFailure + "); the rest of the document is searchable");
        }
        if (!overCap.isEmpty()) {
            int total = attempts + overCap.size() + overBudget.size();
            notes.add("Only the first %d of %d %s were OCR'd (limit %d per document); %s not searchable"
                    .formatted(attempts, total, unit, maxPages, pages(overCap)));
        }
        if (!overBudget.isEmpty()) {
            notes.add("OCR stopped at the %s time budget; %s not OCR'd"
                    .formatted(human(budget), pages(overBudget)));
        }
        return notes;
    }

    /** {@code page 3}, {@code pages 1, 2, 3}, {@code pages 1, 2, … (40 in total)}. */
    static String pages(List<Integer> pageNumbers) {
        if (pageNumbers.size() == 1) return "page " + pageNumbers.getFirst();
        String listed = pageNumbers.stream().limit(MAX_LISTED_PAGES).map(String::valueOf).collect(Collectors.joining(", "));
        return pageNumbers.size() <= MAX_LISTED_PAGES
                ? "pages " + listed
                : "pages " + listed + ", … (" + pageNumbers.size() + " in total)";
    }

    private static String human(Duration d) {
        if (d.toSeconds() % 60 == 0) return d.toMinutes() + "-minute";
        return d.toSeconds() + "-second";
    }
}
