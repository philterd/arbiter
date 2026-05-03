package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.IngestRequest;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.RedactionApiService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private final RedactionApiService redactionApiService;
    private final DocumentRepository documentRepository;

    public IngestionController(RedactionApiService redactionApiService, DocumentRepository documentRepository) {
        this.redactionApiService = redactionApiService;
        this.documentRepository = documentRepository;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody IngestRequest request) {

        final String taskId = UUID.randomUUID().toString();
        
        final Document document = new Document();
        document.setId(taskId);
        document.setFilename(request.name());
        document.setOriginalText(request.text());
        document.setStatus("PENDING");
        documentRepository.save(document);

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
}
