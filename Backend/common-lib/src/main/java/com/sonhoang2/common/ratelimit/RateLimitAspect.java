package com.sonhoang2.common.ratelimit;

import com.sonhoang2.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimitService rateLimitService;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = rateLimit.keyPrefix() + ":" + resolveKey();
        boolean allowed = rateLimitService.tryConsume(
                key, rateLimit.points(), Duration.ofSeconds(rateLimit.durationSeconds())
        );
        if (!allowed) {
            throw new RateLimitExceededException("Too many requests");
        }
        return pjp.proceed();
    }

    private String resolveKey() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();

        // Try to get userId from JWT token in Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            String userId = extractUserIdFromToken(token);
            if (userId != null) {
                return "user:" + userId;
            }
        }

        // Fallback to IP address
        String ipAddress = getClientIpAddress(request);
        return "ip:" + ipAddress;
    }

    private String extractUserIdFromToken(String token) {
        try {
            // Extract userId from JWT token
            // This is a simplified version - in production, use proper JWT parsing
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                // Parse JSON to extract userId claim
                // For now, return null to fallback to IP
                // In production, use a proper JWT library like jjwt
            }
        } catch (Exception e) {
            // If token parsing fails, fallback to IP
        }
        return null;
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // For multiple proxies, take the first IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }
}
