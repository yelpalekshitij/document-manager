package app.vaultdoc.gateway.documents.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Name/colour rules (trim, collapse, NFC, 1–64 code points, {@code #rrggbb})
 * live in {@code TagInput} in the catalog — a {@code @Size} here would count
 * UTF-16 units and reject a 64-emoji name the database accepts.
 */
public record CreateTagRequest(
        @NotNull String name,
        String color) {}
