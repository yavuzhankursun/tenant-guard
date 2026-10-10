package dev.tenantguard.corpus;

import dev.tenantguard.Extremes;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.sample.SampleApp;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Corpus fixture. Every test starts with the attacker's (B) data only; {@link #seedVictim()} adds
 * the victim (A) mid-test, as the owner, with {@link Extremes} amounts, so the differential probe
 * can take a B-only baseline first.
 *
 * <p>Own container and an extra Flyway location ({@code db/corpus}): the corpus tables carry
 * {@code tenant_id}, so in the shared container they would change what auto-discovery finds for
 * every other test. Ids are explicit, so the victim's ids are known before its rows exist and
 * id-based patterns can be called with them in the baseline too.
 */
@SpringBootTest(classes = {SampleApp.class, CorpusTestBase.CorpusFeatures.class})
@TenantIsolationTest(tenantColumn = "tenant_id", tables = {"corpus_invoice", "corpus_line"})
abstract class CorpusTestBase {

    static final String VICTIM = "A";
    static final String ATTACKER = "B";
    static final List<Long> VICTIM_IDS = List.of(101L, 102L);
    static final long VICTIM_ID = VICTIM_IDS.get(0);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/corpus");
    }

    /** Switches on the corpus services' {@code @Cacheable} and {@code @Async}; nowhere else. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableCaching
    @EnableAsync
    static class CorpusFeatures {
    }

    @Autowired
    TenantGuard guard;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CacheManager cacheManager;

    /** The extension armed the guard already: seed as the owner, then re-arm and forget the seeding. */
    @BeforeEach
    void seedAttackerOnly() {
        guard.deactivate();
        clearCaches();
        jdbc.execute("TRUNCATE corpus_line, corpus_invoice RESTART IDENTITY");
        seed(ATTACKER, List.of(1L, 2L), List.of(new BigDecimal("100.00"), new BigDecimal("200.00")));
        guard.activate();
        guard.reset();
    }

    void seedVictim() {
        boolean wasActive = guard.isActive();
        guard.deactivate();
        try {
            seed(VICTIM, VICTIM_IDS, Extremes.amounts());
        } finally {
            if (wasActive) {
                guard.activate();
            }
        }
    }

    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    /** One invoice per id, each with two lines (line ids {@code 2 * id - 1} and {@code 2 * id}). */
    private void seed(String tenant, List<Long> ids, List<BigDecimal> amounts) {
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            jdbc.update("INSERT INTO corpus_invoice (id, tenant_id, name, status, amount) VALUES (?, ?, ?, 'open', ?)",
                    id, tenant, tenant + "-" + (i + 1), amounts.get(i));
            jdbc.update("INSERT INTO corpus_line (id, tenant_id, invoice_id, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
                    2 * id - 1, tenant, id, tenant + " line 1", 2 * id, tenant, id, tenant + " line 2");
        }
    }
}
