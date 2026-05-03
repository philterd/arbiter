package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    public RedactionApiServiceImpl(@Qualifier("philterClient") PhilterClient philterClient,
                                   DocumentRepository documentRepository,
                                   SpanRepository spanRepository) {
        this.philterClient = philterClient;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
    }

    @Override
    public void processDocument(String documentId, String text) throws IOException {
        String contextId = UUID.randomUUID().toString();
        Map<String, Object> explanation = philterClient.explain(text, contextId);

        List<Map<String, Object>> explanationSpans = (List<Map<String, Object>>) explanation.get("explanation");

        if (explanationSpans != null) {
            for (Map<String, Object> exp : explanationSpans) {
                Span span = new Span();
                span.setDocumentId(documentId);
                span.setText((String) exp.get("text"));
                span.setType((String) exp.get("type"));
                span.setConfidence((Double) exp.getOrDefault("confidence", 0.0));
                span.setStatus("PENDING");

                int start = (Integer) exp.get("characterStart");
                int end = (Integer) exp.get("characterEnd");
                int page = (Integer) exp.getOrDefault("page", 0);

                Location location = new Location(start, end, page, null);
                span.setLocation(location);

                spanRepository.save(span);
            }
        }

        Document document = documentRepository.findById(documentId).orElseThrow();
        document.setPhilterContextId(contextId);
        document.setStatus("REVIEW_REQUIRED");
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

        document.setStatus("COMPLETED");
        documentRepository.save(document);

        return finalizedText;
    }
}
