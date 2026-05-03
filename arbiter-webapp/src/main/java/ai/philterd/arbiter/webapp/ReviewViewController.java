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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

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

    public ReviewViewController(DocumentRepository documentRepository, SpanRepository spanRepository) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
    }

    @GetMapping("/review/{documentId}")
    public String review(@PathVariable String documentId, Model model) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));

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

        model.addAttribute("document", document);
        model.addAttribute("originalText", originalText);
        model.addAttribute("redactedText", redactedBuilder.toString());
        model.addAttribute("originalRedactions", originalRedactions);
        model.addAttribute("redactedRedactions", redactedRedactions);
        return "review";
    }

    @PostMapping("/review/{documentId}/approve")
    public String approve(@PathVariable String documentId) {
        updateStatus(documentId, "APPROVED");
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/reject")
    public String reject(@PathVariable String documentId) {
        updateStatus(documentId, "REJECTED");
        return "redirect:/";
    }

    private void updateStatus(String documentId, String status) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        document.setStatus(status);
        documentRepository.save(document);
    }

    private static Map<String, Object> redactionEntry(Span span, int start, int end) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", span.getId());
        entry.put("text", span.getText());
        entry.put("type", span.getType());
        entry.put("confidence", span.getConfidence());
        entry.put("start", start);
        entry.put("end", end);
        return entry;
    }
}
