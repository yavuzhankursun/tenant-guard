package dev.tenantguard.corpus;

import static dev.tenantguard.DifferentialProbe.Verdict.CLEAN;
import static dev.tenantguard.DifferentialProbe.Verdict.LEAK;
import static dev.tenantguard.DifferentialProbe.Verdict.SUSPECT;
import static dev.tenantguard.corpus.LeakCorpusTest.Layer.CAUGHT;
import static dev.tenantguard.corpus.LeakCorpusTest.Layer.SILENT;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Result;
import dev.tenantguard.DifferentialProbe.Verdict;
import dev.tenantguard.Finding;
import dev.tenantguard.Finding.Rule;
import dev.tenantguard.sample.corpus.CorpusAsyncReports;
import dev.tenantguard.sample.corpus.CorpusCachedReports;
import dev.tenantguard.sample.corpus.CorpusInvoice;
import dev.tenantguard.sample.corpus.CorpusInvoiceRepository;
import dev.tenantguard.sample.corpus.CorpusInvoiceRepository.NameOnly;
import dev.tenantguard.sample.corpus.CorpusLineRepository;
import dev.tenantguard.sample.corpus.CorpusQueries;
import dev.tenantguard.sample.corpus.InvoiceView;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The leak corpus: one row per pattern, three layers per row. Each row is called as the attacker
 * (B) three ways: twice for the differential baseline while only B's data exists, once more after
 * the victim (A) is seeded (the probe's verdict), and once in a fresh {@code guard.runAs("B", ...)}
 * whose findings decide the observer ({@code ACTOR_MISMATCH}) and passive
 * ({@code MULTI_TENANT_STATEMENT}) columns.
 *
 * <p>The expectations are the result, not a wish: a row that says SILENT on a leaky pattern is a
 * proven blind spot. Change a row only when Postgres or the tool changes, and say why in its note.
 */
class LeakCorpusTest extends CorpusTestBase {

    enum Layer { CAUGHT, SILENT }

    /**
     * @param call         builds the B-side call from the test's beans
     * @param beforeCall   runs after A is seeded, before the compare call and again before the observer call
     * @param minServerVersion lowest {@code server_version_num} the pattern exists on; older servers skip the row
     */
    record Pattern(String id, String name, Layer observer, Layer passive, Verdict diff,
                   Function<LeakCorpusTest, Supplier<Object>> call, Consumer<LeakCorpusTest> beforeCall, String note,
                   int minServerVersion) {

        Pattern since(int serverVersionNum) {
            return new Pattern(id, name, observer, passive, diff, call, beforeCall, note, serverVersionNum);
        }

        @Override
        public String toString() {
            return "%s %s → observer %s, passive %s, diff %s".formatted(id, name, observer, passive, diff);
        }
    }

    private static Pattern row(String id, String name, Layer observer, Layer passive, Verdict diff,
                               Function<LeakCorpusTest, Supplier<Object>> call, String note) {
        return new Pattern(id, name, observer, passive, diff, call, test -> { }, note, 0);
    }

    private static final String OPEN = "open";
    /** A name nobody has: the answer is false with and without A's rows. */
    private static final String FREE_NAME = "B-3";
    /** A's first invoice: the answer flips from false to true once A exists. */
    private static final String VICTIM_NAME = "A-1";

    static Stream<Pattern> corpus() {
        return Stream.of(
                row("01", "JPQL @Query list, no tenant filter", CAUGHT, CAUGHT, LEAK,
                        t -> () -> views(t.invoices.jpqlAll()), "seq scan reads every row"),
                row("02", "derived query by non-tenant field (findByStatus)", CAUGHT, CAUGHT, LEAK,
                        t -> () -> views(t.invoices.findByStatusOrderById(OPEN)),
                        "status = ? is leakproof but A's rows match it, so they reach the policy"),
                row("03", "@Query(nativeQuery) list", CAUGHT, CAUGHT, LEAK,
                        t -> () -> views(t.invoices.nativeAll()), ""),
                row("04", "native SUM aggregate", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.nativeSum(), "no foreign row in the response, still a read"),
                row("05", "JdbcTemplate.queryForList", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.jdbcList(), ""),
                row("06", "JdbcTemplate.queryForObject COUNT(*)", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.jdbcCount(), "count moves 2 -> 4"),
                row("07", "JPA Criteria API, no tenant predicate", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.criteriaAll(), ""),
                row("08", "Spring Data Specification, no tenant predicate", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.specificationOpen(), ""),
                row("09", "interface projection without tenant_id", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.allNames().stream().map(NameOnly::getName).toList(),
                        "the policy reads tenant_id itself; the select list does not matter"),
                row("10", "findById(victim id), no tenant check", CAUGHT, SILENT, LEAK,
                        t -> () -> String.valueOf(t.invoices.findById(VICTIM_ID).map(InvoiceView::of)),
                        "one row of one tenant: nothing for the passive rule"),
                row("11", "findById then in-memory tenant check (404)", CAUGHT, SILENT, SUSPECT,
                        t -> () -> t.queries.loadThenCheck(VICTIM_ID, ATTACKER),
                        "404 before and after A exists: the read is real, the response does not move"),
                row("12", "Pageable page 0 size 1, amount desc", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.largestFirstPage(),
                        "A's extreme amount takes the only slot; the count query moves totalElements too"),
                row("13", "native ORDER BY amount DESC LIMIT 1", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.nativeTopByAmount(), "the sort reads every row before LIMIT"),
                row("14", "EXISTS with lower(name) = ? (not leakproof)", CAUGHT, CAUGHT, SUSPECT,
                        t -> () -> t.invoices.nameTakenIgnoringCase(FREE_NAME),
                        "false before and after; lower() runs after the policy, so every row is observed"),
                row("15", "EXISTS with name = ? (leakproof), name nobody has", SILENT, SILENT, CLEAN,
                        t -> () -> t.invoices.nameTaken(FREE_NAME),
                        "BLIND: texteq runs before the policy and rejects every row; the answer does not move"),
                row("15b", "EXISTS with name = ? (leakproof), victim's name", CAUGHT, SILENT, LEAK,
                        t -> () -> t.invoices.nameTaken(VICTIM_NAME),
                        "the existence oracle: A's matching row passes texteq and reaches the policy"),
                row("16a", "MAX(amount)", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.maxAmount(), "Extremes seed: A's high value wins"),
                row("16b", "MIN(amount)", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.minAmount(), "Extremes seed: A's low value wins"),
                row("17", "child rows filtered through JOIN on parent tenant (correct)", CAUGHT, CAUGHT, SUSPECT,
                        t -> () -> t.lines.descriptionsOfTenant(ATTACKER),
                        "FALSE POSITIVE in both rules: corpus_line is scanned whole, the join discards A's lines"),
                row("18", "@Cacheable over a leaky SUM", SILENT, SILENT, CLEAN,
                        t -> () -> t.cached.cachedTotal(),
                        "BLIND: the baseline warmed the cache, later calls never reach the database"),
                new Pattern("18b", "@Cacheable over a leaky SUM, cache cleared after seeding", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.cached.cachedTotal(), CorpusTestBase::clearCaches,
                        "workaround: CacheManager clear after seeding the victim", 0),
                row("19", "@Transactional(readOnly = true) read", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.readOnlyAll(), "NOTICE survives READ ONLY"),
                row("20", "@Async read on a task-executor thread", CAUGHT, CAUGHT, LEAK,
                        t -> () -> asyncNames(t), "the actor is global, so the pool thread is labelled B"),
                row("21", "batch IN (:ids) with the victim's ids", CAUGHT, SILENT, LEAK,
                        t -> () -> views(t.invoices.byIds(VICTIM_IDS)),
                        "only A's rows are read: one tenant per statement, passive rule silent"),
                row("22", "CTE (WITH ... SELECT) native", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.nativeCteSummary(), ""),
                row("23", "view without filter (default: owner's rights)", SILENT, SILENT, LEAK,
                        t -> () -> t.invoices.viewSum(),
                        "OBSERVER BLIND: base-table RLS is checked as the view owner, the table owner, who bypasses it"),
                row("23b", "view WITH (security_invoker = true)", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.invoices.invokerViewSum(), "RLS checked as tg_app").since(150000),
                row("24", "SQL function, SECURITY INVOKER", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.functionTotal(), "body runs as tg_app; NOTICE reaches the client"),
                row("24b", "SQL function, SECURITY DEFINER", SILENT, SILENT, LEAK,
                        t -> () -> t.queries.definerFunctionTotal(),
                        "OBSERVER BLIND: body runs as the owner, who bypasses RLS"),
                row("25", "Hibernate StatelessSession", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.statelessAll(), "own connection, still from the guarded DataSource"),
                row("25b", "Hibernate native query getResultStream()", CAUGHT, CAUGHT, LEAK,
                        t -> () -> t.queries.nativeStreamNames(), ""),
                row("26", "control: findByTenantId(B)", SILENT, SILENT, CLEAN,
                        t -> () -> views(t.invoices.findByTenantIdOrderById(ATTACKER)),
                        "tenant_id = ? is leakproof: A's rows never reach the policy"),
                row("27", "control: findByIdAndTenantId(victim id, B)", SILENT, SILENT, CLEAN,
                        t -> () -> String.valueOf(t.invoices.findByIdAndTenantId(VICTIM_ID, ATTACKER)
                                .map(InvoiceView::of)),
                        "both predicates leakproof: A's row is rejected before the policy"));
    }

    @Autowired
    CorpusInvoiceRepository invoices;

    @Autowired
    CorpusLineRepository lines;

    @Autowired
    CorpusQueries queries;

    @Autowired
    CorpusCachedReports cached;

    @Autowired
    CorpusAsyncReports async;

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("Leak corpus: pattern → layers")
    void pattern(Pattern pattern) {
        int server = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
        assumeTrue(server >= pattern.minServerVersion(), () -> "%s (%s) needs server_version_num >= %d, this server is %d"
                .formatted(pattern.id(), pattern.name(), pattern.minServerVersion(), server));
        Supplier<Object> call = pattern.call().apply(this);

        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, ATTACKER,
                List.of(new Endpoint(pattern.name(), call)));
        seedVictim();
        pattern.beforeCall().accept(this);
        Result diff = probe.compare().get(0);

        pattern.beforeCall().accept(this);
        guard.reset();
        guard.runAs(ATTACKER, call::get);
        List<Finding> mismatches = guard.findings(Rule.ACTOR_MISMATCH);
        List<Finding> multiTenant = guard.findings(Rule.MULTI_TENANT_STATEMENT);
        report(pattern, diff, mismatches, multiTenant);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(layer(mismatches)).as("observer (ACTOR_MISMATCH)").isEqualTo(pattern.observer());
        softly.assertThat(mismatches).as("observer only ever blames the victim")
                .allSatisfy(f -> softly.assertThat(f.tenants()).isEqualTo(Set.of(VICTIM)));
        softly.assertThat(layer(multiTenant)).as("passive (MULTI_TENANT_STATEMENT)").isEqualTo(pattern.passive());
        softly.assertThat(diff.verdict()).as("differential").isEqualTo(pattern.diff());
        softly.assertAll();
    }

    private static Layer layer(List<Finding> findings) {
        return findings.isEmpty() ? SILENT : CAUGHT;
    }

    private static List<InvoiceView> views(List<CorpusInvoice> invoices) {
        return invoices.stream().map(InvoiceView::of).toList();
    }

    private static Object asyncNames(LeakCorpusTest test) {
        CorpusAsyncReports.AsyncNames result = test.async.allNames(Thread.currentThread()).join();
        if (!result.offThread()) {
            throw new IllegalStateException("@Async did not switch threads: the pattern would not test a thread pool");
        }
        return result.names();
    }

    private static void report(Pattern pattern, Result diff, List<Finding> mismatches, List<Finding> multiTenant) {
        System.out.printf("[corpus] %-4s | %-62s | observer %-6s | passive %-6s | diff %-7s | %s | changed=%s observerTables=%s%n",
                pattern.id(), pattern.name(), layer(mismatches), layer(multiTenant), diff.verdict(), pattern.note(),
                diff.changedPaths(), mismatches.stream().map(Finding::table).distinct().sorted().toList());
    }
}
