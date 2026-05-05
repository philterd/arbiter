package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.IngestStatus;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterClientFactory;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedactionApiServiceImpl implements RedactionApiService {

    private static final Logger log = LoggerFactory.getLogger(RedactionApiServiceImpl.class);

    private final PhilterClient phileasClient;
    private final PhilterClientFactory philterClientFactory;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final OpenSearchIndexService openSearchIndexService;
    private final SymmetricCipher symmetricCipher;

    public RedactionApiServiceImpl(@Qualifier("phileasClient") final PhilterClient phileasClient,
                                   final PhilterClientFactory philterClientFactory,
                                   final PhilterInstanceRepository philterInstanceRepository,
                                   final DocumentRepository documentRepository,
                                   final SpanRepository spanRepository,
                                   final BatchRepository batchRepository,
                                   final OpenSearchIndexService openSearchIndexService,
                                   final SymmetricCipher symmetricCipher) {
        this.phileasClient = phileasClient;
        this.philterClientFactory = philterClientFactory;
        this.philterInstanceRepository = philterInstanceRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.openSearchIndexService = openSearchIndexService;
        this.symmetricCipher = symmetricCipher;
    }

    private PhilterClient philterClient(final Batch batch) {
        final String instanceId = batch == null ? null : batch.getPhilterInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            log.info("Batch \"{}\" uses Embedded Philter (local Phileas).",
                    batch == null ? "?" : batch.getName());
            return phileasClient;
        }
        final Optional<PhilterInstance> instance = philterInstanceRepository.findById(instanceId);
        if (instance.isEmpty()) {
            log.warn("Philter instance {} configured on batch \"{}\" no longer exists; "
                    + "falling back to Embedded Philter.", instanceId, batch.getName());
            return phileasClient;
        }
        final PhilterInstance pi = instance.get();
        final String apiKey;
        try {
            apiKey = symmetricCipher.decrypt(pi.getEncryptedApiKey());
        } catch (Exception e) {
            log.warn("Could not decrypt API key for Philter instance \"{}\": {}",
                    pi.getName(), e.getMessage());
            return philterClientFactory.create(baseUrl(pi));
        }
        return philterClientFactory.create(baseUrl(pi), apiKey);
    }

    private static String baseUrl(final PhilterInstance instance) {
        String host = instance.getEndpoint();
        if (host == null || host.isBlank()) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }

    @Override
    public void processDocument(final String documentId, final String text) throws IOException {
        final Document document = documentRepository.findById(documentId).orElseThrow();
        final Batch batch = batchRepository.findById(document.getBatchId() == null ? "" : document.getBatchId()).orElse(null);
        final double threshold = batch == null ? 0.8 : batch.getConfidenceThreshold();

        // Use the batch's configured context if set, otherwise fall back to a unique id so that
        // finalize-time redaction can replay the exact context.
        final String batchContext = batch == null ? null : batch.getContext();
        final String contextId = (batchContext != null && !batchContext.isEmpty())
                ? batchContext
                : UUID.randomUUID().toString();
        final Map<String, Object> explanation = philterClient(batch).explain(text, contextId);

        final List<Map<String, Object>> explanationSpans = (List<Map<String, Object>>) explanation.get("explanation");

        final List<Span> persistedSpans = new ArrayList<>();
        if (explanationSpans != null) {
            for (Map<String, Object> exp : explanationSpans) {
                final Span span = new Span();
                span.setDocumentId(documentId);
                span.setText((String) exp.get("text"));
                span.setType((String) exp.get("type"));
                final double confidence = (Double) exp.getOrDefault("confidence", 0.0);
                span.setConfidence(confidence);
                span.setCreatedAt(java.time.LocalDateTime.now());
                span.changeStatus(confidence >= threshold ? "APPROVED" : "PENDING");

                final int start = (Integer) exp.get("characterStart");
                final int end = (Integer) exp.get("characterEnd");
                final int page = (Integer) exp.getOrDefault("page", 0);

                final Location location = new Location(start, end, page, null);
                span.setLocation(location);

                persistedSpans.add(spanRepository.save(span));
            }
        }

        final boolean needsReview = !persistedSpans.isEmpty()
                && persistedSpans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.setPhilterContextId(contextId);
        document.changeStatus(IngestStatus.pick(batch, needsReview));
        documentRepository.save(document);
        if (document.getOriginalText() == null) {
            document.setOriginalText(text);
        }
        openSearchIndexService.indexDocument(document);
    }

    @Override
    public String finalizeRedaction(final String documentId) throws IOException {
        final Document document = documentRepository.findById(documentId).orElseThrow();
        final Batch batch = batchRepository.findById(document.getBatchId() == null ? "" : document.getBatchId()).orElse(null);
        final List<Span> spans = spanRepository.findByDocumentId(documentId);

        final List<ai.philterd.arbiter.core.model.Redaction> approvedSpans = spans.stream()
                .filter(s -> "APPROVED".equals(s.getStatus()))
                .map(s -> {
                    final ai.philterd.arbiter.core.model.Redaction r = new ai.philterd.arbiter.core.model.Redaction();
                    r.setText(s.getText());
                    r.setType(s.getType());
                    r.setStart(s.getLocation().characterStart());
                    r.setEnd(s.getLocation().characterEnd());
                    return r;
                })
                .collect(Collectors.toList());

        final String originalText = "Original text placeholder";

        final String finalizedText = philterClient(batch).redact(originalText, document.getPhilterContextId(), approvedSpans);

        document.changeStatus("AUTO_APPROVED");
        documentRepository.save(document);

        return finalizedText;
    }
}
