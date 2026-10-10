package dev.tenantguard.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * Produces the JSON body a write endpoint is called with. Without a body a write endpoint usually
 * answers 400 before touching any data, which proves nothing, so a body that cannot be built turns
 * into a SKIPPED row with the reason rather than a call. Per-endpoint bodies are added with
 * {@link EndpointScanner.Builder#body}; this strategy is the fallback behind them.
 */
@FunctionalInterface
public interface RequestBodies {

    /** {@code tenant} is the tenant sending the request (the attacker). */
    Body body(Endpoint endpoint, String tenant);

    /**
     * The default: builds a minimal JSON object from the endpoint's {@code @RequestBody} type with
     * Jackson's own property introspection, so naming strategies and {@code @JsonProperty} are
     * honoured when {@code objectMapper} is the application's mapper.
     */
    static RequestBodies introspecting(ObjectMapper objectMapper) {
        return new IntrospectedBodies(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    /**
     * The JSON to send, or the reason there is none. {@code json == null} without a reason means
     * "send no body". {@code generated} marks bodies the scanner made up: only those have their
     * tenant fields stripped, a user's body is sent exactly as written.
     */
    record Body(String json, String reason, boolean generated) {

        public Body {
            if (json != null && reason != null) {
                throw new IllegalArgumentException("A body has either JSON or a reason, not both");
            }
        }

        public static Body none() {
            return new Body(null, null, false);
        }

        /** A body written by the user; sent unchanged. */
        public static Body of(String json) {
            return new Body(Objects.requireNonNull(json, "json"), null, false);
        }

        public static Body generated(String json) {
            return new Body(Objects.requireNonNull(json, "json"), null, true);
        }

        public static Body unavailable(String reason) {
            return new Body(null, Objects.requireNonNull(reason, "reason"), false);
        }

        public boolean isAvailable() {
            return reason == null;
        }
    }
}
