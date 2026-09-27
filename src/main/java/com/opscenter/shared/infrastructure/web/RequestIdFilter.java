package com.opscenter.shared.infrastructure.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request correlation (04-API §2.2, 05-DEPLOY §16, D-17).
 * <p>
 * Accepts a client supplied {@code X-Request-Id} (sanitised: at most 100 chars of
 * {@code [A-Za-z0-9._-]}) or generates a UUID, then
 * <ol>
 *   <li>stores it in the MDC so every log line of this request prints it,</li>
 *   <li>writes it to the response header <em>before</em> the chain continues, so even a 401 from
 *       the security filters or a 500 carries it,</li>
 *   <li>removes it from the MDC in {@code finally} because servlet threads are pooled.</li>
 * </ol>
 * Registered with {@code HIGHEST_PRECEDENCE} so it runs before Spring Security's filter chain
 * (Boot orders filter beans by {@code @Order}; security sits at {@code -100}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String header) {
        if (header != null && VALID.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    /** The current request id, or {@code null} outside a request (for error bodies and audit). */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
