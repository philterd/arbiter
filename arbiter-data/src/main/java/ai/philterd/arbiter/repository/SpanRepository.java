package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Span;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpanRepository extends MongoRepository<Span, String> {
    List<Span> findByDocumentId(String documentId);
    List<Span> findByStatusOrderByStatusChangedAtDesc(String status);
    long countByDocumentIdInAndStatus(java.util.Collection<String> documentIds, String status);
    long countByDocumentIdAndStatus(String documentId, String status);
    long countByDocumentIdInAndManuallyCreated(java.util.Collection<String> documentIds, boolean manuallyCreated);
    long deleteByDocumentId(String documentId);
}
