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

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.RedactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "arbiter.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final RedactionService redactionService;
    private final String sampleFilesDirectory;

    public DemoDataLoader(BatchRepository batchRepository,
                          DocumentRepository documentRepository,
                          SpanRepository spanRepository,
                          RedactionService redactionService,
                          @Value("${arbiter.demo-data.directory:sample-files}") String sampleFilesDirectory) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.redactionService = redactionService;
        this.sampleFilesDirectory = sampleFilesDirectory;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (batchRepository.count() > 0 || documentRepository.count() > 0 || spanRepository.count() > 0) {
            log.info("Demo data not loaded: existing data found in batches/documents/spans collections.");
            return;
        }

        Path directory = resolveDirectory(sampleFilesDirectory);
        if (directory == null) {
            log.warn("Demo data not loaded: sample files directory '{}' not found.", sampleFilesDirectory);
            return;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            log.warn("Demo data not loaded: failed to list {}: {}", directory, e.getMessage());
            return;
        }

        if (files.isEmpty()) {
            log.info("Demo data not loaded: no files found under {}.", directory);
            return;
        }

        log.info("Loading demo data from {} ({} file{}).", directory, files.size(), files.size() == 1 ? "" : "s");

        Batch batch = new Batch();
        batch.setId(UUID.randomUUID().toString());
        batch.setName("Sample files");
        batch.setStatus("COMPLETED");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setOwnerId("demo-user");
        batch.setStats(Map.of("source", directory.toString()));
        batchRepository.save(batch);

        int loaded = 0;
        for (Path file : files) {
            if (loadFile(file, batch.getId())) {
                loaded++;
            }
        }

        log.info("Demo data loaded: 1 batch, {} document(s), {} span(s).",
                loaded, spanRepository.count());
    }

    private boolean loadFile(Path file, String batchId) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Skipping {}: failed to read file: {}", file, e.getMessage());
            return false;
        }

        Document document = new Document();
        document.setId(UUID.randomUUID().toString());
        document.setBatchId(batchId);
        document.setFilename(file.getFileName().toString());
        document.setStoragePath(file.toAbsolutePath().toString());
        document.setOriginalText(text);

        List<Span> spans = new ArrayList<>();
        try {
            RedactionResponse response = redactionService.redactText(text);
            List<Redaction> ordered = new ArrayList<>(response.getRedactions());
            ordered.sort(Comparator.comparingInt(Redaction::getStart));
            int cursor = 0;
            for (Redaction redaction : ordered) {
                int originalStart = text.indexOf(redaction.getText(), cursor);
                if (originalStart < 0) {
                    log.warn("Could not locate '{}' in original text of {}; skipping span.",
                            redaction.getText(), file.getFileName());
                    continue;
                }
                int originalEnd = originalStart + redaction.getText().length();
                spans.add(toSpan(document.getId(), redaction, originalStart, originalEnd));
                cursor = originalEnd;
            }
            document.setStatus(spans.isEmpty() ? "COMPLETED" : "REVIEW_REQUIRED");
            document.setRiskScore(computeRiskScore(spans));
        } catch (Exception e) {
            log.warn("Redaction failed for {}: {}. Storing as PENDING.", file.getFileName(), e.getMessage());
            document.setStatus("PENDING");
            document.setRiskScore(0.0);
        }

        documentRepository.save(document);
        if (!spans.isEmpty()) {
            spanRepository.saveAll(spans);
        }
        return true;
    }

    private static Span toSpan(String documentId, Redaction redaction, int originalStart, int originalEnd) {
        Span span = new Span();
        span.setId(redaction.getId() != null ? redaction.getId() : UUID.randomUUID().toString());
        span.setDocumentId(documentId);
        span.setType(redaction.getType());
        span.setText(redaction.getText());
        span.setConfidence(redaction.getConfidence());
        span.setStatus("PENDING");
        span.setLocation(new Location(
                originalStart,
                originalEnd,
                Math.max(redaction.getPageNumber(), 1),
                new Coordinates(
                        redaction.getLowerLeftX(),
                        redaction.getLowerLeftY(),
                        redaction.getUpperRightX() - redaction.getLowerLeftX(),
                        redaction.getUpperRightY() - redaction.getLowerLeftY())));
        return span;
    }

    private static double computeRiskScore(List<Span> spans) {
        if (spans.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Span s : spans) {
            sum += s.getConfidence();
        }
        return sum / spans.size();
    }

    private static Path resolveDirectory(String configured) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(configured));
        Path leaf = Paths.get(configured).getFileName();
        if (leaf != null) {
            candidates.add(Paths.get(leaf.toString()));
            candidates.add(Paths.get("..").resolve(leaf));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
