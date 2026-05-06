/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.services;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link RedactionCertificate} captured when a document is finalized.
 * The certificate is reconstructed from existing audit records — no separate timeline
 * is maintained — so it always reflects what was actually written to the audit log.
 */
@Service
public class RedactionCertificateService {

    private static final Logger log = LoggerFactory.getLogger(RedactionCertificateService.class);

    private final RedactionCertificateRepository certificateRepository;
    private final SpanRepository spanRepository;
    private final AuditLogRepository auditLogRepository;

    public RedactionCertificateService(final RedactionCertificateRepository certificateRepository,
                                       final SpanRepository spanRepository,
                                       final AuditLogRepository auditLogRepository) {
        this.certificateRepository = certificateRepository;
        this.spanRepository = spanRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public RedactionCertificate generate(final Document document, final String finalizedByEmail) {
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setId(UUID.randomUUID().toString());
        cert.setDocumentId(document.getId());
        cert.setDocumentFilename(document.getFilename());
        cert.setDocumentHash(sha256Hex(document.getOriginalText()));
        cert.setFinalizedAt(Instant.now());
        cert.setFinalizedBy(finalizedByEmail);
        cert.setReviewers(buildReviewers(document));
        cert.setOverturns(buildOverturns(document.getId()));
        return certificateRepository.save(cert);
    }

    private static String sha256Hex(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(
                    (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JDK; this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Walk the audit log for {@code DOCUMENT_APPROVAL} entries on this document and
     * line them up with {@code Document.approvedBy}. The audit log carries the actor
     * email and timestamp; the document's approvedBy gives canonical order. We pair
     * them by email so the certificate has accurate per-reviewer timestamps even when
     * the audit log query order isn't guaranteed.
     */
    private List<RedactionCertificate.ApprovalEntry> buildReviewers(final Document document) {
        final List<AuditLog> approvals = auditLogRepository
                .findByResourceTypeAndResourceIdOrderByTimestampAsc("Document", document.getId())
                .stream()
                .filter(e -> "DOCUMENT_APPROVAL".equals(e.getAction()))
                .toList();

        final List<RedactionCertificate.ApprovalEntry> result = new ArrayList<>();
        for (String email : document.getApprovedBy()) {
            final Instant ts = approvals.stream()
                    .filter(e -> email != null && email.equalsIgnoreCase(e.getUserEmail()))
                    .map(AuditLog::getTimestamp)
                    .findFirst()
                    .orElse(null);
            result.add(new RedactionCertificate.ApprovalEntry(email, ts));
        }
        return result;
    }

    /**
     * Pull every {@code SPAN_UPDATE} audit entry with {@code overturn=true} for the
     * spans on this document, regardless of who performed the overturn. Each entry
     * already carries the previous/new status, the prior actor, the new actor, and
     * the reason supplied at the time.
     */
    private List<RedactionCertificate.OverturnEntry> buildOverturns(final String documentId) {
        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        final List<RedactionCertificate.OverturnEntry> out = new ArrayList<>();
        for (Span span : spans) {
            final List<AuditLog> entries = auditLogRepository
                    .findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", span.getId());
            for (AuditLog e : entries) {
                if (!"SPAN_UPDATE".equals(e.getAction())) continue;
                final Map<String, Object> details = e.getDetails();
                if (details == null) continue;
                if (!Boolean.TRUE.equals(details.get("overturn"))) continue;

                final RedactionCertificate.OverturnEntry o = new RedactionCertificate.OverturnEntry();
                o.setSpanId(span.getId());
                o.setSpanText(span.getText());
                o.setSpanType(span.getType());
                o.setPreviousStatus(asString(details.get("previousStatus")));
                o.setNewStatus(asString(details.get("status")));
                o.setPreviousActor(asString(details.get("previousStatusChangedBy")));
                o.setOverturnedBy(e.getUserEmail());
                o.setOverturnedAt(e.getTimestamp());
                o.setReason(asString(details.get("reason")));
                out.add(o);
            }
        }
        out.sort(Comparator.comparing(
                RedactionCertificate.OverturnEntry::getOverturnedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static String asString(final Object o) {
        return o == null ? null : o.toString();
    }
}
