package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends MongoRepository<Document, String> {
    Page<Document> findByStatus(String status, Pageable pageable);
    Page<Document> findByBatchId(String batchId, Pageable pageable);
    Page<Document> findByBatchIdAndStatus(String batchId, String status, Pageable pageable);
    Page<Document> findByBatchIdAndStatusIn(String batchId, java.util.Collection<String> statuses, Pageable pageable);
    Page<Document> findByBatchIdIn(java.util.Collection<String> batchIds, Pageable pageable);
    Page<Document> findByBatchIdInAndStatus(java.util.Collection<String> batchIds, String status, Pageable pageable);

    Page<Document> findByFilenameContainingIgnoreCase(String filename, Pageable pageable);
    Page<Document> findByStatusAndFilenameContainingIgnoreCase(String status, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndFilenameContainingIgnoreCase(String batchId, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndStatusAndFilenameContainingIgnoreCase(String batchId, String status, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, String status, String filename, Pageable pageable);

    // Variants used by the Document Queue to hide ingest-queue-only statuses (PENDING /
    // PROCESSING) when the user has not explicitly filtered by status.
    Page<Document> findByStatusNotIn(java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByStatusNotInAndFilenameContainingIgnoreCase(java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndStatusNotIn(String batchId, java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase(String batchId, java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusNotIn(java.util.Collection<String> batchIds, java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusNotInAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    long countByBatchId(String batchId);
    long countByBatchIdAndStatus(String batchId, String status);
    long countByBatchIdAndStatusIn(String batchId, java.util.Collection<String> statuses);
    List<Document> findByBatchId(String batchId);
}
