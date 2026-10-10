package dev.tenantguard.sample;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stand-in for real authentication: trusts {@code X-Tenant}. Acceptable for a scan target only. */
@Component
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = request.getHeader(HEADER);
        if (tenant == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!VALID.matcher(tenant).matches()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        TenantContext.set(tenant);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
