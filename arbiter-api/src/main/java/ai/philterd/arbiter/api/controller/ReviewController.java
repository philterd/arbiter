package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.SpanStatusRequest;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.SpanRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final SpanRepository spanRepository;

    public ReviewController(SpanRepository spanRepository) {
        this.spanRepository = spanRepository;
    }

    @GetMapping("/documents/{id}/spans")
    public List<Span> getSpans(@PathVariable String id) {
        return spanRepository.findByDocumentId(id);
    }

    @PatchMapping("/spans/{id}")
    public Span updateSpanStatus(@PathVariable String id, @Valid @RequestBody SpanStatusRequest request) {
        Span span = spanRepository.findById(id).orElseThrow();
        span.setStatus(request.status());
        return spanRepository.save(span);
    }
}
