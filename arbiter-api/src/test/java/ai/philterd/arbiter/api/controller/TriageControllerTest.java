package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.ApprovalRuleEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriageControllerTest {

    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private SpanRepository spanRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TriageController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        spanRepository = mock(SpanRepository.class);
        when(spanRepository.findByDocumentId(anyString())).thenReturn(List.of());
        userGroupsService = TestDoubles.userGroups();
        controller = new TriageController(documentRepository, batchRepository, spanRepository,
                userGroupsService, new ApprovalRuleEvaluator());
    }

    private static Batch batch(final String id, final String groupId, final String name, final double documentThreshold) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        b.setDocumentThreshold(documentThreshold);
        return b;
    }

    private static Document doc(final String id, final String batchId, final String status, final double risk) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setStatus(status);
        d.setRiskScore(risk);
        d.setFilename(id + ".txt");
        return d;
    }

    @Test
    void unauthenticatedRequestWithRestrictReturnsEmpty() {
        // FakeUserGroups returns empty by default → restrict, allowed empty.
        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", null);

        assertTrue(page.getContent().isEmpty());
        verify(documentRepository, never()).findAll((PageRequest) any());
    }

    @Test
    void adminWithMyGroupsOnlyFalseSeesEverything() {
        final Document d = doc("d1", "b1", "REVIEW_REQUIRED", 0.5);
        when(documentRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(d), PageRequest.of(0, 10), 1));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "Batch One", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.admin("admin@example.com"));

        assertEquals(1, page.getContent().size());
        assertEquals("Batch One", page.getContent().get(0).get("batchName"));
    }

    @Test
    void invalidSortFallsBackToRiskScore() {
        when(documentRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        controller.getQueue(0, 10, null, null, null, false, "evil-injection", "asc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findAll(captor.capture());
        final Sort sort = captor.getValue().getSort();
        final Sort.Order order = sort.getOrderFor("riskScore");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void ascDirectionRespected() {
        when(documentRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        controller.getQueue(0, 10, null, null, null, false, "filename", "asc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findAll(captor.capture());
        final Sort.Order order = captor.getValue().getSort().getOrderFor("filename");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void nonAdminFiltersToAllowedBatches() {
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(batchRepository.findAll())
                .thenReturn(List.of(batch("b1", "g1", "Yours", 0.25), batch("b2", "g2", "NotYours", 0.25)));
        when(documentRepository.findByBatchIdIn(eq(Set.of("b1")), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(doc("d1", "b1", "PENDING", 0.1)),
                        PageRequest.of(0, 10), 1));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "Yours", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.user("alice@example.com"));

        assertEquals(1, page.getContent().size());
        verify(documentRepository).findByBatchIdIn(eq(Set.of("b1")), any(PageRequest.class));
    }

    @Test
    void nonAdminAskingForBatchOutsideGroupsGetsEmpty() {
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(batchRepository.findAll())
                .thenReturn(List.of(batch("b1", "g1", "Yours", 0.25), batch("b2", "g2", "Theirs", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, "b2", null, null, false, "riskScore", "desc", TestAuth.user("alice@example.com"));

        assertTrue(page.getContent().isEmpty());
        verify(documentRepository, never()).findByBatchId(anyString(), any(PageRequest.class));
    }

    @Test
    void autoApprovedFlagFlipsAtThreshold() {
        final Document below = doc("d1", "b1", "REVIEW_REQUIRED", 0.10);
        final Document above = doc("d2", "b1", "REVIEW_REQUIRED", 0.50);
        final Document approved = doc("d3", "b1", "APPROVED", 0.05); // user-decided wins
        when(documentRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(below, above, approved), PageRequest.of(0, 10), 3));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "B", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.admin("admin@example.com"));

        assertEquals(true, page.getContent().get(0).get("autoApproved"));
        assertEquals(false, page.getContent().get(1).get("autoApproved"));
        assertEquals(false, page.getContent().get(2).get("autoApproved")); // already APPROVED
    }

    @Test
    void getBatchesAdminUnscoped() {
        when(batchRepository.findAll())
                .thenReturn(List.of(batch("b2", "g1", "Zebra", 0.25), batch("b1", "g1", "Alpha", 0.25)));

        final List<Map<String, String>> result = controller.getBatches(false, TestAuth.admin("admin@example.com"));

        // Sorted alphabetically by name.
        assertEquals(2, result.size());
        assertEquals("Alpha", result.get(0).get("name"));
        assertEquals("Zebra", result.get(1).get("name"));
    }

    @Test
    void getBatchesNonAdminFilteredToGroups() {
        when(batchRepository.findAll())
                .thenReturn(List.of(batch("b1", "g1", "Mine", 0.25), batch("b2", "g2", "NotMine", 0.25)));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final List<Map<String, String>> result = controller.getBatches(false, TestAuth.user("alice@example.com"));

        assertEquals(1, result.size());
        assertEquals("Mine", result.get(0).get("name"));
        assertFalse(result.stream().anyMatch(m -> "NotMine".equals(m.get("name"))));
    }
}
