package dev.tenantguard.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * One GET through MockMvc. Never throws: MockMvc rethrows handler exceptions instead of rendering a
 * 500, and one crashing endpoint must not abort the scan (or the differential probe mid-baseline).
 */
final class HttpCaller {

    /** {@code body} is Map/List/scalar for JSON, the raw string otherwise; {@code failure} is set for exceptions. */
    record Response(int status, Object body, String failure) {

        static final int SYNTHETIC_SERVER_ERROR = 500;

        boolean isError() {
            return failure != null || status >= 500;
        }

        String describeError() {
            return failure != null ? "exception: " + failure : "HTTP " + status;
        }
    }

    private final MockMvc mockMvc;
    private final TenantAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    HttpCaller(MockMvc mockMvc, TenantAuthenticator authenticator, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    /** {@code path} must already be encoded; the URI overload stops MockMvc from encoding it twice. */
    Response get(String path, String tenant) {
        try {
            MockHttpServletResponse response = mockMvc
                    .perform(MockMvcRequestBuilders.get(URI.create(path)).with(authenticator.forTenant(tenant)))
                    .andReturn()
                    .getResponse();
            return new Response(response.getStatus(), parse(response.getContentAsString(StandardCharsets.UTF_8)), null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Response(Response.SYNTHETIC_SERVER_ERROR, null, rootCause(e));
        }
    }

    private Object parse(String body) {
        if (body.isBlank()) {
            return body;
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (JsonProcessingException notJson) {
            return body;
        }
    }

    private static String rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
