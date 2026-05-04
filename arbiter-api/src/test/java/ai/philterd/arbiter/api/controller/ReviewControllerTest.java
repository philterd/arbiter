package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.SpanUpdateRequest;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TestDoubles.RecordingAuditLog auditLogService;
    private ReviewController controller;
    private static final org.springframework.security.core.Authentication ADMIN = TestAuth.admin("admin@example.com");

    @BeforeEach
    void setUp() {
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        controller = new ReviewController(spanRepository, documentRepository,
                batchRepository, userGroupsService, auditLogService);
        // Default stub: a document "d1" the access helper can resolve.
        // Tests that need a different shape override this.
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", "")));
    }

    private static Span span(String id, String docId, String type, String status,
                             int start, int end, String text) {
        Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        s.setType(type);
        s.setStatus(status);
        s.setText(text);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    private static Document document(String id, String text) {
        Document d = new Document();
        d.setId(id);
        d.setOriginalText(text);
        return d;
    }

    // ---- getSpans ----

    @Test
    void getSpansReturnsRepositoryResult() {
        Span s = span("s1", "d1", "ssn", "PENDING", 0, 11, "123-45-6789");
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s));

        List<Span> result = controller.getSpans("d1", ADMIN);

        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).getId());
    }

    // ---- updateSpan ----

    @Test
    void updateSpanRejectsNullRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", null, ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsBothFieldsNull() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("ghost", new SpanUpdateRequest("APPROVED", null), ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidStatus() {
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("WHATEVER", null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidType() {
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, "made-up"), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanAppliesStatusAndAudits() {
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Span updated = controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), ADMIN);

        assertEquals("APPROVED", updated.getStatus());
        assertTrue(auditLogService.hasAction("SPAN_UPDATE"));
    }

    @Test
    void updateSpanNormalizesType() {
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Span updated = controller.updateSpan("s1", new SpanUpdateRequest(null, "  Phone-Number  "), ADMIN);

        assertEquals("phone-number", updated.getType());
    }

    @Test
    void updateSpanCanChangeBoth() {
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Span updated = controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", "phone-number"), ADMIN);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("phone-number", updated.getType());
    }

    // ---- redactAllLike ----

    @Test
    void redactAllLikeRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void redactAllLikeRejectsEmptySourceText() {
        Span source = span("s1", "d1", "ssn", "APPROVED", 0, 0, "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void redactAllLikeReturnsZerosWhenDocumentTextEmpty() {
        Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        Document doc = document("d1", "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
    }

    @Test
    void redactAllLikeCreatesNewSpansForAdditionalMatches() {
        // Document has "Project Icarus" three times. Source span covers the first occurrence.
        String text = "Project Icarus is great. Look at Project Icarus again. And Project Icarus.";
        int firstStart = text.indexOf("Project Icarus");
        int firstEnd = firstStart + "Project Icarus".length();
        Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "Project Icarus");
        Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // Source becomes APPROVED + 2 new spans created for the other matches.
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository).saveAll(anyList());
        assertTrue(auditLogService.hasAction("SPAN_REDACT_LIKE"));
    }

    @Test
    void redactAllLikeFlipsExistingExactRangesToApproved() {
        // Document has "alpha" twice. Source covers the first; an existing span at the second range exists.
        String text = "alpha and alpha";
        int firstStart = 0;
        int firstEnd = 5;
        int secondStart = text.indexOf("alpha", firstEnd);
        int secondEnd = secondStart + 5;

        Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "alpha");
        Span existing = span("s2", "d1", "first-name", "PENDING", secondStart, secondEnd, "alpha");
        Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source, existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(1, result.get("approved"));
        // The existing span should now be APPROVED with type aligned to source.
        assertEquals("APPROVED", existing.getStatus());
        assertEquals("person", existing.getType());
    }

    @Test
    void redactAllLikeSkipsOverlappingNonExactRanges() {
        // Source span "abc" appears once; another match "abc" overlaps with an
        // existing-but-non-exact span occupying [5, 10] inside that area.
        String text = "abc xx abcxxx";
        Span source = span("s1", "d1", "person", "PENDING", 0, 3, "abc");
        // Existing span covers offsets 5..10 (overlapping the second "abc" at 7..10)
        Span overlapping = span("s2", "d1", "other", "PENDING", 5, 10, "x abcx");
        Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new ArrayList<>(List.of(source, overlapping)));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // The second "abc" overlaps an existing range, so neither new span nor flip.
        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository, never()).saveAll(anyList());
    }

    // ---- group-access enforcement (non-admin) ----

    /**
     * Wires up document "d1" → batch "b1" → group "g1" so the access helper has
     * something to compare against. The non-admin user is created by the caller.
     */
    private void seedGroupedDocument() {
        Document doc = document("d1", "");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
    }

    @Test
    void getSpansForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getSpansAllowsNonAdminInGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));
        when(spanRepository.findByDocumentId("d1"))
                .thenReturn(List.of(span("s1", "d1", "ssn", "PENDING", 0, 5, "hello")));

        List<Span> result = controller.getSpans("d1", TestAuth.user("alice@example.com"));
        assertEquals(1, result.size());
    }

    @Test
    void updateSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void redactAllLikeForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void createSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSpan("d1",
                        new ReviewController.CreateSpanRequest("ssn", 0, 5),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- deleteSpan ----

    @Test
    void deleteSpanRejectsUnknown() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteSpanRejectsDetectionSpan() {
        Span detected = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        // manuallyCreated defaults to false → detection-style span
        when(spanRepository.findById("s1")).thenReturn(Optional.of(detected));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void deleteSpanRemovesManualSpan() {
        Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        Map<String, Object> result = controller.deleteSpan("s1", ADMIN);
        assertEquals("s1", result.get("id"));
        assertEquals(true, result.get("deleted"));
        verify(spanRepository).deleteById("s1");
        assertTrue(auditLogService.hasAction("SPAN_DELETE"));
    }

    @Test
    void deleteSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void createSpanMarksManuallyCreated() {
        Document doc = document("d1", "Hello world, this is a sample document.");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        Span result = controller.createSpan("d1",
                new ReviewController.CreateSpanRequest("ssn", 0, 5),
                ADMIN);
        assertTrue(result.isManuallyCreated());
        assertEquals(1.0, result.getConfidence());
        assertEquals("APPROVED", result.getStatus());
    }

    @Test
    void documentEndpointsForbidWhenBatchHasNoGroup() {
        // Document exists but its batch has no groupId — non-admins are blocked.
        Document doc = document("d1", "");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        Batch batch = new Batch();
        batch.setId("b1");
        // groupId left null
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
