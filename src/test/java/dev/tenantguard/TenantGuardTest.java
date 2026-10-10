package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import dev.tenantguard.TenantGuard.HarvestPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantGuardTest {

    private static final RowRead A = new RowRead("invoice", "A");
    private static final RowRead B = new RowRead("invoice", "B");

    private final TenantGuard guard = new TenantGuard();

    @Test
    void anEmptyHarvestRecordsNothing() {
        guard.record("SELECT 1", List.of(), HarvestPoint.EXECUTE);

        assertThat(guard.observations()).isEmpty();
        assertThat(guard.harvestedRows()).isEmpty();
    }

    @Test
    void countsEveryReadAndTagsTheActor() {
        guard.runAs("B", () -> guard.record("q", List.of(A, A, B), HarvestPoint.FETCH));

        assertThat(guard.harvestedRows()).containsOnly(entry(HarvestPoint.FETCH, 3L));
        assertThat(guard.rowReads()).containsOnly(entry(A, 2L), entry(B, 1L));
        assertThat(guard.observations()).singleElement().satisfies(o -> assertThat(o.actor()).isEqualTo("B"));
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH)).hasSize(1);
    }

    @Test
    void callAsRestoresThePreviousActor() {
        guard.runAs("B", () -> guard.runAs("C", () -> { }));
        guard.record("passive", List.of(A), HarvestPoint.EXECUTE);

        assertThat(guard.observations()).singleElement().satisfies(o -> assertThat(o.actor()).isNull());
    }

    @Test
    void callAsRestoresTheActorWhenTheActionThrows() {
        assertThatThrownBy(() -> guard.runAs("B", () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        guard.record("passive", List.of(A), HarvestPoint.EXECUTE);

        assertThat(guard.findings()).as("no actor left behind, so no ACTOR_MISMATCH").isEmpty();
    }
}
