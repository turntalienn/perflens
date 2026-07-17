package io.perflens.core.context;

import java.util.Locale;

/**
 * Produces a stable grouping key for SQL statements.
 *
 * <p>The normalizer is intentionally conservative. It replaces common literal
 * values with placeholders and collapses whitespace, which is enough to group
 * many repeated query shapes without attempting to parse vendor-specific SQL.</p>
 */
public final class SqlNormalizer {

    private SqlNormalizer() {
    }

    /**
     * Normalizes SQL for grouping repeated query shapes.
     *
     * @param sql SQL text captured from JDBC
     * @return normalized SQL text, never {@code null}
     */
    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql
                .replaceAll("'([^']|'')*'", "?")
                .replaceAll("\\b\\d+(\\.\\d+)?\\b", "?")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
