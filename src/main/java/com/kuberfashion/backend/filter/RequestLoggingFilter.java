package com.kuberfashion.backend.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Enumeration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip verbose logging for automated cloud health probes
        String uri = httpRequest.getRequestURI();
        if ("/".equals(uri) || "/healthz".equals(uri) || "/actuator/health".equals(uri) || "/api/health".equals(uri)) {
            chain.doFilter(request, response);
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Log incoming request
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("🔵 INCOMING REQUEST");
        logger.info("Method: {} | URL: {}", httpRequest.getMethod(), httpRequest.getRequestURL());
        logger.info("Query String: {}", httpRequest.getQueryString());
        logger.info("Client IP: {}", httpRequest.getRemoteAddr());
        
        // Log headers
        logger.info("📋 Headers:");
        Enumeration<String> headerNames = httpRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = httpRequest.getHeader(headerName);
            
            // Mask sensitive headers
            if (headerName.equalsIgnoreCase("Authorization")) {
                if (headerValue != null && headerValue.startsWith("Bearer ")) {
                    String token = headerValue.substring(7);
                    headerValue = "Bearer " + token.substring(0, Math.min(20, token.length())) + "...";
                }
            }
            
            logger.info("  {} = {}", headerName, headerValue);
        }
        
        // Continue the filter chain
        try {
            chain.doFilter(request, response);
        } finally {
            // Log response
            long duration = System.currentTimeMillis() - startTime;
            int status = httpResponse.getStatus();
            String statusEmoji = status < 300 ? "✅" : status < 400 ? "⚠️" : "❌";
            
            logger.info("───────────────────────────────────────────────────────────────");
            logger.info("{} RESPONSE", statusEmoji);
            logger.info("Status: {} | Duration: {}ms", status, duration);
            logger.info("═══════════════════════════════════════════════════════════════");
        }
    }
}
