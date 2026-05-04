package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.IngestStatus;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedactionApiServiceImpl implements RedactionApiService {

    private static final Logger log = LoggerFactory.getLogger(RedactionApiServiceImpl.class);

    private final PhilterClient philterClient;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;

    public RedactionApiServiceImpl(@Qualifier("philterClient") PhilterClient philterClient,
                                   DocumentRepository documentRepository,
                                   SpanRepository spanRepository,
                                   BatchRepository batchRepository) {
        this.philterClient = philterClient;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
    }

    @Override
    public void processDocument(String documentId, String text) throws IOException {
        Document document = documentRepository.findById(documentId).orElseThrow();
        Batch batch = batchRepository.findById(document.getBatchId() == null ? "" : document.getBatchId()).orElse(null);
        double threshold = batch == null ? 0.8 : batch.getConfidenceThreshold();

        String contextId = UUID.randomUUID().toString();
        Map<String, Object> explanation = philterClient.explain(text, contextId);

        List<Map<String, Object>> explanationSpans = (List<Map<String, Object>>) explanation.get("explanation");

        List<Span> persistedSpans = new ArrayList<>();
        if (explanationSpans != null) {
            for (Map<String, Object> exp : explanationSpans) {
                Span span = new Span();
                span.setDocumentId(documentId);
                span.setText((String) exp.get("text"));
                span.setType((String) exp.get("type"));
                double confidence = (Double) exp.getOrDefault("confidence", 0.0);
                span.setConfidence(confidence);
                span.setStatus(confidence >= threshold ? "APPROVED" : "PENDING");

                int start = (Integer) exp.get("characterStart");
                int end = (Integer) exp.get("characterEnd");
                int page = (Integer) exp.getOrDefault("page", 0);

                Location location = new Location(start, end, page, null);
                span.setLocation(location);

                persistedSpans.add(spanRepository.save(span));
            }
        }

        boolean needsReview = !persistedSpans.isEmpty()
                && persistedSpans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.setPhilterContextId(contextId);
        document.setStatus(IngestStatus.pick(batch, needsReview));
        documentRepository.save(document);
    }

    @Override
    public String finalizeRedaction(String documentId) throws IOException {
        Document document = documentRepository.findById(documentId).orElseThrow();
        List<Span> spans = spanRepository.findByDocumentId(documentId);

        List<ai.philterd.arbiter.core.model.Redaction> approvedSpans = spans.stream()
                .filter(s -> "APPROVED".equals(s.getStatus()))
                .map(s -> {
                    ai.philterd.arbiter.core.model.Redaction r = new ai.philterd.arbiter.core.model.Redaction();
                    r.setText(s.getText());
                    r.setType(s.getType());
                    r.setStart(s.getLocation().characterStart());
                    r.setEnd(s.getLocation().characterEnd());
                    return r;
                })
                .collect(Collectors.toList());

        String originalText = "Original text placeholder";

        String finalizedText = philterClient.redact(originalText, document.getPhilterContextId(), approvedSpans);

        document.setStatus("AUTO_APPROVED");
        documentRepository.save(document);

        return finalizedText;
    }
}
