package dev.tenantguard.sample.corpus;

import java.math.BigDecimal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** A leaky report behind a cache: inert unless the context enables caching. */
@Service
public class CorpusCachedReports {

    public static final String CACHE = "corpus-totals";

    private final CorpusInvoiceRepository invoices;

    public CorpusCachedReports(CorpusInvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Cacheable(CACHE)
    public BigDecimal cachedTotal() {
        return invoices.nativeSum();
    }
}
