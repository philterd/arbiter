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

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.IngestStatus;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.RiskScore;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.RedactionService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.webapp.services.IngestQueueService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
public class RedactionController {

    private static final Logger log = LoggerFactory.getLogger(RedactionController.class);

    private final RedactionService redactionService;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final OpenSearchIndexService openSearchIndexService;
    private final IngestQueueService ingestQueueService;
    private final GeneralSettingsService generalSettingsService;

    public RedactionController(final RedactionService redactionService,
                               final BatchRepository batchRepository,
                               final DocumentRepository documentRepository,
                               final SpanRepository spanRepository,
                               final UserGroupsService userGroupsService,
                               final AuditLogService auditLogService,
                               final OpenSearchIndexService openSearchIndexService,
                               final IngestQueueService ingestQueueService,
                               final GeneralSettingsService generalSettingsService) {
        this.redactionService = redactionService;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.openSearchIndexService = openSearchIndexService;
        this.ingestQueueService = ingestQueueService;
        this.generalSettingsService = generalSettingsService;
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private boolean canAccessBatch(final Authentication auth, final Batch batch) {
        if (isAdmin(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
        return myGroupIds.contains(batch.getGroupId());
    }

    @GetMapping("/")
    public String dashboard(final Authentication authentication, final Model model) {
        final boolean admin = isAdmin(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName());

        final org.springframework.data.domain.Page<Batch> batchPage =
                batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<Batch> batches = new ArrayList<>(batchPage != null ? batchPage.getContent() : List.of());
        if (!admin) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }

        final long totalBatches = batches.size();
        final long openBatches = batches.stream().filter(b -> !b.isClosed()).count();
        final long closedBatches = totalBatches - openBatches;

        long totalDocuments = 0;
        long needsReview = 0;
        long autoApproved = 0;
        long approved = 0;
        long pending = 0;
        long failed = 0;
        for (Batch b : batches) {
            totalDocuments += documentRepository.countByBatchId(b.getId());
            needsReview += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("REVIEW_REQUIRED", "AUDIT_REQUIRED"));
            autoApproved += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("AUTO_APPROVED"));
            approved += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("APPROVED"));
            pending += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("PENDING"));
            failed += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("FAILED", "REJECTED"));
        }

        model.addAttribute("totalBatches", totalBatches);
        model.addAttribute("openBatches", openBatches);
        model.addAttribute("closedBatches", closedBatches);
        model.addAttribute("totalDocuments", totalDocuments);
        model.addAttribute("needsReview", needsReview);
        model.addAttribute("autoApproved", autoApproved);
        model.addAttribute("approved", approved);
        model.addAttribute("pending", pending);
        model.addAttribute("failed", failed);
        model.addAttribute("isAdmin", admin);
        return "dashboard";
    }

    @GetMapping("/queue")
    public String queue(final Authentication authentication, final Model model) {
        model.addAttribute("isAdmin", isAdmin(authentication));
        return "queue";
    }

    @GetMapping("/upload")
    public String upload(final Authentication authentication, final Model model) {
        final boolean admin = isAdmin(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName());
        final org.springframework.data.domain.Page<Batch> batchPage2 =
                batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<Batch> batches = new ArrayList<>(batchPage2 != null ? batchPage2.getContent() : List.of());
        batches.removeIf(Batch::isClosed);
        if (!admin) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }
        batches.sort(Comparator.comparing(Batch::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        model.addAttribute("batches", batches);
        return "index";
    }

    @PostMapping("/redact")
    public String redact(@RequestParam("file") final MultipartFile file,
                         @RequestParam("batchId") final String batchId,
                         final Authentication authentication,
                         final Model model,
                         final HttpSession session,
                         final RedirectAttributes redirectAttributes) throws IOException {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Selected batch no longer exists.");
            return "redirect:/upload";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error",
                    "Batch \"" + batch.getName() + "\" is closed and cannot accept new documents.");
            return "redirect:/upload";
        }

        final long maxBytes = generalSettingsService.load().getMaxUploadFileSizeBytes();
        if (file.getSize() > maxBytes) {
            redirectAttributes.addFlashAttribute("error",
                    "File exceeds the configured max upload size of "
                            + String.format("%.2f", maxBytes / 1048576.0) + " MB.");
            return "redirect:/upload";
        }

        final String contentType = file.getContentType();
        final byte[] fileBytes = file.getBytes();
        final String originalFilename = file.getOriginalFilename();

        final Document queued;
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            queued = ingestQueueService.enqueueFile(batch, originalFilename, fileBytes, contentType);
        } else {
            final String text = new String(fileBytes, StandardCharsets.UTF_8);
            queued = ingestQueueService.enqueueText(batch, originalFilename, text);
        }

        auditLogService.log("DOCUMENT_QUEUED", "Document", queued.getId(),
                Map.of(
                        "batchId", batch.getId(),
                        "filename", originalFilename == null ? "" : originalFilename,
                        "contentType", contentType == null ? "" : contentType,
                        "size", fileBytes.length));

        redirectAttributes.addFlashAttribute("success",
                "\"" + (originalFilename == null ? "Document" : originalFilename)
                        + "\" was queued for redaction. It will appear in the Document Queue once processed.");
        return "redirect:/upload";
    }

    private Document persistDocument(Batch batch, String filename, RedactionResponse response) {
        final String originalText = response.getOriginalText() == null ? "" : response.getOriginalText();
        final List<Redaction> redactions = response.getRedactions() == null ? List.of() : response.getRedactions();
        final double threshold = batch.getConfidenceThreshold();

        final Document document = new Document();
        document.setId(UUID.randomUUID().toString());
        document.setBatchId(batch.getId());
        document.setCreatedAt(LocalDateTime.now());
        document.setFilename(filename);
        document.setOriginalText(originalText);

        final List<Span> spans = new ArrayList<>();
        if (!redactions.isEmpty()) {
            final List<Redaction> ordered = new ArrayList<>(redactions);
            ordered.sort(Comparator.comparingInt(Redaction::getStart));
            int cursor = 0;
            for (Redaction r : ordered) {
                final int start = originalText.indexOf(r.getText(), cursor);
                if (start < 0) {
                    continue;
                }
                final int end = start + r.getText().length();
                final LocalDateTime now = LocalDateTime.now();
                final Span span = new Span();
                span.setId(UUID.randomUUID().toString());
                span.setDocumentId(document.getId());
                span.setType(r.getType());
                span.setText(r.getText());
                span.setConfidence(r.getConfidence());
                span.setCreatedAt(now);
                span.changeStatus(r.getConfidence() >= threshold ? "APPROVED" : "PENDING");
                span.setLocation(new Location(start, end, Math.max(r.getPageNumber(), 1),
                        new Coordinates(r.getLowerLeftX(), r.getLowerLeftY(),
                                r.getUpperRightX() - r.getLowerLeftX(),
                                r.getUpperRightY() - r.getLowerLeftY())));
                spans.add(span);
                cursor = end;
            }
        }
        document.setRiskScore(RiskScore.compute(spans, originalText, batch.getPiiTypeWeights()));

        final boolean needsReview = !spans.isEmpty()
                && spans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.changeStatus(IngestStatus.pick(batch, needsReview));
        documentRepository.save(document);
        if (!spans.isEmpty()) {
            spanRepository.saveAll(spans);
        }
        openSearchIndexService.indexDocument(document);
        return document;
    }


    @PostMapping("/preview")
    public ResponseEntity<Resource> preview(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @RequestBody RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            final byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            final String philterInstanceId = (String) session.getAttribute("philterInstanceId");
            if (originalFile != null) {
                final byte[] redactedPdf = redactionService.getRedactedPdf(
                        new ByteArrayInputStream(originalFile), redactionResponse, philterInstanceId);
                final ByteArrayResource resource = new ByteArrayResource(redactedPdf);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(resource);
            }
        }

        return ResponseEntity.notFound().build();
    }

    @PostMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @ModelAttribute RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        byte[] content;
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            final byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            final String philterInstanceId = (String) session.getAttribute("philterInstanceId");
            if (originalFile != null) {
                content = redactionService.getRedactedPdf(
                        new ByteArrayInputStream(originalFile), redactionResponse, philterInstanceId);
            } else {
                content = redactedText.getBytes(StandardCharsets.UTF_8);
                fileName = fileName.replace(".pdf", "_redacted.txt");
                contentType = MediaType.TEXT_PLAIN_VALUE;
            }
        } else {
            content = redactedText.getBytes(StandardCharsets.UTF_8);
            fileName = fileName.replace(".", "_redacted.");
        }

        final ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

}
