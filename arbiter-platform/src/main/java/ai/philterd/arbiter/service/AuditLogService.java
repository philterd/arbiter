/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    /** Domain-separation label for the audit-hash subkey, so the same crypto secret
     *  used for AES-256-GCM at-rest encryption (via SymmetricCipher) and for API-key
     *  hashing (via ApiKeyHashingService) doesn't share an HMAC key with audit hashing.
     *  Bumping the {@code v1} suffix is the recipe for rotating audit hashes. */
    private static final String AUDIT_HASH_SUBKEY_LABEL = "arbiter:audit-hash:v1";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    /** Subkey for the keyed audit hash, derived once from the master secret in the
     *  constructor (HMAC-SHA-256 of the label with the master key — single-block
     *  HKDF-Expand). 32 bytes for a full-strength HMAC-SHA-256 key. */
    private final byte[] auditHashKey;

    public AuditLogService(final AuditLogRepository auditLogRepository,
                           final UserRepository userRepository,
                           @Value("${arbiter.crypto.secret}") final String cryptoSecret) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        final byte[] masterKey = CryptoSecretLoader.load(cryptoSecret);
        this.auditHashKey = deriveSubkey(masterKey);
    }

    private static byte[] deriveSubkey(final byte[] masterKey) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(AUDIT_HASH_SUBKEY_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
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

    /**
     * Hex-encoded HMAC-SHA-256 of a user-supplied value, keyed by a subkey of
     * {@code arbiter.crypto.secret}. Used for audit details where the raw input
     * may carry PII (search queries, filenames, etc.) but forensic correlation
     * — "did anyone search for X?" — still needs to work. The investigator,
     * holding the server key, can re-hash X and match.
     *
     * <p>HMAC rather than plain SHA-256 (R2-F8): low-entropy PII like SSN, phone
     * numbers, ZIP codes and DOBs is brute-forceable in seconds against a plain
     * digest. Keying with a server-side secret makes the audit values
     * meaningless to an attacker who has compromised only the audit collection —
     * they can't enumerate candidates without also stealing the crypto secret.
     *
     * <p>Returns an empty string for null/empty input so an audit row never
     * carries a "null" placeholder that could be misread as a real value.
     */
    public String hashForAudit(final String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(auditHashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
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
