package com.ai.receptionist.config;

import com.ai.receptionist.dto.TwilioCredentials;
import com.ai.receptionist.service.TenantService;
import com.twilio.security.RequestValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validates that incoming HTTP requests to Twilio webhook endpoints
 * actually originate from Twilio by checking the X-Twilio-Signature header.
 *
 * <p>Supports per-tenant Twilio credentials: extracts the "To" phone number
 * from the request parameters and resolves the tenant's auth token for validation.
 * Falls back to the global auth token if no per-tenant credentials are configured.
 *
 * <p>Endpoints protected: /inbound, /twilio/voice/*, /continue-call
 * <p>Endpoints NOT protected: /audio/play/* (Twilio fetches without signing), /media-stream (WebSocket)
 *
 * <p>Can be disabled for local development via {@code twilio.validate-signatures=false}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TwilioSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TwilioSignatureFilter.class);

    private static final List<String> PROTECTED_PATHS = Arrays.asList(
            "/inbound",
            "/twilio/voice/inbound",
            "/twilio/voice/say",
            "/twilio/voice/continue-call",
            "/twilio/voice/goodbye",
            "/twilio/voice/status",
            "/twilio/voice/outbound-start",
            "/continue-call"
    );

    private final TenantService tenantService;

    @Value("${twilio.validate-signatures:true}")
    private boolean validateSignatures;

    public TwilioSignatureFilter(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

    	String path = request.getRequestURI();

    	// Skip TwiML playback / redirect endpoints
    	if (path.startsWith("/twilio/voice/say") ||
    	    path.startsWith("/twilio/voice/continue-call") ||
    	    path.startsWith("/twilio/voice/goodbye") ||
    	    path.startsWith("/audio/play")) {

    	    filterChain.doFilter(request, response);
    	    return;
    	}

        // Only validate requests to protected Twilio endpoints
        if (!isProtectedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip validation if disabled (local development)
        if (!validateSignatures) {
            log.debug("Twilio signature validation disabled — allowing request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        String signature = request.getHeader("X-Twilio-Signature");
        if (signature == null || signature.isBlank()) {
            log.warn("Rejected request to {} — missing X-Twilio-Signature header", path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing Twilio signature");
            return;
        }

        // Reconstruct the full URL that Twilio used to compute the signature.
        String requestUrl = reconstructRequestUrl(request);

        // Collect POST parameters (Twilio signs the POST body parameters)
        Map<String, String> params = Collections.emptyMap();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            params = request.getParameterMap().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().length > 0 ? e.getValue()[0] : ""
                    ));
        }

        // Resolve the correct auth token for this tenant.
        // The "To" param identifies which Twilio number received the call → which tenant → which auth token.
        String toPhone = params.getOrDefault("To", "");
        TwilioCredentials creds = tenantService.getTwilioCredentialsByPhone(toPhone);

        if (!creds.isValid()) {
            log.warn("No valid Twilio credentials found for To={} — rejecting request to {}", toPhone, path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Twilio credentials");
            return;
        }

        RequestValidator validator = new RequestValidator(creds.authToken());
        boolean valid = validator.validate(requestUrl, params, signature);

        if (!valid) {
            log.warn("Rejected request to {} — invalid Twilio signature. URL used for validation: {}", path, requestUrl);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Twilio signature");
            return;
        }

        log.debug("Twilio signature valid for {}", path);
        filterChain.doFilter(request, response);
    }

    private boolean isProtectedPath(String path) {
        if (path == null) return false;
        // Normalize: remove trailing slash
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        return PROTECTED_PATHS.contains(normalized);
    }

    /**
     * Reconstructs the full request URL as Twilio sees it.
     * Uses X-Forwarded-Proto and X-Forwarded-Host headers when behind
     * a reverse proxy (e.g., Google Cloud Run).
     */
    private String reconstructRequestUrl(HttpServletRequest request) {
        // Cloud Run (and most load balancers) set these headers
        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");

        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }

        String uri = request.getRequestURI();
        String queryString = request.getQueryString();

        StringBuilder url = new StringBuilder();
        url.append(proto).append("://").append(host).append(uri);
        if (queryString != null && !queryString.isBlank()) {
            url.append("?").append(queryString);
        }

        return url.toString();
    }
}
