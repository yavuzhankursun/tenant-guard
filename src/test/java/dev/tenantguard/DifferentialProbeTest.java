package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Result;
import dev.tenantguard.DifferentialProbe.Verdict;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The diff itself, with scripted responses instead of a database. */
class DifferentialProbeTest {

    private final TenantGuard guard = new TenantGuard();

    @Test
    void sameSizeListWithDifferentContentIsALeak() {
        Result result = probe(List.of("B-1", "B-2"), List.of("B-1", "B-2"), List.of("B-1", "A-1"));

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.changedPaths()).containsExactly("$[1]");
    }

    @Test
    void fieldThatTurnsFromNullToAbsentIsAChange() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("owner", null);

        Result result = probe(withNull, withNull, Map.of());

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.changedPaths()).containsExactly("$.owner");
    }

    @Test
    void unchangedResponseIsClean() {
        assertThat(probe(List.of("B-1"), List.of("B-1"), List.of("B-1")).verdict()).isEqualTo(Verdict.CLEAN);
    }

    /** Two baseline calls, then one compare call. */
    private Result probe(Object... responses) {
        Deque<Object> queue = new ArrayDeque<>(List.of(responses));
        Supplier<Object> call = queue::removeFirst;
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, "B", List.of(new Endpoint("e", call)));
        return probe.compare().get(0);
    }
}
