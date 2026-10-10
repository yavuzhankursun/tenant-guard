package dev.tenantguard.scan;

import dev.tenantguard.Finding;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One endpoint called as the attacker. {@code status} is 0 when no request was sent (SKIPPED).
 * {@code observerFindings} holds only ACTOR_MISMATCH findings raised during the scored call.
 */
public record ScanResult(Endpoint endpoint, String resolvedPath, int status, Verdict verdict,
                         Set<String> changedPaths, List<Finding> observerFindings, String note) {

    public enum Verdict {
        /** 2xx on an id owned by the victim, or the response changed once the victim's data existed. */
        LEAK,
        /** Response unchanged or refused (403/404), but the observer saw the victim's rows being read. */
        SUSPECT,
        CLEAN,
        /** Not verified: no victim id could be resolved (not called), or the attacker got 401. Reason in the note. */
        SKIPPED,
        /** 5xx or exception. Never counted as clean: the endpoint was not verified. */
        ERROR
    }

    public ScanResult {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(verdict, "verdict");
        resolvedPath = resolvedPath == null ? endpoint.pathTemplate() : resolvedPath;
        changedPaths = Collections.unmodifiableSet(new TreeSet<>(changedPaths));
        observerFindings = List.copyOf(observerFindings);
        note = note == null ? "" : note.replaceAll("\\s+", " ").strip();
    }

    public String pathTemplate() {
        return endpoint.pathTemplate();
    }
}
