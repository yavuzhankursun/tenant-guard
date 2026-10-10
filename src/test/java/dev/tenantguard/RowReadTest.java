package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLWarning;
import org.junit.jupiter.api.Test;

class RowReadTest {

    @Test
    void parsesAnObserverNotice() {
        assertThat(RowRead.parse(new SQLWarning("tg|invoice|A"))).contains(new RowRead("invoice", "A"));
    }

    @Test
    void findsThePrefixAfterADriverSuppliedLead() {
        assertThat(RowRead.parse(new SQLWarning("NOTICE: tg|invoice_line|B ")))
                .contains(new RowRead("invoice_line", "B"));
    }

    @Test
    void keepsPipesInsideTheTenantValue() {
        assertThat(RowRead.parse(new SQLWarning("tg|invoice|acme|eu"))).contains(new RowRead("invoice", "acme|eu"));
    }

    @Test
    void ignoresTheApplicationsOwnWarnings() {
        // Shaped like a notice but without the prefix: must not be mistaken for a row read.
        assertThat(RowRead.parse(new SQLWarning("app|invoice|A"))).isEmpty();
    }

    @Test
    void ignoresAWarningWithoutMessage() {
        assertThat(RowRead.parse(new SQLWarning((String) null))).isEmpty();
    }

    @Test
    void ignoresATruncatedNotice() {
        assertThat(RowRead.parse(new SQLWarning("tg|invoice"))).isEmpty();
    }
}
