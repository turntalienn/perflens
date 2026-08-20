package io.perflens.core.context;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces SQL to its shape so executions that differ only in literal values group together.
 *
 * <p>String and numeric literals become {@code ?}, IN-lists of any length become
 * {@code in (?)}, and whitespace is collapsed. It is a regex pass, not a parser, so
 * exotic vendor syntax may not normalize perfectly; that only affects grouping.</p>
 */
public final class SqlNormalizer {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlNormalizer() {
    }

    /**
     * @param sql raw SQL, may be {@code null}
     * @return lower-cased normalized SQL, never {@code null}
     */
    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String normalized = STRING_LITERAL.matcher(sql).replaceAll("?");
        normalized = NUMBER_LITERAL.matcher(normalized).replaceAll("?");
        normalized = IN_LIST.matcher(normalized).replaceAll("in (?)");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }
}
