package kr.co.page1.knowledge.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.co.page1.knowledge.config.KnowledgeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret gate for the HTTP surface.
 *
 * <p><strong>Off by default.</strong> With {@code knowledge.security.token} unset,
 * every HTTP endpoint - including {@code POST /mcp}, which can write - is
 * UNAUTHENTICATED. That is only acceptable because {@code server.address} is pinned
 * to 127.0.0.1 in application.yml, so nothing off-machine can reach it. Set a token
 * BEFORE changing the bind address or putting this behind a proxy; this file is the
 * only thing between the store and anyone who can route to the port.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Knowledge-Token";

    private final KnowledgeProperties properties;

    public TokenAuthFilter(KnowledgeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.security().enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null) {
            provided = bearer(request.getHeader("Authorization"));
        }
        if (provided == null || !constantTimeEquals(provided, properties.security().token())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = "Bearer ";
        return authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                ? authorization.substring(prefix.length()).trim()
                : null;
    }

    /** Length-independent comparison so a wrong token leaks no timing signal. */
    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0) {
            return false;
        }
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i % b.length];
        }
        return result == 0;
    }
}
