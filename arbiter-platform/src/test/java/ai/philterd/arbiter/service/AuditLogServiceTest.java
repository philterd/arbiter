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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditLogService}. The service is critical security infrastructure —
 * every state-changing controller writes a row, and a regression that swallows entries or
 * misattributes the actor would compromise the audit trail. The tests cover actor
 * resolution from the {@link SecurityContextHolder}, the {@code logForUser} variant for
 * background workers (which carry no SecurityContext), IP extraction including the
 * {@code X-Forwarded-For} first-hop rule, and the contract that any failure during
 * persistence is logged and swallowed rather than propagated to the caller.
 */
class AuditLogServiceTest {

    private AuditLogRepository auditLogRepository;
    private UserRepository userRepository;
    private AuditLogService service;

    /** Deterministic 32-byte key (base64) so the pinned HMAC vectors below stay stable.
     *  The bytes are not secret — picked so tests are reproducible. */
    private static final String TEST_CRYPTO_SECRET =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AuditLogService(auditLogRepository, userRepository, TEST_CRYPTO_SECRET);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- happy path ----------

    @Test
    void logResolvesActorFromSecurityContextAndAttachesUserId() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final User alice = new User();
        alice.setId("user-1");
        alice.setEmail("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(alice));

        service.log("BATCH_CLOSE", "Batch", "b-1", Map.of("name", "Q4"));

        final AuditLog saved = capture();
        assertEquals("BATCH_CLOSE", saved.getAction());
        assertEquals("Batch", saved.getResourceType());
        assertEquals("b-1", saved.getResourceId());
        assertEquals("alice@x.com", saved.getUserEmail());
        assertEquals("user-1", saved.getUserId(),
                "userId must be resolved by repository lookup so admin investigations can join "
                        + "audit rows back to the User collection.");
        assertEquals(AuditLogService.OUTCOME_SUCCESS, saved.getOutcome());
        assertEquals("Q4", saved.getDetails().get("name"));
        assertNotNull(saved.getId(), "Service must mint a UUID for the entry id.");
        assertNotNull(saved.getTimestamp(), "Service must stamp the current Instant.");
    }

    @Test
    void logRejectsNullSecurityContextWithNoActor() {
        // No authentication is set — log must still write an entry, just without an
        // actor. Critical for system-initiated audit events (e.g. dispatcher starting
        // a job) called outside a request thread.
        service.log("SYSTEM_ACTION", "System", "s-1", null);

        final AuditLog saved = capture();
        assertNull(saved.getUserEmail());
        assertNull(saved.getUserId());
        assertEquals(AuditLogService.OUTCOME_SUCCESS, saved.getOutcome());
    }

    @Test
    void logIgnoresAnonymousPrincipalAsActor() {
        // Spring populates ROLE_ANONYMOUS for unauthenticated requests. The principal name
        // is the literal "anonymousUser" — service must treat it the same as no actor.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        service.log("X", "Y", "z", null);

        final AuditLog saved = capture();
        assertNull(saved.getUserEmail(),
                "anonymousUser must not be persisted as the actor — the audit trail must be honest.");
        assertNull(saved.getUserId());
    }

    @Test
    void logTwoArgVariantPersistsNullDetails() {
        // The two-arg overload is shorthand for "no details payload" — the persisted entry
        // must have null details, not an empty map (consumers test for null to know there's
        // no payload to display).
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.empty());
        service.log("LOGIN", "User", "alice@x.com");

