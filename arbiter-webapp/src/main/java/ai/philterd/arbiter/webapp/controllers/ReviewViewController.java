/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.FinalizationPolicyRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.model.UserSettings;
import ai.philterd.arbiter.service.ApprovalRuleEvaluator;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.DocumentAccessService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.service.RedactionCertificateService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Set;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class ReviewViewController {

    /**
     * Pick the comparator that orders Previous/Next sibling navigation on the Review page.
     * Driven by the reviewer's UserSettings.reviewSortBy. Highest priority/risk first to
     * surface the most-actionable docs; alphabetical for filename. All comparators tie-break
     * on document id so order is stable across page loads.
     */
    private static Comparator<Document> batchOrder(final UserSettings settings) {
        final String sortBy = settings == null ? null : settings.getReviewSortBy();
        final Comparator<Document> primary;
        if (UserSettings.SORT_PRIORITY.equals(sortBy)) {
            primary = (a, b) -> Integer.compare(b.getPriority(), a.getPriority());
        } else if (UserSettings.SORT_FILENAME.equals(sortBy)) {
            primary = Comparator.comparing(d -> d.getFilename() == null ? "" : d.getFilename().toLowerCase());
        } else {
            primary = (a, b) -> Double.compare(b.getRiskScore(), a.getRiskScore());
        }
        return primary.thenComparing(d -> d.getId() == null ? "" : d.getId());
    }

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final UserGroupsService userGroupsService;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final UserSettingsService userSettingsService;
    private final UserRepository userRepository;
    private final ApprovalRuleEvaluator approvalRuleEvaluator;
    private final OpenSearchIndexService openSearchIndexService;
    private final ai.philterd.arbiter.service.DocumentLockService documentLockService;
    private final RedactionCertificateService redactionCertificateService;
    private final FinalizationPolicyRepository finalizationPolicyRepository;

    public ReviewViewController(final DocumentRepository documentRepository,
                                final SpanRepository spanRepository,
                                final BatchRepository batchRepository,
                                final ComplianceProfileRepository complianceProfileRepository,
                                final UserGroupsService userGroupsService,
                                final DocumentAccessService documentAccessService,
                                final AuditLogService auditLogService,
                                final OllamaInstanceRepository ollamaInstanceRepository,
                                final LlmJudgeDefaultsService llmJudgeDefaultsService,
                                final UserSettingsService userSettingsService,
                                final UserRepository userRepository,
                                final ApprovalRuleEvaluator approvalRuleEvaluator,
                                final OpenSearchIndexService openSearchIndexService,
                                final ai.philterd.arbiter.service.DocumentLockService documentLockService,
                                final RedactionCertificateService redactionCertificateService,
                                final FinalizationPolicyRepository finalizationPolicyRepository) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.complianceProfileRepository = complianceProfileRepository;
        this.userGroupsService = userGroupsService;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.userSettingsService = userSettingsService;
        this.userRepository = userRepository;
        this.approvalRuleEvaluator = approvalRuleEvaluator;
        this.openSearchIndexService = openSearchIndexService;
        this.documentLockService = documentLockService;
        this.redactionCertificateService = redactionCertificateService;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
    }


    @GetMapping("/review/{documentId}")
    public String review(@PathVariable final String documentId, final Authentication authentication, final Model model) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (document.getOriginalText() == null || document.getOriginalText().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document source has been deleted by the batch's finalization policy "
                            + "and is no longer available for review.");
        }

        // Pessimistic lock acquisition. Atomic findAndModify guarantees only one reviewer
        // gets a fresh lock; other openers see read-only mode with a banner naming the
        // current holder. Admins can still open in read-only and choose to break the lock.
        final String email = authentication == null ? null : authentication.getName();
        final Document acquired = email == null ? null
                : documentLockService.acquire(documentId, email);
        boolean lockedByOther = false;
        String lockHolder = null;
        java.time.Instant lockExpiresAt = null;
        if (acquired != null) {
            document = acquired;
            lockHolder = acquired.getLockedBy();
            lockExpiresAt = acquired.getLockExpiresAt();
        } else {
            // Re-fetch to read who currently holds the lock.
            document = documentRepository.findById(documentId).orElse(document);
            if (document.isLocked(java.time.Instant.now())
                    && (email == null || !email.equalsIgnoreCase(document.getLockedBy()))) {
                lockedByOther = true;
                lockHolder = document.getLockedBy();
                lockExpiresAt = document.getLockExpiresAt();
            }
        }

        final String originalText = document.getOriginalText() == null ? "" : document.getOriginalText();

        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        spans.sort(Comparator.comparingInt(s -> s.getLocation().characterStart()));

        final StringBuilder redactedBuilder = new StringBuilder();
        final List<Map<String, Object>> originalRedactions = new ArrayList<>();
        final List<Map<String, Object>> redactedRedactions = new ArrayList<>();

        int cursor = 0;
        for (Span span : spans) {
            final int start = span.getLocation().characterStart();
            final int end = span.getLocation().characterEnd();
            if (start < cursor || end > originalText.length() || start > end) {
                continue;
            }
            redactedBuilder.append(originalText, cursor, start);

            final String replacement = "<<" + span.getType().toUpperCase() + ">>";
            final int newStart = redactedBuilder.length();
            redactedBuilder.append(replacement);
            final int newEnd = redactedBuilder.length();

            originalRedactions.add(redactionEntry(span, start, end));
            redactedRedactions.add(redactionEntry(span, newStart, newEnd));

            cursor = end;
        }
        redactedBuilder.append(originalText, cursor, originalText.length());

        final List<Map<String, String>> piiTypes = new ArrayList<>();
        PiiTypes.labels().forEach((value, label) -> {
            final Map<String, String> entry = new LinkedHashMap<>();
            entry.put("value", value);
            entry.put("label", label);
            piiTypes.add(entry);
        });

        final LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        String defaultExplainInstanceId = defaults.getExplainInstanceId();
        String defaultExplainModel = defaults.getExplainModel();
        final boolean secondOpinionConfigured = defaults.getSecondOpinionInstanceId() != null;

        final List<Map<String, Object>> ollamaInstances = new ArrayList<>();
        final List<OllamaInstance> allInstances = ollamaInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        boolean explainInstanceStillExists = false;
        for (OllamaInstance i : allInstances) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", i.getId());
            entry.put("name", i.getName());
            ollamaInstances.add(entry);
            if (defaultExplainInstanceId != null && defaultExplainInstanceId.equals(i.getId())) {
                explainInstanceStillExists = true;
            }
        }
        if (!explainInstanceStillExists) {
            defaultExplainInstanceId = null;
            defaultExplainModel = null;
        }

        final String currentEmail = authentication == null ? null : authentication.getName();
        final UserSettings settings = userSettingsService.loadForEmail(currentEmail);
        final String prevDocumentId = findSiblingId(document, settings, -1, currentEmail);
        final String nextDocumentId = findSiblingId(document, settings, 1, currentEmail);

        // "Document X of Y" counter: Y = pending (not approved/rejected) docs in the batch,
        // X = 1-based position of the current doc within that sorted pending set.
        final List<Document> batchDocs = document.getBatchId() == null
                ? List.of() : documentRepository.findByBatchId(document.getBatchId());
        batchDocs.sort(batchOrder(settings));
        final List<Document> pendingDocs = batchDocs.stream()
                .filter(d -> !isAcceptedOrRejected(d.getStatus()))
                .collect(java.util.stream.Collectors.toList());
        int docPosition = 0;
        for (int i = 0; i < pendingDocs.size(); i++) {
            if (document.getId().equals(pendingDocs.get(i).getId())) {
                docPosition = i + 1;
                break;
            }
        }
        model.addAttribute("docPosition", docPosition);
        model.addAttribute("pendingCount", pendingDocs.size());

        model.addAttribute("document", document);
        model.addAttribute("originalText", originalText);
        model.addAttribute("redactedText", redactedBuilder.toString());
        model.addAttribute("originalRedactions", originalRedactions);
        model.addAttribute("redactedRedactions", redactedRedactions);
        model.addAttribute("piiTypes", piiTypes);
        model.addAttribute("ollamaInstances", ollamaInstances);
        model.addAttribute("defaultExplainInstanceId", defaultExplainInstanceId);
        model.addAttribute("defaultExplainModel", defaultExplainModel);
        model.addAttribute("secondOpinionConfigured", secondOpinionConfigured);
        model.addAttribute("prevDocumentId", prevDocumentId);
        model.addAttribute("nextDocumentId", nextDocumentId);
        model.addAttribute("currentUserEmail", authentication == null ? "" : authentication.getName());

        // Worst-case approvals required (queue-display semantics): doesn't depend on
        // who the eventual reviewer will be, so reviewers see the same number whether
        // they're inexperienced or not.
        final Batch approvalBatch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final int approvalsRequired = approvalRuleEvaluator.approvalsRequired(approvalBatch, document, spans);
        final int approvalsGiven = document.getApprovedBy().size();
        model.addAttribute("approvalsRequired", approvalsRequired);
        model.addAttribute("approvalsGiven", approvalsGiven);
        model.addAttribute("lockedByOther", lockedByOther);
        model.addAttribute("lockHolder", lockHolder == null ? "" : lockHolder);
        model.addAttribute("lockExpiresAt", lockExpiresAt);

        final ai.philterd.arbiter.model.ComplianceProfile complianceProfile =
                (approvalBatch == null || approvalBatch.getComplianceProfileId() == null) ? null
                : complianceProfileRepository.findById(approvalBatch.getComplianceProfileId()).orElse(null);
        final List<String> exemptionCodes = complianceProfile != null && complianceProfile.getExemptionCodes() != null
                ? complianceProfile.getExemptionCodes().stream()
                        .map(ai.philterd.arbiter.model.ExemptionCode::getCode)
                        .collect(java.util.stream.Collectors.toList())
                : List.of();
        final String complianceProfileName = complianceProfile != null ? complianceProfile.getName() : null;
        // The exemption-code prompt fires only when the batch requires it AND the compliance
        // profile actually defines codes. The batch flag defaults to true on existing rows
        // (the field's Java default), so prior batches keep their original behaviour.
        final boolean exemptionCodeRequired = approvalBatch != null
                && approvalBatch.isExemptionCodeRequired()
                && !exemptionCodes.isEmpty();
        model.addAttribute("exemptionCodes", exemptionCodes);
        model.addAttribute("exemptionCodeRequired", exemptionCodeRequired);
        model.addAttribute("complianceProfileName", complianceProfileName);
        model.addAttribute("batchName", approvalBatch == null ? "" : (approvalBatch.getName() == null ? "" : approvalBatch.getName()));
        model.addAttribute("batchDescription", approvalBatch == null ? "" : (approvalBatch.getDescription() == null ? "" : approvalBatch.getDescription()));

        return "review";
    }

    @PostMapping("/api/v1/review/{documentId}/pulse")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> pulse(@PathVariable final String documentId,
                                     final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        final Document doc = email == null ? null : documentLockService.pulse(documentId, email);
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (doc == null) {
            // Lock was lost (expired, broken, or held by someone else). The client should
            // refresh the review page so the user sees read-only mode.
            out.put("ok", false);
            out.put("reason", "LOCK_LOST");
            return out;
        }
        out.put("ok", true);
        out.put("lockExpiresAt", doc.getLockExpiresAt());
        return out;
    }

    @GetMapping("/api/v1/review/{documentId}/similar")
    @org.springframework.web.bind.annotation.ResponseBody
    public List<Map<String, Object>> findSimilar(@PathVariable final String documentId,
                                                  final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        // Verify the caller can access the source document's batch.
        documentAccessService.requireDocumentAccess(authentication, document);

        final OpenSearchIndexService.SearchResults results =
                openSearchIndexService.findSimilar(documentId, document.getBatchId(), 10);

        // Filter every returned hit against the caller's accessible batches. Admins see
        // all results; non-admins only see hits whose batchId is in one of their groups.
        // This is defense-in-depth: the OpenSearch query already scopes to the source
        // document's batchId (which requireDocumentAccess already validated), but explicit
        // per-hit filtering ensures the check holds even if the search logic changes.
        // Auditors join the cross-group view here — the similar-document widget is
        // a read, just like admin's similar view.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> allowedBatchIds = admin ? null : similarAllowedBatchIds(authentication);

        final java.util.Set<String> docIds = new java.util.HashSet<>();
        for (OpenSearchIndexService.SearchHit h : results.hits()) {
            if (h.id() != null && !h.id().isBlank()) docIds.add(h.id());
        }
        final Map<String, Document> liveDocs = new java.util.HashMap<>();
        for (Document d : documentRepository.findAllById(docIds)) {
            liveDocs.put(d.getId(), d);
        }

        final List<Map<String, Object>> out = new ArrayList<>();
        for (OpenSearchIndexService.SearchHit h : results.hits()) {
            if (!admin && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()))) {
                continue;
            }
            final Document live = liveDocs.get(h.id());
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.id());
            row.put("filename", h.filename());
            row.put("status", live != null && live.getStatus() != null ? live.getStatus() : h.status());
            row.put("batchId", h.batchId());
            out.add(row);
        }
        return out;
    }

    private Set<String> similarAllowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        final Set<String> ids = new java.util.HashSet<>();
        for (Batch b : batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    @PostMapping("/review/{documentId}/finalize")
    public String finalizeDocument(@PathVariable final String documentId,
                                   final Authentication authentication,
                                   final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/queue";
        }
        documentAccessService.requireDocumentAccess(authentication, document);
        // Finalize is reachable only from a fully-approved document; the queue button is
        // gated on (status == APPROVED && approvalsAcquired >= approvalsRequired). Defend
        // here as well so direct POSTs can't bypass the precondition.
        if (!"APPROVED".equals(document.getStatus())) {
            redirectAttributes.addFlashAttribute("error",
                    "Only APPROVED documents can be finalized.");
            return "redirect:/review/" + documentId;
        }
        final String previous = document.getStatus();
        document.changeStatus("FINALIZED");
        // Render the redacted text now and persist it on the document so a later
        // finalization-policy run that clears originalText/spans can't strand the
        // Download button.
        document.setRedactedText(renderRedactedText(document));
        documentRepository.save(document);
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        // Build the Certificate of Redaction from existing audit + document state. This
        // happens *after* the FINALIZED save so the certificate's timestamps capture the
        // post-finalize state of the document.
        final ai.philterd.arbiter.model.RedactionCertificate certificate =
                redactionCertificateService.generate(document, email);
        auditLogService.log("DOCUMENT_FINALIZE", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous,
                        "actor", email == null ? "" : email,
                        "certificateId", certificate.getId(),
                        "documentHash", certificate.getDocumentHash() == null
                                ? "" : certificate.getDocumentHash()));
        applyFinalizationPolicy(document, email);
        return "redirect:/queue";
    }

    /**
     * Resolve the batch's finalization policy and apply its disposition. Runs after the
     * Certificate of Redaction has been generated so destructive options (e.g. delete the
     * source document) cannot strand the certificate.
     */
    private void applyFinalizationPolicy(final Document document, final String actorEmail) {
        final String batchId = document.getBatchId();
        final Batch batch = batchId == null ? null : batchRepository.findById(batchId).orElse(null);
        final String policyId = batch == null ? null : batch.getFinalizationPolicyId();
        final ai.philterd.arbiter.model.FinalizationPolicy policy = (policyId == null || policyId.isBlank())
                ? null
                : finalizationPolicyRepository.findById(policyId).orElse(null);
        if (policy == null) return;
        final String option = policy.getOption();
        switch (option == null ? "" : option) {
            case ai.philterd.arbiter.model.FinalizationPolicy.OPTION_LEGAL_HOLD:
                auditLogService.log("FINALIZATION_POLICY_APPLIED", "Document", document.getId(),
                        Map.of("policyId", policy.getId() == null ? "" : policy.getId(),
                                "policyName", policy.getName() == null ? "" : policy.getName(),
                                "option", option,
                                "action", "RETAIN",
                                "actor", actorEmail == null ? "" : actorEmail));
                break;
            case ai.philterd.arbiter.model.FinalizationPolicy.OPTION_DELETE_IMMEDIATELY:
                // Wipe the source text and the span PII from the database while keeping the
                // Document record itself so it remains visible in the Document Queue and the
                // Certificate of Redaction stays linkable. The pre-rendered redactedText was
                // saved above so the Download button still works.
                final long spansDeleted = spanRepository.deleteByDocumentId(document.getId());
                document.setOriginalText(null);
                document.setStoragePath(null);
                documentRepository.save(document);
                auditLogService.log("FINALIZATION_POLICY_APPLIED", "Document", document.getId(),
                        Map.of("policyId", policy.getId() == null ? "" : policy.getId(),
                                "policyName", policy.getName() == null ? "" : policy.getName(),
                                "option", option,
                                "action", "SOURCE_CLEARED",
                                "spansDeleted", spansDeleted,
                                "actor", actorEmail == null ? "" : actorEmail));
                break;
            default:
                // Other options (DELETE_AFTER_X_DAYS, DELETE_AFTER_48H) are deferred and not
                // handled here; they require a separate scheduled job.
                break;
        }
    }

    @GetMapping("/review/{documentId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable final String documentId,
            final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (!"FINALIZED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Only finalized documents can be downloaded.");
        }

        final String redacted = document.getRedactedText() != null
                ? document.getRedactedText()
                : renderRedactedText(document);
        final byte[] body = redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final String filename = redactedFilename(document.getFilename());
        final String email = authentication == null ? null : authentication.getName();
        auditLogService.log("DOCUMENT_DOWNLOAD", "Document", documentId,
                Map.of("actor", email == null ? "" : email,
                        "filename", filename,
                        "bytes", body.length));
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(new org.springframework.core.io.ByteArrayResource(body));
    }

    private static String redactedFilename(final String original) {
        if (original == null || original.isBlank()) return "redacted.txt";
        final int dot = original.lastIndexOf('.');
        if (dot <= 0) return original + "_redacted";
        return original.substring(0, dot) + "_redacted" + original.substring(dot);
    }

    /**
     * Build the redacted text from the document's original text plus its APPROVED spans.
     * Each approved span is replaced with {@code <<TYPE>>}. Used at finalize time (so the
     * result can be persisted on the document) and as a fallback at download time when
     * {@link Document#getRedactedText()} hasn't been populated for legacy reasons.
     */
    private String renderRedactedText(final Document document) {
        final String originalText = document.getOriginalText() == null ? "" : document.getOriginalText();
        final List<Span> spans = spanRepository.findByDocumentId(document.getId());
        spans.sort(Comparator.comparingInt(s -> s.getLocation().characterStart()));

        final StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Span span : spans) {
            if (!"APPROVED".equals(span.getStatus())) continue;
            final int start = span.getLocation().characterStart();
            final int end = span.getLocation().characterEnd();
            if (start < cursor || end > originalText.length() || start > end) continue;
            out.append(originalText, cursor, start);
            out.append("<<").append(span.getType().toUpperCase()).append(">>");
            cursor = end;
        }
        out.append(originalText, cursor, originalText.length());
        return out.toString();
    }

    @PostMapping("/review/{documentId}/release")
    public String release(@PathVariable final String documentId,
                          final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        return "redirect:/queue";
    }

    /**
     * JSON-friendly release endpoint, for {@code navigator.sendBeacon} on page hide. Lives
     * under {@code /api/**} so it bypasses CSRF (sendBeacon can't easily attach tokens).
     */
    @PostMapping("/api/v1/review/{documentId}/release")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> releaseApi(@PathVariable final String documentId,
                                          final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/review/{documentId}/break-lock")
    public String breakLock(@PathVariable final String documentId,
                            final Authentication authentication,
                            final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only admins can break a review lock.");
            return "redirect:/review/" + documentId;
        }
        final Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/";
        }
        final String previousHolder = doc.getLockedBy();
        documentLockService.breakLock(documentId);
        auditLogService.log("DOCUMENT_LOCK_BROKEN", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName(),
                        "previousHolder", previousHolder == null ? "" : previousHolder));
        redirectAttributes.addFlashAttribute("success",
                previousHolder == null
                        ? "Lock cleared."
                        : "Lock previously held by " + previousHolder + " has been cleared.");
        return "redirect:/review/" + documentId;
    }

    @PostMapping("/review/{documentId}/approve")
    public String approve(@PathVariable final String documentId,
                          final Authentication authentication,
                          final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);

        final String email = authentication == null ? null : authentication.getName();
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        final User reviewer = email == null ? null
                : userRepository.findByEmail(email).orElse(null);

        // Disallow the same user from approving twice — a second approval must come from
        // a different reviewer. This guard runs even when the doc currently only requires
        // a single approval, so a user can't pre-emptively double-approve.
        if (email != null && document.getApprovedBy().contains(email)) {
            redirectAttributes.addFlashAttribute("error",
                    "You have already approved this document. A different reviewer must give the next approval.");
            return "redirect:/review/" + documentId;
        }

        // Every span must be either APPROVED or REJECTED before the document can be
        // approved. Spans awaiting a second opinion get a tailored message; any other
        // non-terminal status (PENDING, missing) is rolled up into a generic prompt.
        final long awaitingSecondOpinion = spans.stream()
                .filter(s -> Span.STATUS_NEEDS_SECOND_OPINION.equals(s.getStatus()))
                .count();
        final long undecided = spans.stream()
                .filter(s -> !"APPROVED".equals(s.getStatus())
                        && !"REJECTED".equals(s.getStatus())
                        && !Span.STATUS_NEEDS_SECOND_OPINION.equals(s.getStatus()))
                .count();
        if (awaitingSecondOpinion > 0 || undecided > 0) {
            final StringBuilder error = new StringBuilder();
            if (awaitingSecondOpinion > 0) {
                error.append(awaitingSecondOpinion).append(" span")
                        .append(awaitingSecondOpinion == 1 ? " is" : "s are")
                        .append(" awaiting a second opinion");
            }
            if (undecided > 0) {
                if (error.length() > 0) error.append("; ");
                error.append(undecided).append(" span")
                        .append(undecided == 1 ? " has" : "s have")
                        .append(" not yet been approved or refused");
            }
            error.append(". Every span must be approved or refused before the document can be approved.");
            redirectAttributes.addFlashAttribute("error", error.toString());
            return "redirect:/review/" + documentId;
        }

        final int required = approvalRuleEvaluator.dualApprovalRequired(batch, document, spans, reviewer)
                ? 2 : 1;

        // Record this approval.
        document.getApprovedBy().add(email == null ? "" : email);
        // Stamp the first-ever reviewer so the blind-double-review filter can identify
        // who is disqualified from the second pass. Set once and never overwritten.
        // Capture each reviewer's approved-span snapshot so the IAA report can compute
        // Cohen's Kappa between the first and second reviewer's labels.
        if (document.getFirstReviewer() == null && email != null) {
            document.setFirstReviewer(email);
            document.setFirstReviewSpans(approvedSpanRanges(spans));
        } else if (document.isDoubleReview()
                && email != null
                && document.getFirstReviewer() != null
                && !email.equalsIgnoreCase(document.getFirstReviewer())
                && document.getSecondReviewer() == null) {
            document.setSecondReviewer(email);
            document.setSecondReviewSpans(approvedSpanRanges(spans));
        }
        final int acquired = document.getApprovedBy().size();

        final String previous = document.getStatus();
        if (acquired >= required) {
            document.changeStatus("APPROVED");
        } else {
            // Stay in REVIEW_REQUIRED so the doc remains visible to other reviewers.
            document.changeStatus("REVIEW_REQUIRED");
        }
        documentRepository.save(document);
        incrementReviewCount(reviewer);
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        auditLogService.log("DOCUMENT_APPROVAL", "Document", documentId,
                Map.of(
                        "previous", previous == null ? "" : previous,
                        "current", document.getStatus() == null ? "" : document.getStatus(),
                        "approvedBy", email == null ? "" : email,
                        "acquired", acquired,
                        "required", required));

        if (acquired < required) {
            redirectAttributes.addFlashAttribute("success",
                    "Approval recorded (" + acquired + " of " + required
                            + "). " + (required - acquired) + " more approval"
                            + (required - acquired == 1 ? "" : "s")
                            + " needed from a different reviewer.");
            return "redirect:/";
        }

        final UserSettings settings = userSettingsService.loadForEmail(email);
        if (settings.isAdvanceToNextOnApprove()) {
            final String nextId = findSiblingId(document, settings, 1, email);
            if (nextId != null) return "redirect:/review/" + nextId;
        }
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/reject")
    public String reject(@PathVariable final String documentId, final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        final Document preReject = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, preReject);
        final String previous = preReject.getStatus();
        // Stamp the first-ever reviewer (set once) so the blind-double-review filter
        // can identify who is disqualified from the second pass. Capture each reviewer's
        // approved-span snapshot so the IAA report can compute Cohen's Kappa.
        boolean snapshotMutated = false;
        if (preReject.getFirstReviewer() == null && email != null) {
            preReject.setFirstReviewer(email);
            preReject.setFirstReviewSpans(approvedSpanRanges(spanRepository.findByDocumentId(documentId)));
            snapshotMutated = true;
        } else if (preReject.isDoubleReview()
                && email != null
                && preReject.getFirstReviewer() != null
                && !email.equalsIgnoreCase(preReject.getFirstReviewer())
                && preReject.getSecondReviewer() == null) {
            preReject.setSecondReviewer(email);
            preReject.setSecondReviewSpans(approvedSpanRanges(spanRepository.findByDocumentId(documentId)));
            snapshotMutated = true;
        }
        if (snapshotMutated) {
            documentRepository.save(preReject);
        }
        updateStatus(documentId, "REJECTED", authentication);
        auditLogService.log("DOCUMENT_REJECT", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous,
                        "rejectedBy", email == null ? "" : email));
        incrementReviewCountByEmail(email);
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        final UserSettings settings = userSettingsService.loadForEmail(email);
        if (settings.isAdvanceToNextOnApprove()) {
            final Document document = documentRepository.findById(documentId).orElse(null);
            if (document != null) {
                final String nextId = findSiblingId(document, settings, 1, email);
                if (nextId != null) return "redirect:/review/" + nextId;
            }
        }
        return "redirect:/queue";
    }

    private void incrementReviewCountByEmail(final String email) {
        if (email == null || email.isBlank()) return;
        incrementReviewCount(userRepository.findByEmail(email).orElse(null));
    }

    private void incrementReviewCount(final User reviewer) {
        if (reviewer == null) return;
        reviewer.setReviewCount(reviewer.getReviewCount() + 1);
        userRepository.save(reviewer);
    }

    @PostMapping("/review/{documentId}/unapprove")
    public String unapprove(@PathVariable final String documentId, final Authentication authentication,
                            final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if ("FINALIZED".equals(document.getStatus())) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        clearApprovals(document);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNAPPROVE", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    @PostMapping("/review/{documentId}/unreject")
    public String unreject(@PathVariable final String documentId, final Authentication authentication,
                           final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if ("FINALIZED".equals(document.getStatus())) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        clearApprovals(document);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNREJECT", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    /**
     * Drop any recorded approvals so the badge resets to {@code 0 of N} when the document
     * is sent back to {@code REVIEW_REQUIRED}. Caller must have already loaded the document
     * and verified access — this method writes unconditionally.
     */
    private void clearApprovals(final Document document) {
        if (document.getApprovedBy().isEmpty()) return;
        document.getApprovedBy().clear();
        documentRepository.save(document);
    }

    private String findSiblingId(final Document document, final UserSettings settings, final int direction,
                                 final String currentEmail) {
        if (document.getBatchId() == null) return null;
        final boolean skipCompleted = settings != null && settings.isSkipCompletedInReview();
        final List<Document> siblings = documentRepository.findByBatchId(document.getBatchId());
        siblings.sort(batchOrder(settings));
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (document.getId().equals(siblings.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;
        for (int i = index + direction; i >= 0 && i < siblings.size(); i += direction) {
            final Document candidate = siblings.get(i);
            if (skipCompleted && isAcceptedOrRejected(candidate.getStatus())) continue;
            // Blind double review: skip documents the current user already first-reviewed.
            // The second pass must come from a different reviewer.
            if (candidate.isDoubleReview()
                    && candidate.getFirstReviewer() != null
                    && currentEmail != null
                    && candidate.getFirstReviewer().equalsIgnoreCase(currentEmail)) {
                continue;
            }
            return candidate.getId();
        }
        return null;
    }

    private static boolean isAcceptedOrRejected(final String status) {
        return "APPROVED".equals(status) || "AUTO_APPROVED".equals(status) || "REJECTED".equals(status);
    }

    /**
     * Project the APPROVED spans on a document into {@code [start, end]} character ranges.
     * Used to snapshot a reviewer's effective PII labels at the moment they completed their
     * review so the Inter-Annotator Agreement (Cohen's Kappa) report can compare each reviewer.
     */
    private static java.util.List<int[]> approvedSpanRanges(final List<Span> spans) {
        if (spans == null || spans.isEmpty()) return new java.util.ArrayList<>();
        final java.util.List<int[]> ranges = new java.util.ArrayList<>();
        for (Span s : spans) {
            if (!"APPROVED".equals(s.getStatus())) continue;
            if (s.getLocation() == null) continue;
            ranges.add(new int[]{s.getLocation().characterStart(), s.getLocation().characterEnd()});
        }
        return ranges;
    }

    private void updateStatus(final String documentId, final String status, final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        final String previous = document.getStatus();
        document.changeStatus(status);
        documentRepository.save(document);
        auditLogService.log("DOCUMENT_STATUS_CHANGE", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous, "current", status));
    }

    private static Map<String, Object> redactionEntry(final Span span, final int start, final int end) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", span.getId());
        entry.put("text", span.getText());
        entry.put("type", span.getType());
        entry.put("confidence", span.getConfidence());
        entry.put("status", span.getStatus());
        entry.put("start", start);
        entry.put("end", end);
        entry.put("manuallyCreated", span.isManuallyCreated());
        entry.put("statusChangedBy", span.getStatusChangedBy());
        return entry;
    }
}
