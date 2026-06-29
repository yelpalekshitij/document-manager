package app.vaultdoc.gateway.documents.dto;

public record DocumentDownloadResponse(String downloadUrl, long expiresInSeconds) {}