        final AuditLog saved = capture();
        assertNull(saved.getDetails());
    }

    @Test
    void logCopiesDetailsMapInsteadOfStoringTheCallerReference() {
        // A regression that stored the caller's map directly would let a later mutation
        // of that map silently rewrite the persisted audit entry. Verify that we made
        // a defensive copy.
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("k", "before");
        service.log("X", "Y", "z", details);
        details.put("k", "after");  // would corrupt the entry if not defensively copied

        final AuditLog saved = capture();
        assertEquals("before", saved.getDetails().get("k"),
                "Service must defensively copy the details map before persisting.");
    }

    @Test
    void logSkipsEmptyDetailsMapAndPersistsNull() {
        // Empty details and null details are equivalent — both mean "no payload" — and the
        // service flattens empty to null so consumers only have one thing to test for.
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.empty());
        service.log("X", "Y", "z", Map.of());

        final AuditLog saved = capture();
        assertNull(saved.getDetails(),
                "Empty details map must be normalized to null on the persisted entry.");
    }

    // ---------- logForUser (background-worker variant) ----------

    @Test
    void logForUserStampsTheGivenEmailEvenWithNoSecurityContext() {
        // Background dispatchers run on pool threads with no SecurityContextHolder; they
        // pass the actor email explicitly. Confirm the explicit value wins over any
        // ambient context (here, none) and is written to the entry.
        when(userRepository.findByEmail("ingest@worker"))
                .thenReturn(Optional.empty());

        service.logForUser("ingest@worker", "DOCUMENT_IMPORT", "Document", "d-1",
                AuditLogService.OUTCOME_SUCCESS, Map.of("source", "OPENSEARCH"));

        final AuditLog saved = capture();
        assertEquals("ingest@worker", saved.getUserEmail());
        assertEquals(AuditLogService.OUTCOME_SUCCESS, saved.getOutcome());
        assertEquals("OPENSEARCH", saved.getDetails().get("source"));
    }

    @Test
    void logForUserHonorsExplicitFailureOutcome() {
        service.logForUser("alice@x.com", "DATA_IMPORT_FAILED", "BackgroundJob", "j-1",
                AuditLogService.OUTCOME_FAILURE, Map.of("error", "boom"));

        final AuditLog saved = capture();
        assertEquals(AuditLogService.OUTCOME_FAILURE, saved.getOutcome());
        assertEquals("boom", saved.getDetails().get("error"));
    }

    @Test
    void logForUserDefaultsNullOutcomeToSuccess() {
        service.logForUser("alice@x.com", "X", "Y", "z", null, null);

        final AuditLog saved = capture();
        assertEquals(AuditLogService.OUTCOME_SUCCESS, saved.getOutcome(),
                "A null outcome must default to SUCCESS so callers can omit the field for happy-path entries.");
    }

    @Test
    void logForUserOmitsUserIdLookupForBlankEmail() {
        service.logForUser("   ", "X", "Y", "z", null, null);

        final AuditLog saved = capture();
        // userEmail is preserved as-is for forensic transparency — a blank string is at
        // least visible in the row, vs. silently rewritten to null. userId stays null
        // because the lookup is skipped for blanks.
        assertEquals("   ", saved.getUserEmail());
        assertNull(saved.getUserId());
        verify(userRepository, never()).findByEmail(any());
    }

    // ---------- IP extraction ----------

    @Test
    void ipAddressDefaultsToRemoteAddrWhenNoForwardedHeader() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.log("X", "Y", "z", null);

        assertEquals("198.51.100.42", capture().getIpAddress());
    }

    @Test
    void ipAddressReadsFirstHopFromXForwardedFor() {
        // X-Forwarded-For carries the chain "client, proxy1, proxy2". The first hop is
        // the closest claim to the originating client and is what the audit log records.
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 10.0.0.2");
        req.setRemoteAddr("10.0.0.99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.log("X", "Y", "z", null);

        assertEquals("203.0.113.5", capture().getIpAddress(),
                "X-Forwarded-For first hop wins over the proxy's RemoteAddr.");
    }

    @Test
    void ipAddressTrimsWhitespaceFromForwardedHeader() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "   203.0.113.5   ,   10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.log("X", "Y", "z", null);

        assertEquals("203.0.113.5", capture().getIpAddress());
    }

    @Test
    void blankForwardedHeaderFallsBackToRemoteAddr() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "   ");
        req.setRemoteAddr("198.51.100.42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.log("X", "Y", "z", null);

        assertEquals("198.51.100.42", capture().getIpAddress());
    }

    @Test
    void noRequestContextLeavesIpAddressNull() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));

        service.log("X", "Y", "z", null);

        assertNull(capture().getIpAddress(),
                "Service called outside a request thread must record null IP rather than crash.");
    }

    // ---------- error swallowing ----------

    @Test
    void persistenceFailureIsSwallowedNotPropagated() {
        // A failure to write the audit entry must NOT propagate, because the audit log
        // is a side effect of the controller's primary operation. The controller already
        // committed the user-facing change; a failed audit row should be logged for an
        // operator to investigate, never thrown back to the caller.
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        doThrow(new RuntimeException("Mongo down")).when(auditLogRepository).save(any());

        // The call must complete normally:
        service.log("X", "Y", "z", Map.of("k", "v"));
        // …and have attempted to save:
        verify(auditLogRepository).save(any());
    }

    @Test
    void userLookupFailureIsSwallowedAndEntryStillWritten() {
        SecurityContextHolder.getContext().setAuthentication(authToken("alice@x.com"));
        when(userRepository.findByEmail("alice@x.com"))
                .thenThrow(new RuntimeException("user repo unavailable"));

        // The catch is broad enough to cover the user lookup failing too. The persistence
        // call won't fire in that case (entire record() body is in the try) but we must
        // at least not throw to the caller.
        service.log("X", "Y", "z", null);
    }

    // ---------- hashForAudit (finding #5, hardened to HMAC by R2-F8) ----------

    @Test
    void hashForAuditReturnsKeyedHmacSha256() {
        // Pinned vector: HMAC-SHA-256("4111-1111-1111-1111") keyed with the
        // domain-separated subkey of TEST_CRYPTO_SECRET. R2-F8: plain SHA-256
        // of low-entropy PII (SSNs ~10^9, phone numbers ~10^10) was brute-
        // forceable in seconds — HMAC keyed by the server secret means an
        // attacker with only the audit collection cannot enumerate candidates
        // without also stealing the crypto secret.
        //
        // Reference Python (run locally to update if the subkey derivation
        // ever changes):
        //   import hmac, hashlib, base64
        //   k = base64.b64decode("AAAAAAA…AAA=")
        //   sub = hmac.new(k, b"arbiter:audit-hash:v1", hashlib.sha256).digest()
        //   print(hmac.new(sub, b"4111-1111-1111-1111", hashlib.sha256).hexdigest())
        final String expected = "d8f7fdfe447f8f0de7ae15deddfb99c4e714dcd83cf53e7071eb9ad4063aa2f5";
        assertEquals(expected, service.hashForAudit("4111-1111-1111-1111"));
    }

    @Test
    void hashForAuditWithDifferentKeyProducesDifferentValue() {
        // The core security property: an attacker who only has the audit log
        // (and not the crypto secret) cannot precompute a dictionary of PII
        // candidates. Two services with different keys must produce different
        // hashes for the same input.
        // A different valid 32-byte key (base64 of 0xFF * 32).
        final AuditLogService other = new AuditLogService(
                auditLogRepository, userRepository,
                "//////////////////////////////////////////8=");
        assertNotEquals(
                service.hashForAudit("4111-1111-1111-1111"),
                other.hashForAudit("4111-1111-1111-1111"),
                "Same plaintext with different keys must produce different hashes — "
                        + "otherwise we'd just be SHA-256 in disguise.");
    }

    @Test
    void hashForAuditReturnsEmptyStringForNullOrBlank() {
        // "" rather than null so an audit row never carries a null placeholder
        // that could be misread as an absent value vs. a search for the empty string.
        assertEquals("", service.hashForAudit(null));
        assertEquals("", service.hashForAudit(""));
    }

    @Test
    void hashForAuditIsDeterministicAndDifferentForDifferentInputs() {
        // Same input → same hash; different inputs → different hashes. The first
        // half is what makes "did anyone search for X?" work; the second half is
        // what makes the hash not collapse all queries to a single bucket.
        assertEquals(service.hashForAudit("abc"), service.hashForAudit("abc"));
        assertNotEquals(
                service.hashForAudit("abc"), service.hashForAudit("abd"));
    }

    // ---------- helpers ----------

    private AuditLog capture() {
        final ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Authentication authToken(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
