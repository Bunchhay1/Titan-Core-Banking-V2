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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    @Around("@annotation(auditLog)")
    public Object logAudit(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        String username = "Anonymous";
        String ipAddress = "Unknown";
        String status = "SUCCESS";

        try {
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                username = SecurityContextHolder.getContext().getAuthentication().getName();
            }
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            if (request != null) ipAddress = request.getRemoteAddr();
        } catch (Exception e) {
            // Ignore
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            status = "FAILURE";
            throw e;
        } finally {
            saveLog(username, auditLog.action(), ipAddress, status);
        }
        return result;
    }

    private void saveLog(String username, String action, String ip, String status) {
        var logEntry = com.titan.titancorebanking.model.AuditLog.builder()
                .username(username)
                .action(AuditAction.valueOf(action))
                .employeeRole(EmployeeRole.SYSTEM)
                .ipAddress(ip)
                .status(status)
                .timestamp(LocalDateTime.now())
                .build();

        auditLogRepository.save(logEntry);
        log.info("📼 AUDIT: User [{}] performed [{}] -> Status: [{}]", username, action, status);
    }
}