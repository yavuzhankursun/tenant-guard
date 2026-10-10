package dev.tenantguard;

import java.sql.SQLWarning;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * One row the executor handed to the observer policy, decoded from a {@code RAISE NOTICE}: read
 * through the policy's USING, or written through its WITH CHECK. The name predates writes; a
 * {@link Kind#WRITE} row is a different fact about the same table, never merged with a read.
 *
 * @param kind {@link Kind#READ} for every row USING saw (including the old row of an UPDATE or
 *             DELETE), {@link Kind#WRITE} for every new row of an INSERT or UPDATE
 */
public record RowRead(String table, String tenant, Kind kind) {

    /** Which policy clause raised the notice; the code is the notice field that carries it. */
    public enum Kind {
        READ("r"), WRITE("w");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static Optional<Kind> ofCode(String code) {
            for (Kind kind : values()) {
                if (kind.code.equals(code)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    static final String PREFIX = "tg:";
    private static final int FIELDS = 4;

    /** A read; kept so code written before writes were observed still compiles. */
    public RowRead(String table, String tenant) {
        this(table, tenant, Kind.READ);
    }

    public static RowRead write(String table, String tenant) {
        return new RowRead(table, tenant, Kind.WRITE);
    }

    /**
     * Notices look like {@code tg:<nonce>|<r|w>|<table>|<tenant>}. Only a nonce {@code trusted}
     * accepts makes one an observation: anyone can {@code RAISE NOTICE 'tg|...'} (the app itself, or
     * a tenant-controlled string the app raises), only the installer knows the nonce. The tenant is
     * the last field, so a tenant value containing {@code |} survives; labels never contain one.
     */
    static Optional<RowRead> parse(SQLWarning warning, Predicate<String> trusted) {
        String message = warning.getMessage();
        int start = message == null ? -1 : message.indexOf(PREFIX);
        if (start < 0) {
            return Optional.empty();
        }
        String[] parts = message.substring(start + PREFIX.length()).split("\\|", FIELDS);
        if (parts.length != FIELDS || parts[0].isEmpty() || parts[2].isEmpty() || !trusted.test(parts[0])) {
            return Optional.empty();
        }
        return Kind.ofCode(parts[1]).map(kind -> new RowRead(parts[2], parts[3].strip(), kind));
    }
}
