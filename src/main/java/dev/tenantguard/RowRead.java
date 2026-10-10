package dev.tenantguard;

import java.sql.SQLWarning;
import java.util.Optional;

/** One row the executor handed to the observer policy, decoded from a {@code RAISE NOTICE}. */
public record RowRead(String table, String tenant) {

    static final String PREFIX = "tg|";

    /** Notices look like {@code tg|<table>|<tenant>}; anything else is the application's own warning. */
    static Optional<RowRead> parse(SQLWarning warning) {
        String message = warning.getMessage();
        int start = message == null ? -1 : message.indexOf(PREFIX);
        if (start < 0) {
            return Optional.empty();
        }
        String[] parts = message.substring(start + PREFIX.length()).split("\\|", 2);
        return parts.length == 2 ? Optional.of(new RowRead(parts[0], parts[1].strip())) : Optional.empty();
    }
}
