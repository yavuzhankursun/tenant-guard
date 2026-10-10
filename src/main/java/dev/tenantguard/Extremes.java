package dev.tenantguard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Seed values for the victim tenant (A). Ordinary values hide leaks from the differential probe:
 * if A's amounts lie inside B's range, an unfiltered {@code MAX}, {@code MIN} or
 * {@code ORDER BY ... LIMIT 1} returns the same answer with or without A's rows. Seeding A at both
 * ends of every sortable column makes each of those queries change as soon as A's rows leak in.
 * Every list holds exactly two values: {@code [low, high]}. Give A one row with each.
 */
public final class Extremes {

    private static final List<BigDecimal> AMOUNTS =
            List.of(new BigDecimal("0.01"), new BigDecimal("999999999.99"));
    private static final List<Instant> INSTANTS =
            List.of(Instant.parse("1970-01-02T00:00:00Z"), Instant.parse("2999-12-31T23:59:59Z"));
    private static final List<LocalDate> DATES =
            List.of(LocalDate.of(1970, 1, 2), LocalDate.of(2999, 12, 31));
    private static final List<String> STRINGS = List.of("!!! 0000 tg-first", "zzzz tg-last");

    private Extremes() {
    }

    /**
     * {@code 0.01} and {@code 999999999.99}. Positive, so {@code CHECK (amount > 0)} constraints
     * still pass; two decimals and twelve digits, so they fit the common {@code numeric(12,2)} money
     * column instead of overflowing it.
     */
    public static List<BigDecimal> amounts() {
        return AMOUNTS;
    }

    /**
     * {@code 1970-01-02T00:00:00Z} and {@code 2999-12-31T23:59:59Z}. One day after the epoch rather
     * than the epoch itself: {@code 0} often means "unset", and a negative UTC offset would push the
     * epoch into 1969. The upper bound stays below year 3000 and fits {@code timestamptz} and
     * {@code java.time}; whole seconds survive any column precision.
     */
    public static List<Instant> instants() {
        return INSTANTS;
    }

    /** {@code 1970-01-02} and {@code 2999-12-31}, for the same reasons as {@link #instants()}. */
    public static List<LocalDate> dates() {
        return DATES;
    }

    /**
     * {@code "!!! 0000 tg-first"} and {@code "zzzz tg-last"}: first and last among values that start
     * with a letter or digit, under the byte-order {@code C} collation, ICU collations (punctuation
     * before digits before letters) and glibc locales (which ignore punctuation, so the {@code 0000}
     * and {@code zzzz} carry the order there). Not covered: values starting with whitespace or other
     * punctuation, non-Latin scripts under ICU, and, under {@code C}, anything starting with a
     * non-ASCII character, which sorts after every ASCII string. Both fit a {@code varchar(20)}.
     */
    public static List<String> strings() {
        return STRINGS;
    }
}
