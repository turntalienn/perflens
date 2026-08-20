package io.perflens.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlNormalizerTest {

    @Test
    void replacesLiteralsAndCollapsesWhitespace() {
        assertThat(SqlNormalizer.normalize("SELECT *  FROM item\n WHERE id = 42 AND name = 'it''s'"))
                .isEqualTo("select * from item where id = ? and name = ?");
    }

    @Test
    void collapsesInListsOfAnyLength() {
        assertThat(SqlNormalizer.normalize("select * from book where author_id in (?, ?, ?)"))
                .isEqualTo(SqlNormalizer.normalize("select * from book where author_id IN (1,2)"))
                .isEqualTo("select * from book where author_id in (?)");
    }

    @Test
    void keepsIdentifiersContainingDigits() {
        assertThat(SqlNormalizer.normalize("select b1_0.id from book b1_0"))
                .isEqualTo("select b1_0.id from book b1_0");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(SqlNormalizer.normalize(null)).isEmpty();
        assertThat(SqlNormalizer.normalize("  ")).isEmpty();
    }
}
