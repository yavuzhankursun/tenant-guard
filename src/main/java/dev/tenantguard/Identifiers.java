package dev.tenantguard;

import java.util.regex.Pattern;

/** Identifiers end up concatenated into DDL, so only plain lower-case SQL identifiers are accepted. */
final class Identifiers {

    private static final Pattern SAFE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private Identifiers() {
    }

    static String require(String identifier) {
        if (identifier == null || !SAFE.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + identifier);
        }
        return identifier;
    }
}
