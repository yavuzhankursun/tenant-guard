package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the claims in {@link Extremes}' javadoc against a real Postgres, not against Java ordering. */
class ExtremesTest {

    /** Realistic values that start with a letter or digit; the extremes must bracket all of them. */
    private static final List<String> REALISTIC_TEXT = List.of(
            "A-1", "Acme GmbH", "acme", "0001", "123 Main St", "Invoice #42 (draft)", "zebra", "Zoo",
            "zz top", "Zürich");

    @Test
    void amountsBracketRealisticValuesAndFitNumeric12_2() throws SQLException {
        BigDecimal low = Extremes.amounts().get(0);
        BigDecimal high = Extremes.amounts().get(1);

        assertThat(low).isPositive().isLessThan(BigDecimal.ONE);
        assertThat(high).isGreaterThan(new BigDecimal("100000000"));
        try (Connection c = GuardedTestBase.ownerConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ?::numeric(12,2), ?::numeric(12,2)")) {
            ps.setBigDecimal(1, low);
            ps.setBigDecimal(2, high);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(low);
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(high);
            }
        }
    }

    @Test
    void instantsAndDatesAreOrderedAndRoundTripThroughTimestamptz() throws SQLException {
        Instant oldest = Extremes.instants().get(0);
        Instant newest = Extremes.instants().get(1);

        assertThat(oldest).isAfter(Instant.EPOCH).isBefore(Instant.parse("1971-01-01T00:00:00Z"));
        assertThat(newest).isAfter(Instant.parse("2900-01-01T00:00:00Z"));
        assertThat(Extremes.dates().get(0)).isAfter(LocalDate.EPOCH).isBefore(LocalDate.of(1971, 1, 1));
        assertThat(Extremes.dates().get(1)).isAfter(LocalDate.of(2900, 1, 1));
        try (Connection c = GuardedTestBase.ownerConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ?::timestamptz, ?::timestamptz")) {
            ps.setTimestamp(1, Timestamp.from(oldest));
            ps.setTimestamp(2, Timestamp.from(newest));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getTimestamp(1).toInstant()).isEqualTo(oldest);
                assertThat(rs.getTimestamp(2).toInstant()).isEqualTo(newest);
            }
        }
    }

    @Test
    void listsAreImmutable() {
        assertThat(Extremes.amounts()).hasSize(2).isUnmodifiable();
        assertThat(Extremes.instants()).hasSize(2).isUnmodifiable();
        assertThat(Extremes.dates()).hasSize(2).isUnmodifiable();
        assertThat(Extremes.strings()).hasSize(2).isUnmodifiable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"C", "en-x-icu", "und-x-icu"})
    void stringsSortFirstAndLastUnderCommonCollations(String collation) throws SQLException {
        // ICU collations exist only when the server was built --with-icu (every official image is).
        assumeTrue(collationExists(collation), () -> "collation \"" + collation + "\" not in pg_collation on "
                + TestPostgres.image());
        List<String> realistic = "C".equals(collation)
                // Byte order: text starting with a non-ASCII letter sorts after any ASCII value (documented).
                ? REALISTIC_TEXT.stream().filter(s -> s.chars().allMatch(ch -> ch < 128)).toList()
                : REALISTIC_TEXT;
        List<String> values = Stream.concat(realistic.stream(), Extremes.strings().stream()).toList();

        List<String> sorted = sortInPostgres(values, collation);

        assertThat(sorted.get(0)).isEqualTo(Extremes.strings().get(0));
        assertThat(sorted.get(sorted.size() - 1)).isEqualTo(Extremes.strings().get(1));
    }

    private static boolean collationExists(String collation) throws SQLException {
        try (Connection c = GuardedTestBase.ownerConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_collation WHERE collname = ?")) {
            ps.setString(1, collation);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<String> sortInPostgres(List<String> values, String collation) throws SQLException {
        // Collation names cannot be bound; the value comes from the @ValueSource above.
        String sql = "SELECT s FROM unnest(?::text[]) AS t(s) ORDER BY s COLLATE \"" + collation + "\"";
        try (Connection c = GuardedTestBase.ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            Array array = c.createArrayOf("text", values.toArray());
            ps.setArray(1, array);
            List<String> sorted = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sorted.add(rs.getString(1));
                }
            }
            return sorted;
        }
    }
}
