package app.vaultdoc.core.storage;

import java.util.UUID;

/**
 * Opaque, tenant-scoped key naming a single blob in object storage.
 *
 * <p>Shape: {@code users/{userId}/docs/{documentId}/{slug}}. The {@code slug}
 * is a filename whose path separators and control characters have been stripped
 * — never trust the original filename from the client.
 */
public record StorageKey(String value) {

    private static final int MAX_SLUG_LENGTH = 180;

    public StorageKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("StorageKey value must not be blank");
        }
    }

    public static StorageKey forDocument(UUID userId, UUID documentId, String originalFilename) {
        String slug = slug(originalFilename);
        return new StorageKey("users/%s/docs/%s/%s".formatted(userId, documentId, slug));
    }

    private static String slug(String input) {
        if (input == null || input.isBlank()) {
            return "blob";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length() && out.length() < MAX_SLUG_LENGTH; i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '\\' || c == 0 || Character.isISOControl(c)) {
                continue;
            }
            if (c == ' ') {
                out.append('_');
                continue;
            }
            out.append(c);
        }
        String result = out.toString();
        return result.isBlank() ? "blob" : result;
    }
}
