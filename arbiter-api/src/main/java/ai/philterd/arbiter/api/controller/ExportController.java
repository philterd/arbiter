package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.RedactionApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private final RedactionApiService redactionApiService;
    private final SpanRepository spanRepository;

    public ExportController(RedactionApiService redactionApiService, SpanRepository spanRepository) {
        this.redactionApiService = redactionApiService;
        this.spanRepository = spanRepository;
    }

    @PostMapping("/documents/{id}/finalize")
    public Map<String, String> finalize(@PathVariable String id) throws IOException {
        String finalizedText = redactionApiService.finalizeRedaction(id);
        return Map.of("finalizedText", finalizedText);
    }

    @GetMapping("/documents/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable String id) {
        List<Span> spans = spanRepository.findByDocumentId(id);
        return spans.stream().map(s -> Map.<String, Object>of(
            "text", s.getText(),
            "type", s.getType(),
            "confidence", s.getConfidence(),
            "status", s.getStatus()
        )).collect(Collectors.toList());
    }

}
