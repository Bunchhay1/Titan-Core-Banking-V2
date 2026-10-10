package com.titan.titancorebanking.aspect;

import com.titan.titancorebanking.annotation.AuditLog;
import com.titan.titancorebanking.enums.AuditAction;
import com.titan.titancorebanking.enums.EmployeeRole;
import com.titan.titancorebanking.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogAspect {
    private final AuditLogRepository auditLogRepository;

    @Around("@annotation(auditLog)")
    public Object logAudit(final ProceedingJoinPoint joinPoint, final AuditLog auditLog) throws Throwable {
        final String username = resolveUsername();
        final String ipAddress = resolveClientIp();
        String status = "SUCCESS";

        try {
            return joinPoint.proceed();
        } catch (final Throwable e) {
            status = "FAILURE";
            throw e;
        } finally {
            final String finalStatus = status;

            // Capture parent thread's MDC context for Distributed Tracing
            final Map<String, String> contextMap = MDC.getCopyOfContextMap();

            Thread.ofVirtual().name("audit-logger-", 0).start(() -> {
                // Propagate MDC to the Virtual Thread
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    persistAuditLogSafe(username, auditLog.action(), ipAddress, finalStatus);
                } finally {
                    // Prevent memory leaks in virtual threads
                    MDC.clear();
                }
            });
        }
    }

    private String resolveUsername() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .orElse("Anonymous");
    }

    private String resolveClientIp() {
        try {
            var attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (StringUtils.hasText(xForwardedFor)) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.trace("Could not resolve client IP from request context", e);
        }
        return "Unknown";
    }

    private void persistAuditLogSafe(final String username, final String action, final String ip, final String status) {
        try {
            var logEntry = com.titan.titancorebanking.model.AuditLog.builder()
                    .username(username)
                    .action(AuditAction.valueOf(action))
                    .employeeRole(EmployeeRole.SYSTEM)
                    .ipAddress(ip)
                    .status(status)
                    .timestamp(LocalDateTime.now())
                    .build();
            auditLogRepository.save(logEntry);
            log.info("AUDIT SECURED: User [{}] Action [{}] Status [{}] IP [{}]", username, action, status, ip);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to persist audit log for User [{}] Action [{}]", username, action, e);
        }
    }
}