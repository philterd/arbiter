/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Correctness tests for the Inter-Annotator Agreement (Cohen's Kappa) report on the Reports
 * page. Each test constructs documents with a hand-built pair of reviewer span snapshots
 * that produce a known confusion matrix when tokenized on whitespace, then drives the
 * {@link ReportingController} and asserts on the {@code iaaRows} model attribute.
 *
 * The corpus uses ten 5-letter tokens separated by single spaces:
 *
 * <pre>
 *   "alpha bravo carry delta extra foxes golfs hotel india jolly"
 *    0     6     12    18    24    30    36    42    48    54
 * </pre>
 *
 * Each token spans 5 characters; the separators are single spaces, so token {@code k}
 * (0-indexed) lives at character offsets {@code [6k, 6k + 5)}. Spans of width 5 starting at
 * those offsets cover exactly one token, which makes constructing arbitrary confusion
 * matrices straightforward.
 */
class InterAnnotatorAgreementReportIntegrationTest {

    /** Ten 5-letter tokens, single-space separated. */
    private static final String CORPUS = "alpha bravo carry delta extra foxes golfs hotel india jolly";

    private final Map<String, List<Document>> docsByBatch = new HashMap<>();
    private final List<Batch> batches = new ArrayList<>();

    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private ReportingController controller;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        final PhilterInstanceRepository philterInstanceRepository = mock(PhilterInstanceRepository.class);
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

        when(batchRepository.findAll(any(PageRequest.class)))
                .thenAnswer(inv -> new PageImpl<>(batches, inv.getArgument(0, PageRequest.class), batches.size()));
        when(documentRepository.findByBatchId(anyString()))
                .thenAnswer(inv -> docsByBatch.getOrDefault(inv.getArgument(0, String.class), List.of()));
        when(philterInstanceRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(spanRepository.countByDocumentIdInAndStatus(any(), anyString())).thenReturn(0L);
        when(spanRepository.countByDocumentIdInAndManuallyCreated(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(0L);
        when(auditLogRepository.findByTimestampBetweenAndActionIn(any(), any(), any()))
                .thenReturn(List.of());

        controller = new ReportingController(batchRepository, documentRepository, spanRepository,
                philterInstanceRepository, userGroupsService, auditLogRepository);
    }

    // ---------- canonical confusion matrices ----------

    @Test
    void perfectAgreementOnAllTokensProducesKappaOne() {
        // Both reviewers labeled every token PII. po=1, pe=1 — degenerate; Arbiter reports 1.0.
        addBatchWithDocument("b-perfect", labels("PPPPPPPPPP"), labels("PPPPPPPPPP"));
        final Map<String, Object> row = runIaaForBatch("b-perfect");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9, "Perfect PII-on-every-token agreement: kappa = 1.0.");
    }

    @Test
    void perfectAgreementOnAllOTokensProducesKappaOneByConvention() {
        // Both reviewers labeled every token O. po=1, pe=1 — degenerate (formula is 0/0).
        // Arbiter convention: unanimous agreement on a single class is reported as 1.0.
        addBatchWithDocument("b-allO", labels("OOOOOOOOOO"), labels("OOOOOOOOOO"));
        final Map<String, Object> row = runIaaForBatch("b-allO");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9,
                "All tokens O for both reviewers is unanimous agreement and should report 1.0.");
    }

    @Test
    void totalDisagreementProducesKappaNegative() {
        // Reviewer A labels 5 PII / 5 O; Reviewer B labels the opposite — every disagreement.
        // a=0, b=5, c=5, d=0; n=10.
        //  po = 0; pe = (5/10)*(5/10)+(5/10)*(5/10) = 0.5; kappa = (0 - 0.5)/(1 - 0.5) = -1.0.
        addBatchWithDocument("b-disagree", labels("PPPPPOOOOO"), labels("OOOOOPPPPP"));
        final Map<String, Object> row = runIaaForBatch("b-disagree");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(-1.0, kappa(row), 1e-9,
                "Mirror-image labeling is total disagreement: kappa = -1.0.");
    }

    @Test
    void chanceLevelAgreementProducesKappaZero() {
        // Construct a matrix where po == pe, giving kappa = 0:
        //   a = 4, b = 4, c = 1, d = 1; n = 10.
        //   po = (4 + 1) / 10 = 0.5
        //   pe = ((4+4)/10) * ((4+1)/10) + ((1+1)/10) * ((4+1)/10)
        //      = 0.8 * 0.5 + 0.2 * 0.5 = 0.5
        //   kappa = (0.5 - 0.5) / (1 - 0.5) = 0
        // Realize that matrix:
        //   tokens 0..3 PII/PII (a=4) — both label PII
        //   tokens 4..7 PII/O   (b=4) — first PII, second O
        //   token  8    O/PII   (c=1) — first O, second PII
        //   token  9    O/O     (d=1) — both O
        addBatchWithDocument("b-chance", labels("PPPPPPPPOO"), labels("PPPPOOOOPO"));
        final Map<String, Object> row = runIaaForBatch("b-chance");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(0.0, kappa(row), 1e-9, "Above-chance reduces to zero when po equals pe.");
    }

    @Test
    void substantialAgreementProducesKappaZeroPointSix() {
        // Construct a familiar 0.6 — the canonical "substantial agreement" textbook example:
        //   a = 4, b = 1, c = 1, d = 4; n = 10.
        //   po = (4+4)/10 = 0.8
        //   pPii_1 = 5/10; pPii_2 = 5/10; pO_1 = 5/10; pO_2 = 5/10
        //   pe = 0.5*0.5 + 0.5*0.5 = 0.5
        //   kappa = (0.8 - 0.5) / (1 - 0.5) = 0.6
        addBatchWithDocument("b-0p6",
                labels("PPPPPOOOOO"),  // 5 PII, 5 O
                labels("PPPPOPOOOO")); // tokens 0..3 PII, 4 O, 5 PII, rest O
        final Map<String, Object> row = runIaaForBatch("b-0p6");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(0.6, kappa(row), 1e-9,
                "a=4,b=1,c=1,d=4 must produce kappa = 0.6.");
    }

    // ---------- pooling and selection ----------

    @Test
    void kappaIsPooledAcrossEveryDoubleReviewedDocumentInTheBatch() {
        // Two documents in the same batch. Their per-doc decisions combine into a single
        // batch-level confusion matrix:
        //   doc-a: a=4, b=1, c=1, d=4 (the 0.6 textbook case from above)
        //   doc-b: a=4, b=4, c=1, d=1 (the 0.0 chance case from above)
        // Pooled: a=8, b=5, c=2, d=5; n=20.
        //   po  = (8+5)/20 = 0.65
        //   pe  = ((8+5)/20)*((8+2)/20) + ((2+5)/20)*((5+5)/20)
        //       = 0.65*0.5 + 0.35*0.5 = 0.5
        //   kappa = (0.65 - 0.50) / (1 - 0.50) = 0.30
        final Batch b = batch("b-pool");
        addDoubleReviewDoc(b, "doc-a", labels("PPPPPOOOOO"), labels("PPPPOPOOOO"));
        addDoubleReviewDoc(b, "doc-b", labels("PPPPPPPPOO"), labels("PPPPOOOOPO"));

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(20L, row.get("totalTokens"),
                "Pooled token count should be the sum across all double-reviewed documents.");
        assertEquals(2L, row.get("doubleReviewedDocs"));
        assertEquals(0.30, kappa(row), 1e-9,
                "Batch kappa is computed on the pooled 2x2 matrix, not as an average of per-doc kappas.");
    }

    @Test
    void documentsMissingASnapshotAreExcludedFromTheBatchKappa() {
        // Three documents in one batch. The third has no second-reviewer snapshot yet — it
        // should not contribute to the pooled matrix. The remaining two are the same pair as
        // the pooled-test above, producing kappa = 0.30.
        final Batch b = batch("b-mixed");
        addDoubleReviewDoc(b, "doc-a", labels("PPPPPOOOOO"), labels("PPPPOPOOOO"));
        addDoubleReviewDoc(b, "doc-b", labels("PPPPPPPPOO"), labels("PPPPOOOOPO"));
        // doc-c was selected for double review and Alice reviewed first, but Bob hasn't yet.
        final Document partial = baseDoubleReviewDoc("doc-c", b.getId());
        partial.setFirstReviewSpans(piiSpansForLabels(labels("PPPPPOOOOO")));
        partial.setSecondReviewSpans(null);
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(partial);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(20L, row.get("totalTokens"),
                "A document missing the second snapshot must be excluded from the pooled count.");
        assertEquals(2L, row.get("doubleReviewedDocs"));
        assertEquals(0.30, kappa(row), 1e-9);
    }

    @Test
    void batchWithoutBlindDoubleReviewEnabledIsNotIncludedInTheReport() {
        // Even if a document somehow has both snapshots (legacy data, manual edit, etc.),
        // a batch that does NOT have Blind Double Review enabled should not appear in
        // iaaRows at all.
        final Batch ordinary = new Batch();
        ordinary.setId("b-ordinary");
        ordinary.setName("Plain batch");
        ordinary.setBlindDoubleReviewEnabled(false);
        batches.add(ordinary);
        addDoubleReviewDoc(ordinary, "stray", labels("PPPPPPPPPP"), labels("PPPPPPPPPP"));

        final List<Map<String, Object>> rows = runIaa();
        assertTrue(rows.stream().noneMatch(r -> "b-ordinary".equals(r.get("batchId"))),
                "Batches without Blind Double Review must not appear on the IAA card.");
    }

    @Test
    void batchWithFeatureEnabledButNoCompletedDoubleReviewsReportsNullKappa() {
        // The batch is on the report, but with no doubly-reviewed docs the kappa column is
        // null (rendered as "— not enough data —" in the template).
        batch("b-empty");
        final Map<String, Object> row = runIaaForBatch("b-empty");
        assertEquals(0L, row.get("totalTokens"));
        assertEquals(0L, row.get("doubleReviewedDocs"));
        assertNull(row.get("kappa"),
                "An enabled batch with zero doubly-reviewed documents must report kappa = null.");
    }

    @Test
    void documentsThatWereNotSelectedForDoubleReviewAreIgnored() {
        // The batch is on the report, has a couple of completed reviews, but the documents
        // were not flagged for double review — they shouldn't contribute even if their
        // snapshot fields somehow have values.
        final Batch b = batch("b-not-selected");
        final Document d = baseDoubleReviewDoc("d-not-flagged", b.getId());
        d.setDoubleReview(false);
        d.setFirstReviewSpans(piiSpansForLabels(labels("PPPPPPPPPP")));
        d.setSecondReviewSpans(piiSpansForLabels(labels("OOOOOOOOOO")));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(0L, row.get("totalTokens"),
                "Documents with doubleReview=false must not contribute even when both snapshots exist.");
        assertEquals(0L, row.get("doubleReviewedDocs"));
    }

    // ---------- text and span edge cases ----------

    @Test
    void documentWithNullOriginalTextIsExcluded() {
        // Without text we cannot tokenize, so the document must not contribute to the
        // pooled matrix even when both snapshots are populated.
        final Batch b = batch("b-null-text");
        final Document d = baseDoubleReviewDoc("d-null", b.getId());
        d.setOriginalText(null);
        d.setFirstReviewSpans(piiSpansForLabels(labels("PPPPPPPPPP")));
        d.setSecondReviewSpans(piiSpansForLabels(labels("PPPPPPPPPP")));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(0L, row.get("totalTokens"),
                "A document with null text must contribute zero tokens to the pooled matrix.");
        assertEquals(0L, row.get("doubleReviewedDocs"));
        assertNull(row.get("kappa"));
    }

    @Test
    void documentWithEmptyOriginalTextIsExcluded() {
        final Batch b = batch("b-empty-text");
        final Document d = baseDoubleReviewDoc("d-empty", b.getId());
        d.setOriginalText("");
        d.setFirstReviewSpans(new ArrayList<>());
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(0L, row.get("totalTokens"));
        assertEquals(0L, row.get("doubleReviewedDocs"));
        assertNull(row.get("kappa"));
    }

    @Test
    void oneReviewerWithNoSpansLabelsEveryTokenAsO() {
        // Reviewer A approved every token as PII; Reviewer B approved nothing. The 10-token
        // matrix is a=0, b=10, c=0, d=0; n=10.
        //   po = 0; pe = (10/10)*(0/10) + (0/10)*(10/10) = 0; kappa = (0 - 0)/(1 - 0) = 0
        addBatchWithDocument("b-asym",
                labels("PPPPPPPPPP"),
                labels("OOOOOOOOOO"));
        final Map<String, Object> row = runIaaForBatch("b-asym");
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(0.0, kappa(row), 1e-9,
                "When one reviewer approves everything and the other approves nothing, "
                        + "po and pe both collapse to 0 and kappa is 0.");
    }

    @Test
    void wideSpanLabelsEveryTokenItOverlaps() {
        // A single very wide span on the first reviewer's side covers tokens 0..4 (alpha
        // through extra). The second reviewer left an identical narrow span per token.
        // Both reviewers must agree on those five tokens.
        final Batch b = batch("b-wide");
        final Document d = baseDoubleReviewDoc("d-wide", b.getId());
        // Wide span: characters 0..29 — spans tokens 0..4 (alpha bravo carry delta extra).
        d.setFirstReviewSpans(new ArrayList<>(List.of(new int[]{0, 29})));
        d.setSecondReviewSpans(piiSpansForLabels(labels("PPPPPOOOOO")));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(10L, row.get("totalTokens"));
        // a=5 both PII, b=0, c=0, d=5 both O → po=1, kappa=1.
        assertEquals(1.0, kappa(row), 1e-9,
                "A single wide span must label every token it overlaps as PII.");
    }

    @Test
    void partialOverlapAtTheTokenBoundaryStillCountsAsPii() {
        // First reviewer's span covers chars 4..6 — the last char of token 0 ("alpha", at
        // [0,5)) and the first char of token 1 ("bravo", at [6,11)). The implementation
        // treats partial overlap as PII (conservative for compliance), so token 0 is PII
        // and token 1 is PII even though only one character of each is actually inside
        // the span. The other reviewer left no spans.
        final Batch b = batch("b-partial");
        final Document d = baseDoubleReviewDoc("d-partial", b.getId());
        d.setFirstReviewSpans(new ArrayList<>(List.of(new int[]{4, 7})));
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(10L, row.get("totalTokens"));
        // First labels tokens 0 and 1 PII; second labels nothing PII.
        // a=0, b=2, c=0, d=8.
        //  po = 8/10 = 0.8
        //  pe = (2/10)*(0/10) + (8/10)*(10/10) = 0 + 0.8 = 0.8
        //  kappa = (0.8 - 0.8) / (1 - 0.8) = 0
        assertEquals(0.0, kappa(row), 1e-9,
                "Partial overlap on the token edge counts as PII for both edge tokens.");
    }

    @Test
    void zeroLengthSpanIsNoOp() {
        // A span where start == end covers no characters, so it must not promote any token
        // to PII. Both reviewers leave such spans; both their effective label vectors are
        // all-O and the kappa convention is 1.0.
        final Batch b = batch("b-zero");
        final Document d = baseDoubleReviewDoc("d-zero", b.getId());
        d.setFirstReviewSpans(new ArrayList<>(List.of(new int[]{0, 0})));
        d.setSecondReviewSpans(new ArrayList<>(List.of(new int[]{6, 6})));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9,
                "Zero-length spans must label nothing — both reviewers see an all-O document.");
    }

    @Test
    void multipleConsecutiveWhitespaceCharactersDoNotProduceEmptyTokens() {
        // The tokenizer skips runs of whitespace, so "alpha   bravo" still yields exactly
        // two tokens regardless of how many spaces separate them. Set up text with extra
        // whitespace and confirm the token count is what we expect.
        final Batch b = batch("b-ws");
        final Document d = baseDoubleReviewDoc("d-ws", b.getId());
        d.setOriginalText("alpha   bravo\tcarry\n\ndelta");  // 4 tokens
        d.setFirstReviewSpans(new ArrayList<>());
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(4L, row.get("totalTokens"),
                "Runs of whitespace must collapse — only non-whitespace characters form tokens.");
        // Both all-O → degenerate kappa = 1.0 by convention.
        assertEquals(1.0, kappa(row), 1e-9);
    }

    @Test
    void leadingAndTrailingWhitespaceProducesNoExtraTokens() {
        final Batch b = batch("b-edge");
        final Document d = baseDoubleReviewDoc("d-edge", b.getId());
        d.setOriginalText("   alpha bravo   ");  // 2 tokens
        d.setFirstReviewSpans(new ArrayList<>());
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(2L, row.get("totalTokens"),
                "Leading/trailing whitespace must not be tokenized into empty tokens.");
    }

    @Test
    void whitespaceOnlyTextProducesNoTokens() {
        // A document containing only spaces and tabs should produce zero tokens — it
        // should not contribute to the matrix and must not crash the kappa computation.
        final Batch b = batch("b-blank");
        final Document d = baseDoubleReviewDoc("d-blank", b.getId());
        d.setOriginalText("   \t\n   ");
        d.setFirstReviewSpans(new ArrayList<>());
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(0L, row.get("totalTokens"),
                "A whitespace-only document must produce zero tokens.");
    }

    @Test
    void nullSpanEntryDoesNotCrashTheTokenizer() {
        // Defensive: if a malformed snapshot somehow contains a null entry, the tokenizer
        // must skip it instead of throwing a NullPointerException.
        final Batch b = batch("b-nulls");
        final Document d = baseDoubleReviewDoc("d-nulls", b.getId());
        final ArrayList<int[]> first = new ArrayList<>();
        first.add(null);
        first.add(new int[]{0, 5}); // legitimately covers token 0
        d.setFirstReviewSpans(first);
        d.setSecondReviewSpans(piiSpansForLabels(labels("POOOOOOOOO")));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        // Both reviewers label only token 0 as PII → unanimous agreement → kappa = 1.
        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9,
                "A null entry inside the span list must be ignored, not crash the tokenizer.");
    }

    @Test
    void firstReviewSnapshotPresentButSecondMissingProducesNotEnoughData() {
        // Second reviewer hasn't acted yet — only firstReviewSpans is populated. The doc
        // must contribute no tokens; the batch's kappa is null (rendered as
        // "— not enough data —") since no doubly-reviewed document exists yet.
        final Batch b = batch("b-only-first");
        final Document d = baseDoubleReviewDoc("d-only-first", b.getId());
        d.setSecondReviewer(null);
        d.setFirstReviewSpans(piiSpansForLabels(labels("PPPPPOOOOO")));
        d.setSecondReviewSpans(null);
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(0L, row.get("doubleReviewedDocs"));
        assertEquals(0L, row.get("totalTokens"));
        assertNull(row.get("kappa"),
                "A batch where every document is only half-reviewed must report null kappa.");
    }

    @Test
    void emptySnapshotListsAreTreatedAsAllOLabels() {
        // Both reviewers present and both completed their review, but neither approved any
        // span. Effective label vectors are all-O for both → degenerate kappa = 1.0.
        final Batch b = batch("b-noPII");
        final Document d = baseDoubleReviewDoc("d-noPII", b.getId());
        d.setFirstReviewSpans(new ArrayList<>());
        d.setSecondReviewSpans(new ArrayList<>());
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(10L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9,
                "Two empty snapshots are unanimous on every token being O → kappa = 1.0.");
    }

    @Test
    void singleTokenDocumentIsHandled() {
        final Batch b = batch("b-single");
        final Document d = baseDoubleReviewDoc("d-single", b.getId());
        d.setOriginalText("alpha");
        d.setFirstReviewSpans(new ArrayList<>(List.of(new int[]{0, 5})));
        d.setSecondReviewSpans(new ArrayList<>(List.of(new int[]{0, 5})));
        docsByBatch.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(d);

        final Map<String, Object> row = runIaaForBatch(b.getId());
        assertEquals(1L, row.get("totalTokens"));
        assertEquals(1.0, kappa(row), 1e-9);
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runIaa() {
        final ConcurrentModel model = new ConcurrentModel();
        controller.view(null, null, null, admin(), model);
        final Object iaa = model.getAttribute("iaaRows");
        assertNotNull(iaa, "ReportingController must always populate the iaaRows model attribute.");
        return (List<Map<String, Object>>) iaa;
    }

    private Map<String, Object> runIaaForBatch(final String batchId) {
        final List<Map<String, Object>> rows = runIaa();
        return rows.stream()
                .filter(r -> batchId.equals(r.get("batchId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an IAA row for batch " + batchId
                        + "; got: " + rows));
    }

    private static double kappa(final Map<String, Object> row) {
        final Object k = row.get("kappa");
        assertNotNull(k, "kappa was null on row " + row);
        return ((Number) k).doubleValue();
    }

    private Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        b.setName(id);
        b.setBlindDoubleReviewEnabled(true);
        b.setBlindDoubleReviewPercentage(100);
        batches.add(b);
        return b;
    }

    private void addBatchWithDocument(final String batchId, final boolean[] firstLabels, final boolean[] secondLabels) {
        final Batch b = batch(batchId);
        addDoubleReviewDoc(b, batchId + "-doc", firstLabels, secondLabels);
    }

    private void addDoubleReviewDoc(final Batch parent, final String docId,
                                    final boolean[] firstLabels, final boolean[] secondLabels) {
        final Document d = baseDoubleReviewDoc(docId, parent.getId());
        d.setFirstReviewSpans(piiSpansForLabels(firstLabels));
        d.setSecondReviewSpans(piiSpansForLabels(secondLabels));
        docsByBatch.computeIfAbsent(parent.getId(), k -> new ArrayList<>()).add(d);
    }

    private static Document baseDoubleReviewDoc(final String id, final String batchId) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setStatus("APPROVED");
        d.setFilename(id + ".txt");
        d.setOriginalText(CORPUS);
        d.setDoubleReview(true);
        d.setFirstReviewer("alice@x.com");
        d.setSecondReviewer("bob@x.com");
        return d;
    }

    /**
     * Convert a per-token boolean label vector ({@code true = PII}, {@code false = O}) into
     * a list of {@code [start, end]} character ranges suitable for {@code firstReviewSpans} /
     * {@code secondReviewSpans}. Each true entry contributes one 5-character span over its
     * corresponding token in the {@link #CORPUS} text.
     */
    private static List<int[]> piiSpansForLabels(final boolean[] labels) {
        final List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i]) {
                final int start = i * 6;
                ranges.add(new int[]{start, start + 5});
            }
        }
        return ranges;
    }

    /**
     * Compact label-string parser. {@code "PPPPPOOOOO"} yields {@code [true,...×5, false,...×5]}.
     * Length must equal the number of tokens in {@link #CORPUS} (10) so callers don't silently
     * mis-align labels and tokens.
     */
    private static boolean[] labels(final String s) {
        if (s.length() != 10) {
            throw new IllegalArgumentException("Label string must be exactly 10 characters; got " + s.length());
        }
        final boolean[] out = new boolean[10];
        for (int i = 0; i < 10; i++) {
            final char c = s.charAt(i);
            if (c == 'P') out[i] = true;
            else if (c == 'O') out[i] = false;
            else throw new IllegalArgumentException("Label characters must be P or O; got '" + c + "'");
        }
        return out;
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
