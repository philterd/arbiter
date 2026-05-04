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
    long countByBatchId(String batchId);
    long countByBatchIdAndStatus(String batchId, String status);
    long countByBatchIdAndStatusIn(String batchId, java.util.Collection<String> statuses);
    List<Document> findByBatchId(String batchId);
}
