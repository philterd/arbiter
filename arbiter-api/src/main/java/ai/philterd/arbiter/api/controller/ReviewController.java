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

    private static final Set<String> ALLOWED_STATUSES = Set.of("APPROVED", "REJECTED", "PENDING");

    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;

    public ReviewController(SpanRepository spanRepository,
                            DocumentRepository documentRepository,
                            BatchRepository batchRepository,
                            UserGroupsService userGroupsService,
                            AuditLogService auditLogService) {
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
    }

    private void requireDocumentAccess(Authentication auth, Document document) {
        if (isAdmin(auth)) return;
        Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    @GetMapping("/documents/{id}/spans")
    public List<Span> getSpans(@PathVariable String id, Authentication authentication) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);
        return spanRepository.findByDocumentId(id);
    }

    public record CreateSpanRequest(String type, Integer start, Integer end) {}

    @PostMapping("/documents/{documentId}/spans")
    public Span createSpan(@PathVariable String documentId,
                           @RequestBody CreateSpanRequest request,
                           Authentication authentication) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "type is required.");
        }
        if (request.start() == null || request.end() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "start and end are required.");
        }
        int start = request.start();
        int end = request.end();
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid range.");
        }
        String normalized = request.type().trim().toLowerCase();
        if (!PiiTypes.isValid(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + documentId));
        requireDocumentAccess(authentication, document);
        String original = document.getOriginalText() == null ? "" : document.getOriginalText();
        if (end > original.length()) {
            throw new ResponseStatusException(BAD_REQUEST, "Range exceeds document length.");
        }
        String text = original.substring(start, end);
        if (text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Selection is empty.");
        }

        Span span = new Span();
        span.setId(UUID.randomUUID().toString());
        span.setDocumentId(documentId);
        span.setType(normalized);
        span.setText(text);
        span.setConfidence(1.0);
        span.setStatus("APPROVED");
        span.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        span.setManuallyCreated(true);
        Span saved = spanRepository.save(span);

        auditLogService.log("SPAN_CREATE", "Span", saved.getId(),
                Map.of("documentId", documentId,
                        "type", normalized,
                        "start", start,
                        "end", end,
                        "length", text.length()));
        return saved;
    }

    @PatchMapping("/spans/{id}")
    public Span updateSpan(@PathVariable String id,
                           @RequestBody SpanUpdateRequest request,
                           Authentication authentication) {
        if (request == null || (request.status() == null && request.type() == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one of 'status' or 'type' must be provided.");
        }
        Span span = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));
        Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.status() != null) {
            if (!ALLOWED_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid status: " + request.status());
            }
            changes.put("previousStatus", span.getStatus() == null ? "" : span.getStatus());
            changes.put("status", request.status());
            span.setStatus(request.status());
        }
        if (request.type() != null) {
            String normalized = request.type().trim().toLowerCase();
            if (!PiiTypes.isValid(normalized)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
            }
            changes.put("previousType", span.getType() == null ? "" : span.getType());
            changes.put("type", normalized);
            span.setType(normalized);
        }
        Span saved = spanRepository.save(span);
        changes.put("documentId", saved.getDocumentId() == null ? "" : saved.getDocumentId());
        auditLogService.log("SPAN_UPDATE", "Span", saved.getId(), changes);
        return saved;
    }

    @DeleteMapping("/spans/{id}")
    public Map<String, Object> deleteSpan(@PathVariable String id, Authentication authentication) {
        Span span = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));
        Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);

        if (!span.isManuallyCreated()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Only manually-created spans can be deleted. "
                            + "Use the Refused toggle to ignore a detected span.");
        }

        spanRepository.deleteById(id);
        auditLogService.log("SPAN_DELETE", "Span", id,
                Map.of("documentId", document.getId(),
                        "type", span.getType() == null ? "" : span.getType()));
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/spans/{id}/redact-like")
    public Map<String, Object> redactAllLike(@PathVariable String id, Authentication authentication) {
        Span source = spanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + id));

        String needle = source.getText();
        if (needle == null || needle.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Source span has no text to match.");
        }

        Document document = documentRepository.findById(source.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + source.getDocumentId()));
        requireDocumentAccess(authentication, document);
        String haystack = document.getOriginalText();
        if (haystack == null || haystack.isEmpty()) {
            return Map.of("created", 0, "approved", 0);
        }

        List<Span> existing = spanRepository.findByDocumentId(source.getDocumentId());
        Map<Long, Span> byRange = new HashMap<>();
        List<long[]> existingRanges = new ArrayList<>();
        for (Span s : existing) {
            if (s.getLocation() == null) continue;
            long start = s.getLocation().characterStart();
            long end = s.getLocation().characterEnd();
            byRange.put((start << 32) | (end & 0xFFFFFFFFL), s);
            existingRanges.add(new long[]{start, end});
        }

        source.setStatus("APPROVED");
        spanRepository.save(source);

        List<Span> toSave = new ArrayList<>();
        int created = 0;
        int approved = 0;
        int cursor = 0;
        while (true) {
            int idx = haystack.indexOf(needle, cursor);
            if (idx < 0) break;
            int end = idx + needle.length();
            cursor = end;

            if (idx == source.getLocation().characterStart() && end == source.getLocation().characterEnd()) {
                continue;
            }

            Span exact = byRange.get(((long) idx << 32) | (end & 0xFFFFFFFFL));
            if (exact != null) {
                exact.setStatus("APPROVED");
                exact.setType(source.getType());
                toSave.add(exact);
                approved++;
                continue;
            }

            if (overlapsExisting(existingRanges, idx, end)) {
                continue;
            }

            Span fresh = new Span();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setDocumentId(source.getDocumentId());
            fresh.setType(source.getType());
            fresh.setText(needle);
            fresh.setConfidence(1.0);
            fresh.setStatus("APPROVED");
            fresh.setLocation(new Location(idx, end, 1, new Coordinates(0, 0, 0, 0)));
            fresh.setManuallyCreated(true);
            toSave.add(fresh);
            existingRanges.add(new long[]{idx, end});
            created++;
        }

        if (!toSave.isEmpty()) {
            spanRepository.saveAll(toSave);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("approved", approved);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("documentId", source.getDocumentId());
        auditDetails.put("type", source.getType() == null ? "" : source.getType());
        auditDetails.put("created", created);
        auditDetails.put("approved", approved);
        auditLogService.log("SPAN_REDACT_LIKE", "Span", source.getId(), auditDetails);
        return result;
    }

    private static boolean overlapsExisting(List<long[]> ranges, int start, int end) {
        for (long[] r : ranges) {
            if (start < r[1] && end > r[0]) return true;
        }
        return false;
    }
}
