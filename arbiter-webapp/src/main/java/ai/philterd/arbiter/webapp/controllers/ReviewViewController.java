/*
 * Copyright 2026 Philterd
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
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.services.RedactionCertificateService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final UserSettingsService userSettingsService;
    private final UserRepository userRepository;
    private final ApprovalRuleEvaluator approvalRuleEvaluator;
    private final ai.philterd.arbiter.service.DocumentLockService documentLockService;
    private final RedactionCertificateService redactionCertificateService;
    private final FinalizationPolicyRepository finalizationPolicyRepository;

    public ReviewViewController(final DocumentRepository documentRepository,
                                final SpanRepository spanRepository,
                                final BatchRepository batchRepository,
                                final ComplianceProfileRepository complianceProfileRepository,
                                final UserGroupsService userGroupsService,
                                final AuditLogService auditLogService,
                                final OllamaInstanceRepository ollamaInstanceRepository,
                                final LlmJudgeDefaultsService llmJudgeDefaultsService,
                                final UserSettingsService userSettingsService,
                                final UserRepository userRepository,
                                final ApprovalRuleEvaluator approvalRuleEvaluator,
                                final ai.philterd.arbiter.service.DocumentLockService documentLockService,
                                final RedactionCertificateService redactionCertificateService,
                                final FinalizationPolicyRepository finalizationPolicyRepository) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.complianceProfileRepository = complianceProfileRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.userSettingsService = userSettingsService;
        this.userRepository = userRepository;
        this.approvalRuleEvaluator = approvalRuleEvaluator;
        this.documentLockService = documentLockService;
        this.redactionCertificateService = redactionCertificateService;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private void requireAccess(final Authentication auth, final Document document) {
        if (isAdmin(auth)) return;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    @GetMapping("/review/{documentId}")
    public String review(@PathVariable final String documentId, final Authentication authentication, final Model model) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);
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

        final UserSettings settings = userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName());
        final boolean skipCompleted = settings.isSkipCompletedInReview();

        final String prevDocumentId = findSiblingId(document, skipCompleted, -1);
        final String nextDocumentId = findSiblingId(document, skipCompleted, 1);

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
        model.addAttribute("exemptionCodes", exemptionCodes);
        model.addAttribute("complianceProfileName", complianceProfileName);

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

    @PostMapping("/review/{documentId}/finalize")
    public String finalizeDocument(@PathVariable final String documentId,
                                   final Authentication authentication,
                                   final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/queue";
        }
        requireAccess(authentication, document);
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
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);
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
        if (!isAdmin(authentication)) {
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
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);

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
            final String nextId = findSiblingId(document, settings.isSkipCompletedInReview(), 1);
            if (nextId != null) return "redirect:/review/" + nextId;
        }
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/reject")
    public String reject(@PathVariable final String documentId, final Authentication authentication) {
        updateStatus(documentId, "REJECTED", authentication);
        incrementReviewCountByEmail(authentication == null ? null : authentication.getName());
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        return "redirect:/";
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
        if (isFinalized(documentId)) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        clearApprovals(documentId);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNAPPROVE", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    @PostMapping("/review/{documentId}/unreject")
    public String unreject(@PathVariable final String documentId, final Authentication authentication,
                           final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        if (isFinalized(documentId)) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        clearApprovals(documentId);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNREJECT", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    private boolean isFinalized(final String documentId) {
        final Document doc = documentRepository.findById(documentId).orElse(null);
        return doc != null && "FINALIZED".equals(doc.getStatus());
    }

    /**
     * Drop any recorded approvals so the badge resets to {@code 0 of N} when the document
     * is sent back to {@code REVIEW_REQUIRED}. Called from both {@code unapprove} and
     * {@code unreject} — in either case the prior decision is being reopened, so prior
     * approver records no longer apply.
     */
    private void clearApprovals(final String documentId) {
        final Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;
        if (document.getApprovedBy().isEmpty()) return;
        document.getApprovedBy().clear();
        documentRepository.save(document);
    }

    private String findSiblingId(final Document document, final boolean skipCompleted, final int direction) {
        if (document.getBatchId() == null) return null;
        final List<Document> siblings = documentRepository.findByBatchId(document.getBatchId());
        siblings.sort(Comparator
                .comparing((Document d) -> d.getFilename() == null ? "" : d.getFilename().toLowerCase())
                .thenComparing(d -> d.getId() == null ? "" : d.getId()));
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
            if (skipCompleted && "AUTO_APPROVED".equals(candidate.getStatus())) continue;
            return candidate.getId();
        }
        return null;
    }

    private void updateStatus(final String documentId, final String status, final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);
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
