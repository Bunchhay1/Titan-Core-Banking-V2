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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * Hardened Audit Log Aspect.
 * Optimizes the extraction of Security and Servlet contexts, ensuring compatibility
 * with Java 21 Virtual Threads where standard ThreadLocals may be aggressively recycled.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    @Around("@annotation(auditLog)")
    public Object logAudit(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        String username = "SYSTEM";
        String ipAddress = "127.0.0.1";
        String status = "SUCCESS";

        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                username = authentication.getName();
            }

            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                ipAddress = extractClientIp(request);
            }
        } catch (Exception e) {
            log.trace("Could not resolve context for audit log: {}", e.getMessage());
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            status = "FAILURE";
            throw e;
        } finally {
            saveLogAsync(username, auditLog.action(), ipAddress, status);
        }

        return result;
    }

    /**
     * Executes the actual database write on a separate virtual thread
     * to prevent blocking the primary request response cycle.
     */
    private void saveLogAsync(String username, String action, String ip, String status) {
        Thread.ofVirtual().name("audit-log-", 0).start(() -> {
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
                log.info("  AUDIT: User [{}] performed [{}] -> Status: [{}]", username, action, status);
            } catch (Exception e) {
                log.error("Failed to persist audit log: {}", e.getMessage());
            }
        });
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || !xfHeader.contains(request.getRemoteAddr())) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}