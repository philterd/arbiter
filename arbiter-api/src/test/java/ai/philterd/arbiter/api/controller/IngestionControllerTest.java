package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.IngestRequest;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.RedactionApiService;
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
    private IngestionController controller;

    @BeforeEach
    void setUp() {
        redactionApiService = mock(RedactionApiService.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        controller = new IngestionController(redactionApiService, documentRepository,
                batchRepository, userGroupsService, auditLogService);
    }

    private static Batch openBatch(String id, String groupId, String name) {
        Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        return b;
    }

    private static Batch closedBatch(String id, String groupId, String name) {
        Batch b = openBatch(id, groupId, name);
        b.setClosed(true);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void unknownBatchReturns400() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());
        IngestRequest req = new IngestRequest("doc.txt", "missing", "hello");

        ResponseEntity<?> response = controller.ingest(req, TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(((String) body(response).get("error")).contains("missing"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void nonAdminWithoutGroupAccessReturns403() {
        Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void closedBatchReturns409WithDetails() {
        Batch batch = closedBatch("b1", "g1", "Closed Batch");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, Object> body = body(response);
        assertEquals("b1", body.get("batchId"));
        assertEquals(true, body.get("closed"));
        assertTrue(((String) body.get("error")).contains("Closed Batch"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void happyPathSavesDocumentAndReturns202() {
        Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello world"),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        Map<String, Object> body = body(response);
        String taskId = (String) body.get("taskId");
        assertNotNull(taskId);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        Document saved = docCaptor.getValue();
        assertEquals(taskId, saved.getId());
        assertEquals("b1", saved.getBatchId());
        assertEquals("doc.txt", saved.getFilename());
        assertEquals("hello world", saved.getOriginalText());
        assertEquals("PENDING", saved.getStatus());

        assertTrue(auditLogService.hasAction("DOCUMENT_INGEST"));
    }

    @Test
    void adminCanIngestEvenWithoutGroupMembership() {
        Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        // admin not in any group

        ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi"),
                TestAuth.admin("admin@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(documentRepository).save(any());
    }

    @Test
    void unauthenticatedRequestIsForbiddenWhenBatchHasGroup() {
        Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi"),
                (Authentication) null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(documentRepository, never()).save(any());
    }
}
