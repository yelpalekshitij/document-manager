package app.vaultdoc.catalog.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagInputTest {

    // ── name ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n", "\u00A0", " \u3000 "})
    void name_rejectsNullEmptyAndBlank(String raw) {
        assertThatThrownBy(() -> TagInput.normalizeName(raw))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void name_stripsAndCollapsesWhitespace() {
        assertThat(TagInput.normalizeName("  tax  ")).isEqualTo("tax");
        assertThat(TagInput.normalizeName("tax \t\n 2024")).isEqualTo("tax 2024");
        assertThat(TagInput.normalizeName(" tax  2024 ")).isEqualTo("tax 2024");
        assertThat(TagInput.normalizeName("\u00A0tax \u00A0 2024\u3000")).isEqualTo("tax 2024");
    }

    @Test
    void name_acceptsExactly64CodePoints() {
        String name = "a".repeat(64);
        assertThat(TagInput.normalizeName(name)).isEqualTo(name);
    }

    @Test
    void name_rejects65CodePoints() {
        assertThatThrownBy(() -> TagInput.normalizeName("a".repeat(65)))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("64");
    }

    @Test
    void name_countsCodePointsNotUtf16Units() {
        // 64 emoji = 128 UTF-16 units but 64 code points — Postgres VARCHAR(64) accepts it.
        String emoji = "📄".repeat(64);
        assertThat(TagInput.normalizeName(emoji)).isEqualTo(emoji);
        assertThatThrownBy(() -> TagInput.normalizeName(emoji + "📄"))
                .isInstanceOf(InvalidTagException.class);
        assertThat(TagInput.normalizeName("कर दस्तावेज़")).isEqualTo("कर दस्तावेज़");
    }

    @Test
    void name_isNfcNormalised() {
        String decomposed = "Cafe\u0301";   // e + combining acute
        String composed = "Caf\u00e9";
        assertThat(TagInput.normalizeName(decomposed)).isEqualTo(composed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tax\u0000", "a\u0007b", "x\u001Fy", "del\u007F"})
    void name_rejectsControlCharacters(String raw) {
        assertThatThrownBy(() -> TagInput.normalizeName(raw))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("control");
    }

    // ── colour ──────────────────────────────────────────────────────────────

    @Test
    void colour_acceptsHexAndLowercases() {
        assertThat(TagInput.normalizeColor("#A1B2C3")).isEqualTo("#a1b2c3");
        assertThat(TagInput.normalizeColor(" #00ff00 ")).isEqualTo("#00ff00");
    }

    @Test
    void colour_nullMeansNoColour() {
        assertThat(TagInput.normalizeColor(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"red", "#fff", "#12345g", "#1234567", "123456", "javascript:alert(1)", "#12 456"})
    void colour_rejectsNonHex(String raw) {
        assertThatThrownBy(() -> TagInput.normalizeColor(raw))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("#rrggbb");
    }

    // ── filter ──────────────────────────────────────────────────────────────

    @Test
    void filter_nullOrEmptyIsEmpty() {
        assertThat(TagInput.normalizeFilter(null)).isEmpty();
        assertThat(TagInput.normalizeFilter(List.of())).isEmpty();
    }

    @Test
    void filter_dedupes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(TagInput.normalizeFilter(List.of(a, b, a))).containsExactly(a, b);
    }

    @Test
    void filter_accepts20() {
        List<UUID> ids = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();
        assertThat(TagInput.normalizeFilter(ids)).hasSize(20);
    }

    @Test
    void filter_rejectsMoreThan20() {
        List<UUID> ids = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> TagInput.normalizeFilter(ids))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("20");
    }

    @Test
    void filter_rejectsNullElement() {
        List<UUID> ids = new ArrayList<>(Arrays.asList(UUID.randomUUID(), null));
        assertThatThrownBy(() -> TagInput.normalizeFilter(ids))
                .isInstanceOf(InvalidTagException.class);
    }
}
