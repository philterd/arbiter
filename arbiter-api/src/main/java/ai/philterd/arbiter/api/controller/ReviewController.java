package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.SpanUpdateRequest;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "APPROVED", "REJECTED", "PENDING", Span.STATUS_NEEDS_SECOND_OPINION);

    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;

    public ReviewController(final SpanRepository spanRepository,
                            final DocumentRepository documentRepository,
                            final BatchRepository batchRepository,
                            final UserGroupsService userGroupsService,
                            final AuditLogService auditLogService) {
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
    }

    private void requireDocumentAccess(final Authentication auth, final Document document) {
        if (isAdmin(auth)) return;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    /**
     * Spans on a document in a terminal status are read-only. APPROVED / REJECTED documents
     * can be reopened (Unapprove / Unreject). FINALIZED is a permanent terminal state — no
     * further edits are accepted via the API.
     */
    private static void requireEditable(final Document document) {
        if ("APPROVED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is APPROVED. Unapprove it before adding, editing, or deleting spans.");
        }
        if ("REJECTED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is REJECTED. Unreject it before adding, editing, or deleting spans.");
        }
        if ("FINALIZED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is FINALIZED. Finalized documents cannot be modified.");
        }
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    @GetMapping("/documents/{id}/spans")
    public List<Span> getSpans(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);
        return spanRepository.findByDocumentId(id);
    }

    public record CreateSpanRequest(String type, Integer start, Integer end) {}

    @PostMapping("/documents/{documentId}/spans")
    public Span createSpan(@PathVariable final String documentId,
                           @RequestBody final CreateSpanRequest request,
                           final Authentication authentication) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "type is required.");
        }
        if (request.start() == null || request.end() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "start and end are required.");
        }
        final int start = request.start();
        final int end = request.end();
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid range.");
        }
        final String normalized = request.type().trim().toLowerCase();
        if (!PiiTypes.isValid(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
        }
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + documentId));
        requireDocumentAccess(authentication, document);
        requireEditable(document);
        final String original = document.getOriginalText() == null ? "" : document.getOriginalText();
        if (end > original.length()) {
            throw new ResponseStatusException(BAD_REQUEST, "Range exceeds document length.");
        }
        final String text = original.substring(start, end);
        if (text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Selection is empty.");
        }

        final Span span = new Span();
        span.setId(UUID.randomUUID().toString());
        span.setDocumentId(documentId);
        span.setType(normalized);
        span.setText(text);
        span.setConfidence(1.0);
        span.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        span.setManuallyCreated(true);
        span.setCreatedAt(LocalDateTime.now());
        span.changeStatus("APPROVED", authentication == null ? null : authentication.getName());
        final Span saved = spanRepository.save(span);

        auditLogService.log("SPAN_CREATE", "Span", saved.getId(),
                Map.of("spanId", saved.getId(),
                        "documentId", documentId,
                        "type", normalized,
                        "start", start,
                        "end", end,
                        "length", text.length()));
        return saved;
    }

    @PatchMapping("/spans/{id}")
    public Span updateSpan(@PathVariable final String id,
                           @RequestBody final SpanUpdateRequest request,
                           final Authentication authentication) {
        if (request == null || (request.status() == null && request.type() == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one of 'status' or 'type' must be provided.");
        }
        final Span span = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));
        final Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);
        requireEditable(document);

        final String actor = authentication == null ? null : authentication.getName();
        final Map<String, Object> changes = new LinkedHashMap<>();
        if (request.status() != null) {
            if (!ALLOWED_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid status: " + request.status());
            }
            // Overturn check: moving a span *out of* APPROVED, where the prior approval
            // was recorded by a different reviewer, requires a reason for the audit trail.
            final boolean leavingApproved = "APPROVED".equals(span.getStatus())
                    && !"APPROVED".equals(request.status());
            final String priorActor = span.getStatusChangedBy();
            final boolean differentReviewer = priorActor != null && actor != null
                    && !priorActor.equalsIgnoreCase(actor);
            final String reason = request.reason() == null ? "" : request.reason().trim();
            if (leavingApproved && differentReviewer && reason.isEmpty()) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "OVERTURN_REASON_REQUIRED: a reason is required to overturn another reviewer's approval.");
            }
            changes.put("previousStatus", span.getStatus() == null ? "" : span.getStatus());
            changes.put("status", request.status());
            if (priorActor != null) {
                changes.put("previousStatusChangedBy", priorActor);
            }
            if (leavingApproved && differentReviewer) {
                changes.put("overturn", true);
                changes.put("reason", reason);
            } else if (!reason.isEmpty()) {
                // Reason supplied without an overturn — record it for traceability.
                changes.put("reason", reason);
            }
            span.changeStatus(request.status(), actor);
            if ("APPROVED".equals(request.status())) {
                final String code = request.exemptionCode() == null ? null : request.exemptionCode().trim();
                span.setExemptionCode(code == null || code.isEmpty() ? null : code);
                if (span.getExemptionCode() != null) {
                    changes.put("exemptionCode", span.getExemptionCode());
                }
            } else {
                span.setExemptionCode(null);
            }
        }
        if (request.type() != null) {
            final String normalized = request.type().trim().toLowerCase();
            if (!PiiTypes.isValid(normalized)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
            }
            changes.put("previousType", span.getType() == null ? "" : span.getType());
            changes.put("type", normalized);
            span.setType(normalized);
        }
        final Span saved = spanRepository.save(span);
        changes.put("spanId", saved.getId());
        changes.put("documentId", saved.getDocumentId() == null ? "" : saved.getDocumentId());
        auditLogService.log("SPAN_UPDATE", "Span", saved.getId(), changes);
        return saved;
    }

    @DeleteMapping("/spans/{id}")
    public Map<String, Object> deleteSpan(@PathVariable final String id, final Authentication authentication) {
        final Span span = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));
        final Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);
        requireEditable(document);

        if (!span.isManuallyCreated()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Only manually-created spans can be deleted. "
                            + "Use the Refused toggle to ignore a detected span.");
        }

        spanRepository.deleteById(id);
        auditLogService.log("SPAN_DELETE", "Span", id,
                Map.of("spanId", id,
                        "documentId", document.getId(),
                        "type", span.getType() == null ? "" : span.getType()));
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/spans/{id}/redact-like")
    public Map<String, Object> redactAllLike(@PathVariable final String id, final Authentication authentication) {
        final Span source = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));

        final String needle = source.getText();
        if (needle == null || needle.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Source span has no text to match.");
        }

        final Document document = documentRepository.findById(source.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + source.getDocumentId()));
        requireDocumentAccess(authentication, document);
        requireEditable(document);
        // Redact All Like This is only meaningful while the source span is still a redaction
        // candidate. If the reviewer refused it or flagged it for a second opinion, the action
        // is disabled — propagating the source's redaction across the document doesn't make
        // sense in either case.
        if ("REJECTED".equals(source.getStatus())
                || Span.STATUS_NEEDS_SECOND_OPINION.equals(source.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Redact All Like This is only available for approved or pending spans.");
        }
        final String haystack = document.getOriginalText();
        if (haystack == null || haystack.isEmpty()) {
            return Map.of("created", 0, "approved", 0);
        }

        final List<Span> existing = spanRepository.findByDocumentId(source.getDocumentId());
        final Map<Long, Span> byRange = new HashMap<>();
        final List<long[]> existingRanges = new ArrayList<>();
        for (Span s : existing) {
            if (s.getLocation() == null) continue;
            final long start = s.getLocation().characterStart();
            final long end = s.getLocation().characterEnd();
            byRange.put((start << 32) | (end & 0xFFFFFFFFL), s);
            existingRanges.add(new long[]{start, end});
        }

        final String actor = authentication == null ? null : authentication.getName();
        source.changeStatus("APPROVED", actor);
        spanRepository.save(source);

        final List<Span> toSave = new ArrayList<>();
        int created = 0;
        int approved = 0;
        int cursor = 0;
        while (true) {
            final int idx = haystack.indexOf(needle, cursor);
            if (idx < 0) break;
            final int end = idx + needle.length();
            cursor = end;

            if (idx == source.getLocation().characterStart() && end == source.getLocation().characterEnd()) {
                continue;
            }

            final Span exact = byRange.get(((long) idx << 32) | (end & 0xFFFFFFFFL));
            if (exact != null) {
                exact.changeStatus("APPROVED", actor);
                exact.setType(source.getType());
                toSave.add(exact);
                approved++;
                continue;
            }

            if (overlapsExisting(existingRanges, idx, end)) {
                continue;
            }

            final Span fresh = new Span();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setDocumentId(source.getDocumentId());
            fresh.setType(source.getType());
            fresh.setText(needle);
            fresh.setConfidence(1.0);
            fresh.setLocation(new Location(idx, end, 1, new Coordinates(0, 0, 0, 0)));
            fresh.setManuallyCreated(true);
            fresh.setCreatedAt(LocalDateTime.now());
            fresh.changeStatus("APPROVED", actor);
            toSave.add(fresh);
            existingRanges.add(new long[]{idx, end});
            created++;
        }

        if (!toSave.isEmpty()) {
            spanRepository.saveAll(toSave);
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("approved", approved);

        final Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("spanId", source.getId());
        auditDetails.put("documentId", source.getDocumentId());
        auditDetails.put("type", source.getType() == null ? "" : source.getType());
        auditDetails.put("created", created);
        auditDetails.put("approved", approved);
        auditLogService.log("SPAN_REDACT_LIKE", "Span", source.getId(), auditDetails);
        return result;
    }

    public record ResetSpanRequest(String originalStatus) {}

    @PostMapping("/spans/{id}/reset")
    public Span resetSpan(@PathVariable final String id,
                          @RequestBody(required = false) final ResetSpanRequest request,
                          final Authentication authentication) {
        final Span span = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));
        final Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);
        requireEditable(document);

        final String actor = authentication == null ? null : authentication.getName();
        final String previousStatus = span.getStatus() == null ? "" : span.getStatus();
        final String originalStatus = request == null ? null : request.originalStatus();

        span.changeStatus(originalStatus, actor);
        final Span saved = spanRepository.save(span);

        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("spanId", saved.getId());
        details.put("documentId", saved.getDocumentId() == null ? "" : saved.getDocumentId());
        details.put("previousStatus", previousStatus);
        details.put("originalStatus", originalStatus == null ? "" : originalStatus);
        auditLogService.log("SPAN_RESET", "Span", saved.getId(), details);
        return saved;
    }

    private static boolean overlapsExisting(final List<long[]> ranges, final int start, final int end) {
        for (long[] r : ranges) {
            if (start < r[1] && end > r[0]) return true;
        }
        return false;
    }
}
