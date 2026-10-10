package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.sample.SampleSeeder;
import dev.tenantguard.scan.ResourceIds.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TableGuessingResourceIdsTest extends ScanTestBase {

    TableGuessingResourceIds ids;

    @BeforeEach
    void seedVictimAsOwner() {
        // The scanner resolves ids with the guard inactive; mirror that.
        guard.deactivate();
        new SampleSeeder(jdbc).seed(TENANT_A);
        ids = new TableGuessingResourceIds(dataSource, "tenant_id");
    }

    @Test
    @DisplayName("/invoices/{id} -> invoice tablosu, A'nın en küçük ID'si")
    void invoicesMapsToInvoiceTable() {
        Resolution resolution = ids.resolve("/invoices/{id}", "id", TENANT_A);

        assertThat(resolution.isResolved()).isTrue();
        assertThat(resolution.id()).isEqualTo("1");
    }

    @Test
    @DisplayName("Kebab-case ve değişken adı ipucu: /lines/{invoiceLineId} -> invoice_line")
    void variableNameHintFindsSnakeCaseTable() {
        assertThat(ids.resolve("/lines/{invoiceLineId}", "invoiceLineId", TENANT_A).id()).isEqualTo("1");
        assertThat(ids.resolve("/invoice-lines/{id}", "id", TENANT_A).id()).isEqualTo("1");
    }

    @Test
    @DisplayName("Bilinmeyen tablo -> çözülemedi, sebep denenen adları söyler")
    void unknownTableIsUnresolvedWithReason() {
        Resolution resolution = ids.resolve("/widgets/{id}", "id", TENANT_A);

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.reason()).contains("widget", "widgets");
    }

    @Test
    @DisplayName("Tenant'ın satırı yoksa -> çözülemedi")
    void tenantWithoutRowsIsUnresolved() {
        Resolution resolution = ids.resolve("/invoices/{id}", "id", "C");

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.reason()).contains("no rows of tenant C");
    }

    @Test
    @DisplayName("Tablo var ama tenant kolonu yok -> çözülemedi")
    void tableWithoutTenantColumnIsUnresolved() {
        Resolution resolution = new TableGuessingResourceIds(dataSource, "owner_id")
                .resolve("/invoices/{id}", "id", TENANT_A);

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.reason()).contains("owner_id");
    }

    @Test
    @DisplayName("Güvenli olmayan path segmenti SQL'e hiç ulaşmaz")
    void unsafeSegmentNeverReachesSql() {
        Resolution resolution = ids.resolve("/invoice;drop table invoice/{id}", "id", TENANT_A);

        assertThat(resolution.isResolved()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("Güvenli olmayan kolon adı yapıcıda reddedilir")
    void unsafeColumnIsRejected() {
        assertThatThrownBy(() -> new TableGuessingResourceIds(dataSource, "tenant_id; drop table invoice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Aday tablo adları: tekil, olduğu gibi, snake_case, değişken adı ipucu")
    void candidateTables() {
        assertThat(TableGuessingResourceIds.candidateTables("/invoices/{id}", "id"))
                .containsExactly("invoice", "invoices");
        assertThat(TableGuessingResourceIds.candidateTables("/companies/{id}", "id"))
                .startsWith("company");
        assertThat(TableGuessingResourceIds.candidateTables("/invoiceLines/{lineId:\\d+}", "lineId"))
                .startsWith("invoice_line", "invoice_lines").contains("line");
        assertThat(TableGuessingResourceIds.candidateTables("/{id}", "id")).isEmpty();
        assertThat(TableGuessingResourceIds.candidateTables("/x;drop/{id}", "id")).isEmpty();
    }
}
