package dev.tenantguard.scan;

import java.util.Objects;

/**
 * Finds an id owned by the victim tenant for a path variable, so the attacker can be sent to
 * {@code /invoices/<A's id>}. Called by the scanner after the victim is seeded, with the guard
 * inactive, so lookups run as the owner and are not observed.
 */
@FunctionalInterface
public interface ResourceIds {

    Resolution resolve(String pathTemplate, String variable, String tenant);

    /** Per-template overrides in front of an optional fallback such as {@link TableGuessingResourceIds}. */
    static ResourceIdOverrides.Builder builder() {
        return new ResourceIdOverrides.Builder();
    }

    /** Either an id or the reason there is none; the reason ends up in the report as SKIPPED. */
    record Resolution(String id, String reason) {

        public Resolution {
            if ((id == null) == (reason == null)) {
                throw new IllegalArgumentException("Exactly one of id and reason must be set");
            }
        }

        public static Resolution of(Object id) {
            return new Resolution(String.valueOf(Objects.requireNonNull(id, "id")), null);
        }

        public static Resolution unresolved(String reason) {
            return new Resolution(null, Objects.requireNonNull(reason, "reason"));
        }

        public boolean isResolved() {
            return id != null;
        }
    }
}
