package dev.tenantguard;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Identifiers end up in DDL and SQL text, so they pass an allowlist before they get there.
 *
 * <p>{@link #require} is the strict form for names we choose ourselves (the observed role): plain
 * lower-case, never needs quoting. {@link #quote} is for names the app chose: tables and columns
 * are always double-quoted, so mixed case ({@code "MixedCase"}) and reserved words ({@code "order"})
 * work, but only letters, digits, {@code _} and {@code $} are accepted. That excludes the
 * double quote (the only character that could break out of a quoted identifier), the single quote
 * (labels go into a string literal), {@code |} (the NOTICE delimiter), {@code .} (the schema
 * separator in labels), whitespace and control characters. Names outside the allowlist are
 * skipped by discovery with a reason, never escaped.
 */
final class Identifiers {

    private static final Pattern SAFE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern QUOTABLE = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*");
    /** Postgres truncates longer names to NAMEDATALEN - 1 bytes; a truncated name is another name. */
    private static final int MAX_BYTES = 63;

    private Identifiers() {
    }

    static String require(String identifier) {
        if (identifier == null || !SAFE.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + identifier);
        }
        return identifier;
    }

    static boolean isQuotable(String identifier) {
        return identifier != null && QUOTABLE.matcher(identifier).matches()
                && identifier.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    /** The identifier exactly as stored in the catalog, double-quoted. */
    static String quote(String identifier) {
        if (!isQuotable(identifier)) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    /** {@code table} or {@code schema.table}, each part {@link #quote quoted}. */
    static String quoteQualified(String name) {
        String[] parts = split(name);
        return parts.length == 1 ? quote(parts[0]) : quote(parts[0]) + "." + quote(parts[1]);
    }

    /** One or two parts; {@code a.b.c} and empty parts are rejected. */
    static String[] split(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Unsupported SQL identifier: null");
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Unsupported qualified name (schema.table at most): " + name);
        }
        for (String part : parts) {
            quote(part);
        }
        return parts;
    }
}
