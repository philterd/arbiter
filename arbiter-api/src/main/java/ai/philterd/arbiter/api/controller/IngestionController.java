package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.IngestRequest;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.RedactionApiService;
import ai.philterd.arbiter.service.UserGroupsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private final RedactionApiService redactionApiService;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;

    public IngestionController(RedactionApiService redactionApiService,
                               DocumentRepository documentRepository,
                               BatchRepository batchRepository,
                               UserGroupsService userGroupsService,
                               AuditLogService auditLogService) {
        this.redactionApiService = redactionApiService;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@Valid @RequestBody IngestRequest request, Authentication authentication) {

        Batch batch = batchRepository.findById(request.batchId()).orElse(null);
        if (batch == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch not found: " + request.batchId()));
        }
        if (!canAccessBatch(authentication, batch)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to that batch."));
        }
        if (batch.isClosed()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Batch \"" + batch.getName() + "\" is closed and cannot accept new documents.",
                    "batchId", batch.getId(),
                    "closed", true));
        }

        final String taskId = UUID.randomUUID().toString();

        final Document document = new Document();
        document.setId(taskId);
        document.setBatchId(request.batchId());
        document.setCreatedAt(LocalDateTime.now());
        document.setFilename(request.name());
        document.setOriginalText(request.text());
        document.setStatus("PENDING");
        documentRepository.save(document);
        auditLogService.log("DOCUMENT_INGEST", "Document", taskId,
                Map.of(
                        "batchId", request.batchId(),
                        "name", request.name() == null ? "" : request.name(),
                        "textLength", request.text() == null ? 0 : request.text().length()));

        // Start asynchronous process
        CompletableFuture.runAsync(() -> {
            try {
                redactionApiService.processDocument(taskId, request.text());
            } catch (Exception e) {
                // In a real app, update document status to FAILED
                e.printStackTrace();
            }
        });

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    private boolean canAccessBatch(Authentication auth, Batch batch) {
        if (isAdmin(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        Set<String> myGroupIds = userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
        return myGroupIds.contains(batch.getGroupId());
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
