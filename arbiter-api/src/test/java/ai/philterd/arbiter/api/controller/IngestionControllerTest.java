package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.IngestRequest;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.RedactionApiService;
import ai.philterd.arbiter.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionControllerTest {

    private RedactionApiService redactionApiService;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TestDoubles.RecordingAuditLog auditLogService;
    private GeneralSettingsService generalSettingsService;
    private IngestionController controller;

    @BeforeEach
    void setUp() {
        redactionApiService = mock(RedactionApiService.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        generalSettingsService = mock(GeneralSettingsService.class);
        final GeneralSettings settings = new GeneralSettings();
        settings.setMaxUploadFileSizeBytes(10L * 1024L * 1024L);
        when(generalSettingsService.load()).thenReturn(settings);
        controller = new IngestionController(redactionApiService, documentRepository,
                batchRepository, userGroupsService, auditLogService, generalSettingsService);
    }

    private static Batch openBatch(final String id, final String groupId, final String name) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        return b;
    }

    private static Batch closedBatch(final String id, final String groupId, final String name) {
        final Batch b = openBatch(id, groupId, name);
        b.setClosed(true);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(final ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void unknownBatchReturns400() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());
        final IngestRequest req = new IngestRequest("doc.txt", "missing", "hello");

        final ResponseEntity<?> response = controller.ingest(req, TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(((String) body(response).get("error")).contains("missing"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void nonAdminWithoutGroupAccessReturns403() {
        final Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void closedBatchReturns409WithDetails() {
        final Batch batch = closedBatch("b1", "g1", "Closed Batch");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        final Map<String, Object> body = body(response);
        assertEquals("b1", body.get("batchId"));
        assertEquals(true, body.get("closed"));
        assertTrue(((String) body.get("error")).contains("Closed Batch"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void happyPathSavesDocumentAndReturns202() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello world"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        final Map<String, Object> body = body(response);
        final String taskId = (String) body.get("taskId");
        assertNotNull(taskId);

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        final Document saved = docCaptor.getValue();
        assertEquals(taskId, saved.getId());
        assertEquals("b1", saved.getBatchId());
        assertEquals("doc.txt", saved.getFilename());
        assertEquals("hello world", saved.getOriginalText());
        assertEquals("PENDING", saved.getStatus());

        assertTrue(auditLogService.hasAction("DOCUMENT_INGEST"));
        // Content SHA-512 is recorded for chain-of-custody.
        assertEquals(Hashing.sha512Hex("hello world"), saved.getContentSha512());
    }

    @Test
    void emptyTextStillGetsContentHash() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        controller.ingest(new IngestRequest("doc.txt", "b1", null),
                TestAuth.user("alice@example.com"));

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        // Null/missing text is hashed as empty string so every persisted document has a hash.
        assertEquals(Hashing.sha512Hex(""), docCaptor.getValue().getContentSha512());
    }

    @Test
    void adminCanIngestEvenWithoutGroupMembership() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        // admin not in any group

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi"),
                TestAuth.admin("admin@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(documentRepository).save(any());
    }

    @Test
    void unauthenticatedRequestIsForbiddenWhenBatchHasGroup() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi"),
                (Authentication) null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(documentRepository, never()).save(any());
    }
}
