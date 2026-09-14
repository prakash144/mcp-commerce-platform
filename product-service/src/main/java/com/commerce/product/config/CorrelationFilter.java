package com.commerce.product.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class CorrelationFilter extends OncePerRequestFilter {

    private static final String CORRELATION_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.startsWith("/actuator")) {
            chain.doFilter(req, res);
            return;
        }

        String correlationId = req.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_KEY, correlationId);
        res.setHeader("X-Correlation-Id", correlationId);

        try {
            chain.doFilter(req, res);
        } finally {
            log.info("evt=http.request method={} path={} status={}", req.getMethod(), path, res.getStatus());
            MDC.remove(CORRELATION_KEY);
        }
    }
}