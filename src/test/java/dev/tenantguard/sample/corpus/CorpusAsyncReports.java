package dev.tenantguard.sample.corpus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** A leaky read on a task-executor thread: synchronous unless the context enables {@code @Async}. */
@Service
public class CorpusAsyncReports {

    /** {@code offThread} tells the caller whether the read really ran on another thread. */
    public record AsyncNames(List<String> names, boolean offThread) {
    }

    private final CorpusInvoiceRepository invoices;

    public CorpusAsyncReports(CorpusInvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Async
    public CompletableFuture<AsyncNames> allNames(Thread caller) {
        List<String> names = invoices.jpqlAll().stream().map(CorpusInvoice::getName).toList();
        return CompletableFuture.completedFuture(new AsyncNames(names, Thread.currentThread() != caller));
    }
}
