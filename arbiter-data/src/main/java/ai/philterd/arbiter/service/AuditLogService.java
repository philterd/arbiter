package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogService(final AuditLogRepository auditLogRepository, final UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    public void log(final String action, final String resourceType, final String resourceId, final Map<String, Object> details) {
        record(action, resourceType, resourceId, OUTCOME_SUCCESS, currentUserEmail(), details);
    }

    public void log(final String action, final String resourceType, final String resourceId) {
        log(action, resourceType, resourceId, null);
    }

    public void logForUser(final String userEmail, final String action, final String resourceType, final String resourceId,
                           final String outcome, final Map<String, Object> details) {
        record(action, resourceType, resourceId, outcome, userEmail, details);
    }

    private void record(final String action, final String resourceType, final String resourceId, final String outcome,
                        final String userEmail, final Map<String, Object> details) {
        try {
            final AuditLog entry = new AuditLog();
            entry.setId(UUID.randomUUID().toString());
            entry.setTimestamp(Instant.now());
            entry.setAction(action);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setOutcome(outcome == null ? OUTCOME_SUCCESS : outcome);
            entry.setUserEmail(userEmail);
            if (userEmail != null && !userEmail.isBlank()) {
                final User user = userRepository.findByEmail(userEmail).orElse(null);
                if (user != null) entry.setUserId(user.getId());
            }
            entry.setIpAddress(currentRequestIp());
            if (details != null && !details.isEmpty()) {
                entry.setDetails(new LinkedHashMap<>(details));
            }
            auditLogRepository.save(entry);
        } catch (RuntimeException e) {
            log.warn("Failed to write audit log entry for {} {}/{}: {}",
                    action, resourceType, resourceId, e.getMessage());
        }
    }

    private static String currentUserEmail() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        final String name = auth.getName();
        if (name == null || "anonymousUser".equals(name)) return null;
        return name;
    }

    private static String currentRequestIp() {
        try {
            final ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            final HttpServletRequest req = attrs.getRequest();
            final String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                final int comma = forwarded.indexOf(',');
                return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            return req.getRemoteAddr();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
