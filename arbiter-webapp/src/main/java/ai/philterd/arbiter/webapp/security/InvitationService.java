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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and redeems one-shot invitations so admins never need to type or email a user's
 * password. The plaintext token is generated server-side, returned to the caller for
 * inclusion in the invitation email, and immediately discarded — only the SHA-256 hash
 * is persisted. Redemption looks up by hash and refuses tokens that are unknown,
 * already-consumed, or past their {@link Invitation#getExpiresAt() expiration}.
 *
 * <p>Lifetime defaults to 7 days; configurable via {@link #DEFAULT_TTL}. Redemption is
 * single-shot — once {@code consumedAt} is set, the same token cannot be reused.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    /**
     * Retention for consumed-or-expired invitation rows. Rows past this age are swept
     * by {@link #pruneOldInvitations()}. Long enough that an auditor reviewing recent
     * admin activity still sees the redemption row, short enough that the collection
     * doesn't grow without bound.
     */
    public static final Duration CLEANUP_RETENTION = Duration.ofDays(30);

    /** 32 bytes of randomness, base64url-encoded — 43 ASCII chars, opaque to the recipient. */
    private static final int TOKEN_BYTES = 32;

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public InvitationService(final InvitationRepository invitationRepository,
                             final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder) {
        this(invitationRepository, userRepository, passwordEncoder, Clock.systemUTC());
    }

    /** Test seam: lets tests inject a fixed clock. Not used by Spring. */
    public InvitationService(final InvitationRepository invitationRepository,
                             final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final Clock clock) {
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Carries the plaintext token back to the caller. The token is never stored anywhere else. */
    public record IssuedInvitation(Invitation invitation, String token) {}

    /**
     * Issue a fresh invitation for an email. If a prior pending invitation exists for the
     * same address it is replaced so the new token supersedes the old one (and the old token,
     * even if leaked, no longer redeems anything).
     */
    public IssuedInvitation issue(final String email, final boolean admin, final Set<String> groupIds) {
        final String trimmed = email == null ? "" : email.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Email is required.");
        }
        // Defence-in-depth against log/audit forgery: refuse control characters
        // here too, so a caller that bypasses AdminController.isValidEmail can't
        // smuggle \n into the persisted invitation row and surface it through
        // the redemption-success log line further down.
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("Email contains a control character.");
            }
        }
        // Drop any prior pending row for this email so the new token is the only one valid.
        invitationRepository.findByEmail(trimmed).ifPresent(prev -> invitationRepository.deleteById(prev.getId()));

        final String token = randomToken();
        final Invitation invite = new Invitation();
        invite.setId(UUID.randomUUID().toString());
        invite.setTokenHash(sha256(token));
        invite.setEmail(trimmed);
        invite.setAdmin(admin);
        invite.setGroupIds(groupIds == null ? new HashSet<>() : new HashSet<>(groupIds));
        final Instant now = clock.instant();
        invite.setCreatedAt(now);
        invite.setExpiresAt(now.plus(DEFAULT_TTL));
        invitationRepository.save(invite);
        return new IssuedInvitation(invite, token);
    }

    /** Look up the underlying invitation given a public token. Used by GET /invitations/{token}. */
    public Optional<Invitation> findByToken(final String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return invitationRepository.findByTokenHash(sha256(token));
    }

    /** Reasons {@link #redeem(String, String)} can fail. */
    public enum RedemptionStatus {
        /** Token unknown, doesn't decode, or matches no record. */
        INVALID_TOKEN,
        /** Token previously redeemed. */
        ALREADY_REDEEMED,
        /** Past {@link Invitation#getExpiresAt()}. */
        EXPIRED,
        /** Password didn't satisfy the policy. */
        WEAK_PASSWORD,
        /** Email is already a registered user — race between admin create and a self-signup elsewhere. */
        EMAIL_ALREADY_TAKEN,
        /** User row was created and the invitation was marked consumed. */
        OK
    }

    /**
     * Redeem a token by setting the user's password and creating the {@code User} row.
     * Single-shot: a successful redemption marks {@code consumedAt} so the same token can
     * never be used again, even if the email recipient saved it.
     */
    public synchronized RedemptionStatus redeem(final String token, final String password) {
        final Optional<Invitation> opt = findByToken(token);
        if (opt.isEmpty()) return RedemptionStatus.INVALID_TOKEN;
        final Invitation invite = opt.get();
        if (invite.getConsumedAt() != null) return RedemptionStatus.ALREADY_REDEEMED;
        if (invite.getExpiresAt() != null && !clock.instant().isBefore(invite.getExpiresAt())) {
            return RedemptionStatus.EXPIRED;
        }
        if (password == null || password.length() < 12) return RedemptionStatus.WEAK_PASSWORD;
        if (userRepository.findByEmail(invite.getEmail()).isPresent()) {
            return RedemptionStatus.EMAIL_ALREADY_TAKEN;
        }

        final User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        user.setEmail(invite.getEmail());
        user.setPasswordHash(passwordEncoder.encode(password));
        final Set<String> roles = new HashSet<>();
        roles.add(invite.isAdmin() ? Roles.ADMIN : Roles.USER);
        user.setRoles(roles);
        userRepository.save(user);

        invite.setConsumedAt(clock.instant());
        invitationRepository.save(invite);
        log.info("Redeemed invitation for {} (admin={})", invite.getEmail(), invite.isAdmin());
        return RedemptionStatus.OK;
    }

    /**
     * Sweep invitations that are either consumed or expired and older than
     * {@link #CLEANUP_RETENTION}. Runs once a day; the cadence matters less than the
     * fact that it runs at all — the goal is to keep the collection bounded so the
     * token-hash index stays small and the redemption lookup stays fast.
     */
    @Scheduled(fixedDelayString = "${arbiter.invitations.cleanup-millis:86400000}",
            initialDelayString = "${arbiter.invitations.cleanup-initial-delay-millis:60000}")
    public void pruneOldInvitations() {
        final Instant cutoff = clock.instant().minus(CLEANUP_RETENTION);
        final long removed = invitationRepository
                .deleteByConsumedAtBeforeOrExpiresAtBefore(cutoff, cutoff);
        if (removed > 0) {
            log.info("Pruned {} consumed-or-expired invitations older than {}",
                    removed, CLEANUP_RETENTION);
        }
    }

    private String randomToken() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time compare for a future use case where two known-length tokens are compared. */
    static boolean constantTimeEquals(final String a, final String b) {
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
