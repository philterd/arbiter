/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.model.Invitation;
import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.InvitationRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.webapp.security.InvitationService.IssuedInvitation;
import ai.philterd.arbiter.webapp.security.InvitationService.RedemptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvitationService}. Exercises every redemption status, the
 * single-shot guarantee, the per-email replacement on re-issue, and the security
 * properties: token only ever stored as a SHA-256 hash, fresh tokens are unguessable,
 * and the recipient's chosen password is hashed (not stored).
 */
class InvitationServiceTest {

    private InvitationRepository invitationRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-05-08T12:00:00Z"));
    private final Clock fixed = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private InvitationService service;
    private final Map<String, Invitation> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        invitationRepository = mock(InvitationRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");

        // Lightweight in-memory backing for the repository so save/findBy* round-trip.
        store.clear();
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> {
            final Invitation i = inv.getArgument(0);
            store.put(i.getId(), i);
            return i;
        });
        when(invitationRepository.findByTokenHash(anyString())).thenAnswer(inv -> {
            final String hash = inv.getArgument(0);
            return store.values().stream().filter(i -> hash.equals(i.getTokenHash())).findFirst();
        });
        when(invitationRepository.findByEmail(anyString())).thenAnswer(inv -> {
            final String email = inv.getArgument(0);
            return store.values().stream()
                    .filter(i -> email.equalsIgnoreCase(i.getEmail()))
                    .filter(i -> i.getConsumedAt() == null)
                    .findFirst();
        });
        lenient().doAnswer(inv -> { store.remove(inv.getArgument(0)); return null; })
                .when(invitationRepository).deleteById(anyString());

