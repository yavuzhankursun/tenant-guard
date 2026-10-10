package dev.tenantguard.sample.corpus;

import java.math.BigDecimal;

/** Response shape. A record, so its {@code toString} is stable across calls and diffable. */
public record InvoiceView(Long id, String name, BigDecimal amount) {

    public static InvoiceView of(CorpusInvoice invoice) {
        return new InvoiceView(invoice.getId(), invoice.getName(), invoice.getAmount());
    }
}
