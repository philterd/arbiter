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
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.model.UserSettings;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.service.UserSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

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
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final UserSettingsService userSettingsService;

    public ReviewViewController(DocumentRepository documentRepository,
                                SpanRepository spanRepository,
                                BatchRepository batchRepository,
                                UserGroupsService userGroupsService,
                                AuditLogService auditLogService,
                                OllamaInstanceRepository ollamaInstanceRepository,
                                LlmJudgeDefaultsService llmJudgeDefaultsService,
                                UserSettingsService userSettingsService) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.userSettingsService = userSettingsService;
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private void requireAccess(Authentication auth, Document document) {
        if (isAdmin(auth)) return;
        Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        Set<String> myGroupIds = userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    @GetMapping("/review/{documentId}")
    public String review(@PathVariable String documentId, Authentication authentication, Model model) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);

        String originalText = document.getOriginalText() == null ? "" : document.getOriginalText();

        List<Span> spans = spanRepository.findByDocumentId(documentId);
        spans.sort(Comparator.comparingInt(s -> s.getLocation().characterStart()));

        StringBuilder redactedBuilder = new StringBuilder();
        List<Map<String, Object>> originalRedactions = new ArrayList<>();
        List<Map<String, Object>> redactedRedactions = new ArrayList<>();

        int cursor = 0;
        for (Span span : spans) {
            int start = span.getLocation().characterStart();
            int end = span.getLocation().characterEnd();
            if (start < cursor || end > originalText.length() || start > end) {
                continue;
            }
            redactedBuilder.append(originalText, cursor, start);

            String replacement = "<<" + span.getType().toUpperCase() + ">>";
            int newStart = redactedBuilder.length();
            redactedBuilder.append(replacement);
            int newEnd = redactedBuilder.length();

            originalRedactions.add(redactionEntry(span, start, end));
            redactedRedactions.add(redactionEntry(span, newStart, newEnd));

            cursor = end;
        }
        redactedBuilder.append(originalText, cursor, originalText.length());

        List<Map<String, String>> piiTypes = new ArrayList<>();
        PiiTypes.labels().forEach((value, label) -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("value", value);
            entry.put("label", label);
            piiTypes.add(entry);
        });

        LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        String defaultExplainInstanceId = defaults.getExplainInstanceId();
        String defaultExplainModel = defaults.getExplainModel();
        boolean secondOpinionConfigured = defaults.getSecondOpinionInstanceId() != null;

        List<Map<String, Object>> ollamaInstances = new ArrayList<>();
        List<OllamaInstance> allInstances = ollamaInstanceRepository.findAll();
        allInstances.sort(Comparator.comparing(
                (OllamaInstance i) -> i.getName() == null ? "" : i.getName().toLowerCase()));
        boolean explainInstanceStillExists = false;
        for (OllamaInstance i : allInstances) {
            Map<String, Object> entry = new LinkedHashMap<>();
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

        UserSettings settings = userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName());
        boolean skipCompleted = settings.isSkipCompletedInReview();

        String prevDocumentId = findSiblingId(document, skipCompleted, -1);
        String nextDocumentId = findSiblingId(document, skipCompleted, 1);

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
        return "review";
    }

    @PostMapping("/review/{documentId}/approve")
    public String approve(@PathVariable String documentId, Authentication authentication) {
        updateStatus(documentId, "APPROVED", authentication);
        UserSettings settings = userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName());
        if (settings.isAdvanceToNextOnApprove()) {
            Document approved = documentRepository.findById(documentId).orElse(null);
            if (approved != null) {
                String nextId = findSiblingId(approved, settings.isSkipCompletedInReview(), 1);
                if (nextId != null) return "redirect:/review/" + nextId;
            }
        }
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/reject")
    public String reject(@PathVariable String documentId, Authentication authentication) {
        updateStatus(documentId, "REJECTED", authentication);
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/unapprove")
    public String unapprove(@PathVariable String documentId, Authentication authentication) {
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        return "redirect:/review/" + documentId;
    }

    private String findSiblingId(Document document, boolean skipCompleted, int direction) {
        if (document.getBatchId() == null) return null;
        List<Document> siblings = documentRepository.findByBatchId(document.getBatchId());
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
            Document candidate = siblings.get(i);
            if (skipCompleted && "AUTO_APPROVED".equals(candidate.getStatus())) continue;
            return candidate.getId();
        }
        return null;
    }

    private void updateStatus(String documentId, String status, Authentication authentication) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireAccess(authentication, document);
        String previous = document.getStatus();
        document.setStatus(status);
        documentRepository.save(document);
        auditLogService.log("DOCUMENT_STATUS_CHANGE", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous, "current", status));
    }

    private static Map<String, Object> redactionEntry(Span span, int start, int end) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", span.getId());
        entry.put("text", span.getText());
        entry.put("type", span.getType());
        entry.put("confidence", span.getConfidence());
        entry.put("status", span.getStatus());
        entry.put("start", start);
        entry.put("end", end);
        entry.put("manuallyCreated", span.isManuallyCreated());
        return entry;
    }
}
