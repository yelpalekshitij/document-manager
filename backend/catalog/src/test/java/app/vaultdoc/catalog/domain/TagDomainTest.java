package app.vaultdoc.catalog.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagDomainTest {

    @Test
    void tag_prePersistAssignsIdAndCreatedAt_andMutatorsApply() {
        UUID owner = UUID.randomUUID();
        Tag t = new Tag(null, owner, "tax", null);

        t.onCreate();
        t.rename("taxes");
        t.setColor("#112233");

        assertThat(t.getId()).isNotNull();
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUserId()).isEqualTo(owner);
        assertThat(t.getName()).isEqualTo("taxes");
        assertThat(t.getColor()).isEqualTo("#112233");
    }

    @Test
    void tag_prePersistKeepsExplicitId() {
        UUID id = UUID.randomUUID();
        Tag t = new Tag(id, UUID.randomUUID(), "x", null);

        t.onCreate();

        assertThat(t.getId()).isEqualTo(id);
    }

    @Test
    void documentTag_keyHasValueSemantics() {
        UUID doc = UUID.randomUUID();
        UUID tag = UUID.randomUUID();
        var a = new DocumentTag.Key(doc, tag);
        var b = new DocumentTag.Key(doc, tag);
        var other = new DocumentTag.Key(doc, UUID.randomUUID());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other).isNotEqualTo("x");
        assertThat(new DocumentTag.Key()).isEqualTo(new DocumentTag.Key());

        var row = new DocumentTag(doc, tag);
        assertThat(row.getDocumentId()).isEqualTo(doc);
        assertThat(row.getTagId()).isEqualTo(tag);
    }
}
