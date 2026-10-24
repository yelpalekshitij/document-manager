package app.vaultdoc.catalog.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure normalisation + validation rules for tag input. Kept free of Spring so
 * the rules are unit-testable in isolation.
 */
public final class TagInput {

    /** Postgres {@code VARCHAR(64)} counts characters (code points), not UTF-16 units. */
    public static final int MAX_NAME_CODE_POINTS = 64;
    public static final int MAX_FILTER_TAGS = 20;

    // UNICODE_CHARACTER_CLASS makes \s cover NBSP, ideographic space, etc. —
    // String.strip() alone misses NBSP.
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private TagInput() {}

    /**
     * NFC-normalise, collapse whitespace runs to one space, trim, and enforce
     * 1–64 code points with no control characters.
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            throw new InvalidTagException("Tag name must not be blank");
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String collapsed = WHITESPACE_RUN.matcher(nfc).replaceAll(" ").strip();
        if (collapsed.isEmpty()) {
            throw new InvalidTagException("Tag name must not be blank");
        }
        if (collapsed.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidTagException("Tag name must not contain control characters");
        }
        if (collapsed.codePointCount(0, collapsed.length()) > MAX_NAME_CODE_POINTS) {
            throw new InvalidTagException("Tag name must be at most " + MAX_NAME_CODE_POINTS + " characters");
        }
        return collapsed;
    }

    /** {@code null} → no colour; otherwise {@code #rrggbb}, stored lowercase. */
    public static String normalizeColor(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw new InvalidTagException("Tag colour must be a #rrggbb hex value");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Tag-filter ids for list/search: deduplicated (so AND-counting isn't
     * inflated), insertion-ordered, at most {@value #MAX_FILTER_TAGS}.
     */
    public static Set<UUID> normalizeFilter(Collection<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return Set.of();
        var unique = new LinkedHashSet<UUID>();
        for (UUID id : tagIds) {
            if (id == null) {
                throw new InvalidTagException("Tag filter must not contain null ids");
            }
            unique.add(id);
        }
        if (unique.size() > MAX_FILTER_TAGS) {
            throw new InvalidTagException("At most " + MAX_FILTER_TAGS + " tags can be combined in a filter");
        }
        return unique;
    }
}
