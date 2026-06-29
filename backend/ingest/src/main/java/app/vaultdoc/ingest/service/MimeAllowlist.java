package app.vaultdoc.ingest.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MimeAllowlist {

    private final Set<String> allowed;

    public MimeAllowlist(Collection<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalArgumentException("MIME allowlist must not be empty");
        }
        var normalized = new LinkedHashSet<String>(allowed.size());
        for (var mime : allowed) {
            if (mime != null && !mime.isBlank()) {
                normalized.add(mime.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.allowed = Set.copyOf(normalized);
    }

    public boolean isAllowed(String mimeType) {
        if (mimeType == null) return false;
        return allowed.contains(mimeType.toLowerCase(Locale.ROOT).trim());
    }

    public Set<String> asSet() {
        return allowed;
    }
}
