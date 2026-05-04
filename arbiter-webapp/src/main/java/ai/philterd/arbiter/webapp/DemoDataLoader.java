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
import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.IngestStatus;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Policy;
import ai.philterd.arbiter.model.RiskScore;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "arbiter.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private static final String DEMO_GROUP_NAME = "Default";

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final PolicyRepository policyRepository;
    private final RedactionService redactionService;
    private final String sampleFilesDirectory;

    public DemoDataLoader(final BatchRepository batchRepository,
                          final DocumentRepository documentRepository,
                          final SpanRepository spanRepository,
                          final GroupRepository groupRepository,
                          final UserRepository userRepository,
                          final PolicyRepository policyRepository,
                          final RedactionService redactionService,
                          @Value("${arbiter.demo-data.directory:sample-files}") final String sampleFilesDirectory) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.policyRepository = policyRepository;
        this.redactionService = redactionService;
        this.sampleFilesDirectory = sampleFilesDirectory;
    }

    @Override
    public void run(final ApplicationArguments args) {
        ensureDefaultPolicy();

        if (batchRepository.count() > 0 || documentRepository.count() > 0 || spanRepository.count() > 0) {
            log.info("Demo data not loaded: existing data found in batches/documents/spans collections.");
            return;
        }

        final Path directory = resolveDirectory(sampleFilesDirectory);
        if (directory == null) {
            log.warn("Demo data not loaded: sample files directory '{}' not found.", sampleFilesDirectory);
            return;
        }

        List<Path> files;
        try (final Stream<Path> stream = Files.list(directory)) {
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

        final Group demoGroup = ensureDemoGroup();

        final Batch batch = new Batch();
        batch.setId(UUID.randomUUID().toString());
        batch.setName("Sample files");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setOwnerId("demo-user");
        batch.setGroupId(demoGroup.getId());
        batch.setStats(Map.of("source", directory.toString()));
        batchRepository.save(batch);

        int loaded = 0;
        for (Path file : files) {
            if (loadFile(file, batch)) {
                loaded++;
            }
        }

        log.info("Demo data loaded: 1 batch, {} document(s), {} span(s).",
                loaded, spanRepository.count());
    }

    private void ensureDefaultPolicy() {
        if (policyRepository.count() > 0) {
            return;
        }
        final Policy policy = new Policy();
        policy.setId(UUID.randomUUID().toString());
        policy.setName("Default");
        policy.setContent(DEFAULT_POLICY_JSON);
        policy.setCreatedAt(LocalDateTime.now());
        policyRepository.save(policy);
        log.info("Seeded default Phileas policy.");
    }

    private static final String DEFAULT_POLICY_JSON = """
            {
              "name": "Default",
              "identifiers": {
                "age": { "ageFilterStrategies": [{"strategy": "REDACT"}] },
                "creditCard": { "creditCardFilterStrategies": [{"strategy": "REDACT"}] },
                "date": { "dateFilterStrategies": [{"strategy": "REDACT"}] },
                "emailAddress": { "emailAddressFilterStrategies": [{"strategy": "REDACT"}] },
                "ipAddress": { "ipAddressFilterStrategies": [{"strategy": "REDACT"}] },
                "phoneNumber": { "phoneNumberFilterStrategies": [{"strategy": "REDACT"}] },
                "ssn": { "ssnFilterStrategies": [{"strategy": "REDACT"}] }
              }
            }
            """;

    private Group ensureDemoGroup() {
        return groupRepository.findByName(DEMO_GROUP_NAME).orElseGet(() -> {
            final Group group = new Group();
            group.setId(UUID.randomUUID().toString());
            group.setName(DEMO_GROUP_NAME);
            group.setCreatedAt(LocalDateTime.now());
            final Set<String> userIds = new HashSet<>();
            for (User u : userRepository.findAll()) {
                if (u.getId() != null) {
                    userIds.add(u.getId());
                }
            }
            group.setUserIds(userIds);
            groupRepository.save(group);
            return group;
        });
    }

    private boolean loadFile(final Path file, final Batch batch) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Skipping {}: failed to read file: {}", file, e.getMessage());
            return false;
        }

        final Document document = new Document();
        document.setId(UUID.randomUUID().toString());
        document.setBatchId(batch.getId());
        document.setCreatedAt(LocalDateTime.now());
        document.setFilename(file.getFileName().toString());
        document.setStoragePath(file.toAbsolutePath().toString());
        document.setOriginalText(text);

        final List<Span> spans = new ArrayList<>();
        try {
            final RedactionResponse response = redactionService.redactText(text, batch.getPhilterInstanceId());
            final List<Redaction> ordered = new ArrayList<>(response.getRedactions());
            ordered.sort(Comparator.comparingInt(Redaction::getStart));
            int cursor = 0;
            for (Redaction redaction : ordered) {
                final int originalStart = text.indexOf(redaction.getText(), cursor);
                if (originalStart < 0) {
                    log.warn("Could not locate '{}' in original text of {}; skipping span.",
                            redaction.getText(), file.getFileName());
                    continue;
                }
                final int originalEnd = originalStart + redaction.getText().length();
                spans.add(toSpan(document.getId(), redaction, originalStart, originalEnd, batch.getConfidenceThreshold()));
                cursor = originalEnd;
            }
            final boolean needsReview = !spans.isEmpty()
                    && spans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
            document.changeStatus(IngestStatus.pick(batch, needsReview));
        } catch (Exception e) {
            log.warn("Redaction failed for {}: {}. Storing as PENDING.", file.getFileName(), e.getMessage());
            document.changeStatus("PENDING");
            spans.clear();
        }

        document.setRiskScore(RiskScore.compute(spans, text, batch.getPiiTypeWeights()));

        documentRepository.save(document);
        if (!spans.isEmpty()) {
            spanRepository.saveAll(spans);
        }
        log.info("Loaded {} ({} span(s), risk score {}).",
                file.getFileName(), spans.size(),
                String.format("%.4f", document.getRiskScore()));
        return true;
    }

    private static Span toSpan(final String documentId, final Redaction redaction, final int originalStart, final int originalEnd, final double threshold) {
        final LocalDateTime now = LocalDateTime.now();
        final Span span = new Span();
        span.setId(redaction.getId() != null ? redaction.getId() : UUID.randomUUID().toString());
        span.setDocumentId(documentId);
        span.setType(redaction.getType());
        span.setText(redaction.getText());
        span.setConfidence(redaction.getConfidence());
        span.setCreatedAt(now);
        span.changeStatus(redaction.getConfidence() >= threshold ? "APPROVED" : "PENDING");
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


    private static Path resolveDirectory(final String configured) {
        final List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(configured));
        final Path leaf = Paths.get(configured).getFileName();
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
