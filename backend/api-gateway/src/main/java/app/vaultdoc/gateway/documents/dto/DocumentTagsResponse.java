package app.vaultdoc.gateway.documents.dto;

import java.util.List;
import java.util.UUID;

public record DocumentTagsResponse(UUID documentId, List<TagRefDto> tags) {}