        // Default: no existing user.
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        service = new InvitationService(invitationRepository, userRepository, passwordEncoder, fixed);
    }

    private void advance(final java.time.Duration d) {
        now.set(now.get().plus(d));
    }

    // ---------- issuance ----------

    @Test
    void issueGeneratesUnguessableTokenAndStoresOnlyTheHash() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());

        // Token returned to the caller is opaque, base64url, ≥ 32 random bytes worth.
        assertNotNull(issued.token());
        assertTrue(issued.token().length() >= 40,
                "token should be base64url of ≥32 bytes: " + issued.token());

        // Database row stores the SHA-256 hash, not the token.
        final ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitationRepository, atLeastOnce()).save(captor.capture());
        final Invitation persisted = captor.getValue();
        assertNotEquals(issued.token(), persisted.getTokenHash());
        assertEquals(InvitationService.sha256(issued.token()), persisted.getTokenHash());
        assertNull(persisted.getConsumedAt());
        assertEquals("alice@x.com", persisted.getEmail());
        assertFalse(persisted.isAdmin());
    }

    @Test
    void twoIssuancesProduceDifferentTokens() {
        // Even back-to-back. The first issuance for the same email is replaced.
        final String t1 = service.issue("alice@x.com", false, Set.of()).token();
        final String t2 = service.issue("alice@x.com", false, Set.of()).token();
        assertNotEquals(t1, t2, "fresh issuances must produce distinct tokens");
    }

    @Test
    void reissueForSameEmailDeletesPriorPendingInvitation() {
        final IssuedInvitation first = service.issue("alice@x.com", false, Set.of());
        final IssuedInvitation second = service.issue("alice@x.com", false, Set.of());

        // The old invitation row is gone; only the second one is redeemable.
        verify(invitationRepository, times(1)).deleteById(first.invitation().getId());
        assertEquals(RedemptionStatus.INVALID_TOKEN,
                service.redeem(first.token(), "validPassword12"));
        assertEquals(RedemptionStatus.OK,
                service.redeem(second.token(), "validPassword12"));
    }

    @Test
    void issueRejectsBlankEmail() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.issue("", false, Set.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.issue(null, false, Set.of()));
    }

    @Test
    void issueRejectsEmailWithControlCharacters() {
        // Defence in depth against finding #3: even if a future caller bypasses
        // AdminController.isValidEmail (e.g. a programmatic admin-API flow), the
        // service must self-defend against control characters so the redemption-
        // success log line can't be split by a smuggled \n.
        for (String bad : new String[]{
                "alice@x.com\nforged",
                "alice@x.com\rforged",
                "alice@x.com\tforged",
                "alice@x.com",
                "alice@x.com"}) {
            final IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.issue(bad, false, Set.of()),
                    "expected refusal for control-char email: "
                            + bad.replace("\n", "\\n").replace("\r", "\\r")
                                .replace("\t", "\\t"));
            assertTrue(ex.getMessage().toLowerCase().contains("control"),
                    "error message should name the rule, got: " + ex.getMessage());
        }
    }

    @Test
    void issueLowercasesEmail() {
        final IssuedInvitation issued = service.issue("Alice@X.COM", false, Set.of());
        assertEquals("alice@x.com", issued.invitation().getEmail());
    }

    // ---------- redemption — happy path ----------

    @Test
    void redeemSuccessfullyCreatesUserAndConsumesInvitation() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());

        final RedemptionStatus result = service.redeem(issued.token(), "newPassword123");

        assertEquals(RedemptionStatus.OK, result);
        // User row is created at redemption, not at issuance.
        final ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        final User created = userCaptor.getValue();
        assertEquals("alice@x.com", created.getEmail());
        // Password is hashed, not stored verbatim.
        assertEquals("hashed-password", created.getPasswordHash());
        assertNotNull(created.getId());
        assertNotNull(created.getCreatedAt());
        // Default role is USER when admin=false.
        assertTrue(created.getRoles().contains(Roles.USER));
        assertFalse(created.getRoles().contains(Roles.ADMIN));
        // Invitation marked consumed.
        final Invitation persisted = store.get(issued.invitation().getId());
        assertNotNull(persisted.getConsumedAt());
    }

    @Test
    void redeemAdminInvitationCreatesAdminUser() {
        final IssuedInvitation issued = service.issue("admin@x.com", true, Set.of());

        assertEquals(RedemptionStatus.OK, service.redeem(issued.token(), "newPassword123"));

        final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertTrue(captor.getValue().getRoles().contains(Roles.ADMIN));
        assertFalse(captor.getValue().getRoles().contains(Roles.USER));
    }

    // ---------- redemption — rejection paths ----------

    @Test
    void redeemUnknownTokenReturnsInvalidToken() {
        assertEquals(RedemptionStatus.INVALID_TOKEN,
                service.redeem("not-a-real-token", "newPassword123"));
        assertEquals(RedemptionStatus.INVALID_TOKEN,
                service.redeem(null, "newPassword123"));
        assertEquals(RedemptionStatus.INVALID_TOKEN,
                service.redeem("", "newPassword123"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void redeemSecondTimeReturnsAlreadyRedeemed() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());
        // First redemption marks consumed.
        assertEquals(RedemptionStatus.OK, service.redeem(issued.token(), "newPassword123"));
        // Existing-user check would normally fire on the second attempt; simulate the
        // pre-existing-user scenario being avoided so we exercise the consumedAt branch.
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.empty());

        assertEquals(RedemptionStatus.ALREADY_REDEEMED,
                service.redeem(issued.token(), "newPassword456"));
        // Only one user save across both calls.
        verify(userRepository, times(1)).save(any());
    }

    @Test
    void redeemAfterExpiryReturnsExpired() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());
        advance(InvitationService.DEFAULT_TTL.plusSeconds(1));

        assertEquals(RedemptionStatus.EXPIRED,
                service.redeem(issued.token(), "newPassword123"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void redeemWithWeakPasswordReturnsWeakPassword() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());
        assertEquals(RedemptionStatus.WEAK_PASSWORD, service.redeem(issued.token(), "tooshort"));
        assertEquals(RedemptionStatus.WEAK_PASSWORD, service.redeem(issued.token(), null));
        // Boundary: 11 characters fails, 12 passes.
        assertEquals(RedemptionStatus.WEAK_PASSWORD, service.redeem(issued.token(), "12345678901"));
        assertEquals(RedemptionStatus.OK, service.redeem(issued.token(), "123456789012"));
    }

    @Test
    void redeemWhenEmailAlreadyTakenReturnsEmailAlreadyTaken() {
        final IssuedInvitation issued = service.issue("alice@x.com", false, Set.of());
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(new User()));

        assertEquals(RedemptionStatus.EMAIL_ALREADY_TAKEN,
                service.redeem(issued.token(), "newPassword123"));
        verify(userRepository, never()).save(any());
    }

    // ---------- token storage shape ----------

    @Test
    void hashOfATokenIsConsistent() {
        // Same input → same hash; trivial confirmation that findByToken roundtrips.
        final String h1 = InvitationService.sha256("abc");
        final String h2 = InvitationService.sha256("abc");
        assertEquals(h1, h2);
        assertNotEquals(h1, InvitationService.sha256("abd"));
    }

    // ---------- index annotation on tokenHash (defends the redemption-lookup index) ----------

    @Test
    void tokenHashFieldIsIndexedUniqueAndSparse() throws NoSuchFieldException {
        // Reflection guard against a future refactor accidentally dropping the index.
        // Without it, findByTokenHash falls back to a full collection scan and the
        // timing-channel-vs-collection-size concern (#15) returns.
        final java.lang.reflect.Field field =
                ai.philterd.arbiter.model.Invitation.class.getDeclaredField("tokenHash");
        final org.springframework.data.mongodb.core.index.Indexed indexed =
                field.getAnnotation(org.springframework.data.mongodb.core.index.Indexed.class);
        assertNotNull(indexed, "Invitation.tokenHash must carry @Indexed");
        assertTrue(indexed.unique(), "Invitation.tokenHash @Indexed must be unique");
        assertTrue(indexed.sparse(), "Invitation.tokenHash @Indexed must be sparse");
    }

    // ---------- scheduled cleanup ----------

    @Test
    void pruneOldInvitationsDelegatesToRepositoryWithThirtyDayCutoff() {
        when(invitationRepository.deleteByConsumedAtBeforeOrExpiresAtBefore(any(), any()))
                .thenReturn(0L);
        final Instant before = now.get();

        service.pruneOldInvitations();

        // Cutoff = now - 30 days, passed for both the consumedAt and expiresAt clauses.
        final ArgumentCaptor<Instant> consumedCutoff = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> expiredCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(invitationRepository).deleteByConsumedAtBeforeOrExpiresAtBefore(
                consumedCutoff.capture(), expiredCutoff.capture());
        assertEquals(before.minus(Duration.ofDays(30)), consumedCutoff.getValue());
        assertEquals(before.minus(Duration.ofDays(30)), expiredCutoff.getValue());
        assertEquals(InvitationService.CLEANUP_RETENTION, Duration.ofDays(30),
                "the documented retention contract is 30 days");
    }

    @Test
    void pruneOldInvitationsCutoffShiftsWithTheClock() {
        // Drive the clock forward; the cutoff must track it. Confirms we read the clock
        // at call time rather than capturing it at construction time.
        when(invitationRepository.deleteByConsumedAtBeforeOrExpiresAtBefore(any(), any()))
                .thenReturn(0L);
        advance(Duration.ofDays(10));
        final Instant expected = now.get().minus(Duration.ofDays(30));

        service.pruneOldInvitations();

        final ArgumentCaptor<Instant> consumedCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(invitationRepository).deleteByConsumedAtBeforeOrExpiresAtBefore(
                consumedCutoff.capture(), any());
        assertEquals(expected, consumedCutoff.getValue());
    }

    @Test
    void pruneOldInvitationsIsAnnotatedScheduled() throws NoSuchMethodException {
        // The harness wires @Scheduled at the bean level; this test asserts the method
        // itself carries the annotation so a future refactor (renaming or removing the
        // method, dropping the annotation, moving to another class) trips here rather
        // than silently disabling the sweep in production.
        final Method m = InvitationService.class.getDeclaredMethod("pruneOldInvitations");
        final Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "pruneOldInvitations must be @Scheduled");
        assertFalse(scheduled.fixedDelayString().isBlank(),
                "@Scheduled must specify a fixedDelayString so it actually runs periodically");
    }

    @Test
    void pruneOldInvitationsToleratesEmptyResult() {
        // Empty-collection path: must not throw, must not log noise. Just a smoke test
        // that the zero-removed branch works.
        when(invitationRepository.deleteByConsumedAtBeforeOrExpiresAtBefore(any(), any()))
                .thenReturn(0L);

        service.pruneOldInvitations();

        verify(invitationRepository, times(1))
                .deleteByConsumedAtBeforeOrExpiresAtBefore(any(), any());
    }
}
