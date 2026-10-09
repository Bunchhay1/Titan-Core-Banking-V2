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
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Optional;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    // [MODIFIED] Enforced final keyword on aspect parameters for immutability.
    @Around("@annotation(auditLog)")
    public Object logAudit(final ProceedingJoinPoint joinPoint, final AuditLog auditLog) throws Throwable {

        // [MODIFIED] Extracted context resolution into isolated, null-safe helper methods.
        final String username = resolveUsername();
        final String ipAddress = resolveClientIp();
        String status = "SUCCESS";

        try {
            return joinPoint.proceed();
        } catch (final Throwable e) {
            status = "FAILURE";
            throw e;
        } finally {
            final String finalStatus = status; // Effectively final for lambda closure

            // [MODIFIED] Offloaded DB write to a Virtual Thread to eliminate blocking I/O latency.
            // ការសរសេរទិន្នន័យ (Save) ទៅកាន់ Database ត្រូវបានប្តូរទៅប្រើ Virtual Thread។ នេះមានន័យថា ដំណើរការកាត់លុយរបស់ User នឹងមិនចាំបាច់រង់ចាំការកត់ត្រា Log នេះឲ្យចប់នោះទេ ដែលជួយកាត់បន្ថយភាពយឺតយ៉ាវ (Latency) យ៉ាងមានប្រសិទ្ធភាព។
            Thread.ofVirtual().name("audit-logger-", 0).start(() ->
                    persistAuditLogSafe(username, auditLog.action(), ipAddress, finalStatus)
            );
        }
    }

    private String resolveUsername() {
        // [MODIFIED] Leveraged Optional chaining to cleanly extract the authentication principal.
        // ប្រើប្រាស់ Optional ដើម្បីចាប់យកឈ្មោះអ្នកប្រើប្រាស់ ដោយសុវត្ថិភាពនិងគ្មានបញ្ហា NullPointerException។
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

                // [MODIFIED] Secured IP resolution behind proxies via X-Forwarded-For.
                // នៅក្នុងប្រព័ន្ធ Enterprise ធំៗ Server តែងតែស្ថិតនៅក្រោយ Load Balancer ឬ WAF។ ការទាញយក IP តាមរយៈ X-Forwarded-For ផ្តល់នូវ IP ពិតប្រាកដរបស់ User មិនមែន IP របស់ Load Balancer ឡើយ។
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (StringUtils.hasText(xForwardedFor)) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            // [MODIFIED] Replaced anti-pattern "catch and ignore" with a trace log.
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
            // [MODIFIED] Ensure auditing failures trigger operational alerts.
            // បើកត់ត្រា Log មិនបាន វាត្រូវតែលោត Error ដើម្បីឲ្យក្រុម DevOps អាចតាមដានដឹង មិនត្រូវឲ្យវាបាត់ស្ងាត់ៗនោះទេ។
            log.error("CRITICAL: Failed to persist audit log for User [{}] Action [{}]", username, action, e);
        }
    }
}