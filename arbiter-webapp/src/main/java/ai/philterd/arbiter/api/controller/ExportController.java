package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.RedactionApiService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private final RedactionApiService redactionApiService;
    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public ExportController(final RedactionApiService redactionApiService,
                            final SpanRepository spanRepository,
                            final DocumentRepository documentRepository,
                            final BatchRepository batchRepository,
                            final UserGroupsService userGroupsService) {
        this.redactionApiService = redactionApiService;
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    @PostMapping("/documents/{id}/finalize")
    public Map<String, String> finalize(@PathVariable final String id, final Authentication authentication) throws IOException {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);
        final String finalizedText = redactionApiService.finalizeRedaction(id);
        return Map.of("finalizedText", finalizedText);
    }

    @GetMapping("/documents/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + id));
        requireDocumentAccess(authentication, document);
        final List<Span> spans = spanRepository.findByDocumentId(id);
        return spans.stream().map(s -> Map.<String, Object>of(
            "text", s.getText(),
            "type", s.getType(),
            "confidence", s.getConfidence(),
            "status", s.getStatus()
        )).collect(Collectors.toList());
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

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
