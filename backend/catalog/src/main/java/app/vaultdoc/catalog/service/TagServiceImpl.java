package app.vaultdoc.catalog.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.DocumentStatus;
import app.vaultdoc.catalog.domain.DocumentTagRepository;
import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.domain.TagRepository;
import app.vaultdoc.catalog.domain.TagWithCount;
import app.vaultdoc.catalog.event.DocumentTagsChangedEvent;

@Transactional
public class TagServiceImpl implements TagService {

    /** Same order as the list endpoint: case-insensitive name, id as tie-breaker. */
    private static final Comparator<Tag> BY_NAME = Comparator
            .comparing((Tag t) -> t.getName().toLowerCase(Locale.ROOT))
            .thenComparing(Tag::getId);

    private final TagRepository tags;
    private final DocumentTagRepository documentTags;
    private final DocumentRepository documents;
    private final ApplicationEventPublisher events;

    public TagServiceImpl(
            TagRepository tags,
            DocumentTagRepository documentTags,
            DocumentRepository documents,
            ApplicationEventPublisher events) {
        this.tags = tags;
        this.documentTags = documentTags;
        this.documents = documents;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagWithCount> listForUser(UUID userId) {
        return tags.findAllWithCounts(userId);
    }

    @Override
    public Tag create(UUID userId, String name, String color) {
        String normalizedName = TagInput.normalizeName(name);
        String normalizedColor = TagInput.normalizeColor(color);
        if (tags.countByUserId(userId) >= MAX_TAGS_PER_USER) {
            throw new TagLimitExceededException(MAX_TAGS_PER_USER);
        }
        if (tags.findByUserIdAndNameIgnoreCase(userId, normalizedName).isPresent()) {
            throw new TagNameConflictException(normalizedName);
        }
        return saveOrConflict(new Tag(null, userId, normalizedName, normalizedColor));
    }

    @Override
    public Tag update(UUID userId, UUID tagId, String newName, String color) {
        Tag tag = requireTag(userId, tagId);
        boolean renamed = false;
        if (newName != null) {
            String normalizedName = TagInput.normalizeName(newName);
            tags.findByUserIdAndNameIgnoreCase(userId, normalizedName)
                    .filter(other -> !other.getId().equals(tag.getId()))
                    .ifPresent(other -> {
                        throw new TagNameConflictException(normalizedName);
                    });
            if (!normalizedName.equals(tag.getName())) {
                tag.rename(normalizedName);
                renamed = true;
            }
        }
        if (color != null) {
            tag.setColor(color.isBlank() ? null : TagInput.normalizeColor(color));
        }
        Tag saved = saveOrConflict(tag);
        // Colour isn't in the search index; only a rename changes what ES holds.
        if (renamed) {
            publish(userId, documentTags.findDocumentIdsByTagId(tagId));
        }
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public long documentCount(UUID userId, UUID tagId) {
        requireTag(userId, tagId);
        return tags.countLiveDocuments(tagId);
    }

    @Override
    public void delete(UUID userId, UUID tagId) {
        Tag tag = requireTag(userId, tagId);
        // Capture before the FK cascade removes the join rows.
        List<UUID> affected = documentTags.findDocumentIdsByTagId(tagId);
        tags.delete(tag);
        publish(userId, affected);
    }

    @Override
    public List<Tag> setDocumentTags(UUID userId, UUID documentId, Collection<UUID> tagIds) {
        requireLiveDocument(userId, documentId);
        Set<UUID> desired = normalizeAssignment(tagIds);
        List<Tag> resolved = desired.isEmpty() ? List.of() : tags.findAllByUserIdAndIdIn(userId, desired);
        if (resolved.size() != desired.size()) {
            Set<UUID> found = new HashSet<>();
            resolved.forEach(t -> found.add(t.getId()));
            UUID missing = desired.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new TagNotFoundException(missing);
        }

        Set<UUID> current = new HashSet<>(documentTags.findTagIdsByDocumentId(documentId));
        boolean changed = false;
        for (UUID existing : current) {
            if (!desired.contains(existing)) {
                documentTags.detach(documentId, existing);
                changed = true;
            }
        }
        for (UUID wanted : desired) {
            if (!current.contains(wanted)) {
                documentTags.attach(documentId, wanted);
                changed = true;
            }
        }
        if (changed) {
            publish(userId, List.of(documentId));
        }
        return resolved.stream().sorted(BY_NAME).toList();
    }

    @Override
    public List<Tag> addDocumentTag(UUID userId, UUID documentId, UUID tagId) {
        requireLiveDocument(userId, documentId);
        requireTag(userId, tagId);
        if (documentTags.attach(documentId, tagId) > 0) {
            publish(userId, List.of(documentId));
        }
        return tagsOf(userId, documentId);
    }

    @Override
    public List<Tag> removeDocumentTag(UUID userId, UUID documentId, UUID tagId) {
        requireLiveDocument(userId, documentId);
        requireTag(userId, tagId);
        if (documentTags.detach(documentId, tagId) > 0) {
            publish(userId, List.of(documentId));
        }
        return tagsOf(userId, documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<Tag>> tagsForDocuments(UUID userId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        Map<UUID, List<Tag>> result = new LinkedHashMap<>();
        for (UUID id : documentIds) result.put(id, new ArrayList<>());
        for (Object[] row : documentTags.findTagsForDocuments(userId, documentIds)) {
            UUID documentId = (UUID) row[0];
            Tag tag = (Tag) row[1];
            result.computeIfAbsent(documentId, k -> new ArrayList<>()).add(tag);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<Tag>> tagsForIndexing(UUID userId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        List<UUID> ready = documents.findReadyIds(userId, documentIds);
        if (ready.isEmpty()) return Map.of();
        return tagsForDocuments(userId, ready);
    }

    private List<Tag> tagsOf(UUID userId, UUID documentId) {
        return tagsForDocuments(userId, List.of(documentId)).getOrDefault(documentId, List.of());
    }

    private Tag requireTag(UUID userId, UUID tagId) {
        return tags.findByIdAndUserId(tagId, userId)
                .orElseThrow(() -> new TagNotFoundException(tagId));
    }

    /** Owned and not soft-deleted — a deleted document is as invisible as a foreign one. */
    private Document requireLiveDocument(UUID userId, UUID documentId) {
        return documents.findByIdAndUserId(documentId, userId)
                .filter(d -> d.getStatus() != DocumentStatus.DELETED)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private static Set<UUID> normalizeAssignment(Collection<UUID> tagIds) {
        if (tagIds == null) return Set.of();
        var unique = new LinkedHashSet<UUID>();
        for (UUID id : tagIds) {
            if (id == null) {
                throw new InvalidTagException("Tag ids must not contain null");
            }
            unique.add(id);
        }
        if (unique.size() > MAX_TAGS_PER_DOCUMENT) {
            throw new InvalidTagException("A document can carry at most " + MAX_TAGS_PER_DOCUMENT + " tags");
        }
        return unique;
    }

    /** The pre-check catches the common case; the unique index is the arbiter under concurrency. */
    private Tag saveOrConflict(Tag tag) {
        try {
            return tags.saveAndFlush(tag);
        } catch (DataIntegrityViolationException e) {
            throw new TagNameConflictException(tag.getName());
        }
    }

    private void publish(UUID userId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) return;
        events.publishEvent(new DocumentTagsChangedEvent(userId, Set.copyOf(documentIds)));
    }
}
