package app.vaultdoc.ocr.service;

import java.nio.charset.StandardCharsets;

/** Small text helpers shared by the OCR engines. */
final class OcrText {

    private OcrText() {}

    /** Truncates to {@code limitBytes} of UTF-8 without splitting a codepoint. */
    static String capToLimit(String text, int limitBytes) {
        if (text == null) return null;
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= limitBytes) return text;
        int end = Math.max(0, limitBytes);
        // utf8.length > end here, so utf8[end] exists: back up while the cut
        // would land inside a multi-byte codepoint.
        while (end > 0 && (utf8[end] & 0xC0) == 0x80) end--;
        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }

    /** Tesseract ends every page with a form feed; normalise that and outer whitespace away. */
    static String clean(String raw) {
        if (raw == null) return "";
        return raw.replace('\f', '\n').strip();
    }

    /** Count of non-whitespace codepoints — the "is there real text here?" measure. */
    static int visibleChars(String text) {
        if (text == null) return 0;
        return (int) text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    static String summarize(Throwable t) {
        String message = t.getMessage();
        if (message == null) return t.getClass().getSimpleName();
        if (message.length() > 300) return message.substring(0, 300) + "…";
        return message;
    }
}
