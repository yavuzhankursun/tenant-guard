package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.scan.RowSnapshot.Row;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The data-side verdict: what happened to the victim's rows between two snapshots. */
class RowSnapshotTest {

    private static final Row A1 = new Row("(1)", "A", "h1");
    private static final Row A2 = new Row("(2)", "A", "h2");
    private static final Row B3 = new Row("(3)", "B", "h3");
    private static final Row L1 = new Row("(10)", "A", "l1");
    private static final Row L2 = new Row("(11)", "A", "l2");

    private static RowSnapshot snapshot(List<Row> invoices, List<Row> lines) {
        Map<String, List<Row>> tables = new LinkedHashMap<>();
        tables.put("invoice", invoices);
        tables.put("invoice_line", lines);
        return new RowSnapshot(tables);
    }

    private static final RowSnapshot BEFORE = snapshot(List.of(A1, A2, B3), List.of(L1, L2));

    @Test
    @DisplayName("Değişmeyen veri: değişiklik yok, snapshot'lar eşit (satır sırası önemsiz)")
    void unchangedDataHasNoChanges() {
        RowSnapshot same = snapshot(List.of(B3, A2, A1), List.of(L2, L1));

        assertThat(BEFORE.victimChanges(same, "A")).isEmpty();
        assertThat(same).isEqualTo(BEFORE);
    }

    @Test
    @DisplayName("Güncellenen, silinen, başka tenant'a taşınan ve kurban adına oluşturulan satırlar tabloyla adlandırılır")
    void namesEveryKindOfChangePerTable() {
        RowSnapshot after = snapshot(
                List.of(new Row("(1)", "A", "changed"), new Row("(2)", "B", "h2x"), B3, new Row("(4)", "A", "new")),
                List.of());

        assertThat(BEFORE.victimChanges(after, "A")).containsExactly(
                "invoice: 1 row updated", "invoice: 1 row moved to tenant B", "invoice: 1 row created with tenant A",
                "invoice_line: 2 rows deleted");
    }

    @Test
    @DisplayName("Saldırganın kendi satırlarındaki değişiklik kurbanın verisi sayılmaz, ama snapshot kirlenir")
    void attackerOwnChangesAreNotVictimChanges() {
        RowSnapshot after = snapshot(List.of(A1, A2, new Row("(3)", "B", "changed"), new Row("(5)", "B", "new")),
                List.of(L1, L2));

        assertThat(BEFORE.victimChanges(after, "A")).isEmpty();
        assertThat(after).isNotEqualTo(BEFORE);
    }

    @Test
    @DisplayName("Primary key'siz tablo: satırlar hash ile sayılır, güncelleme silme + oluşturma olarak görünür")
    void tablesWithoutPrimaryKeyCompareByHash() {
        RowSnapshot before = new RowSnapshot(Map.of("audit", List.of(new Row(null, "A", "x"), new Row(null, "A", "x"))));
        RowSnapshot after = new RowSnapshot(Map.of("audit", List.of(new Row(null, "A", "x"), new Row(null, "A", "y"))));

        assertThat(before.victimChanges(after, "A"))
                .containsExactly("audit: 1 row deleted", "audit: 1 row created with tenant A");
    }
}
