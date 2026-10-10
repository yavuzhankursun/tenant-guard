/**
 * Leak corpus: one small, deliberately leaky (or deliberately correct) data access per pattern,
 * over the {@code corpus_*} tables of {@code db/corpus/V2__corpus.sql}. Leaky methods read or
 * aggregate across tenants where they should filter by the caller's tenant. Driven by
 * {@code dev.tenantguard.corpus.LeakCorpusTest}; caching and {@code @Async} only take effect where
 * that test's configuration enables them.
 */
package dev.tenantguard.sample.corpus;
