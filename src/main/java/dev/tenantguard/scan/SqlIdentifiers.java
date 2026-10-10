package dev.tenantguard.scan;

import java.util.regex.Pattern;

/**
 * Table and column names guessed from URLs end up in SQL text, so only plain lower-case identifiers
 * pass, and they are double-quoted on top so reserved words like {@code order} still work.
 */
final class SqlIdentifiers {

    private static final Pattern SAFE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private SqlIdentifiers() {
    }

    static boolean isSafe(String identifier) {
        return identifier != null && SAFE.matcher(identifier).matches();
    }

    static String require(String identifier) {
        if (!isSafe(identifier)) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + identifier);
        }
        return identifier;
    }

    static String quote(String identifier) {
        return "\"" + require(identifier) + "\"";
    }
}
