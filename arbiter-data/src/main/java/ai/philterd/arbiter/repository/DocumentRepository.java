package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentRepository extends MongoRepository<Document, String> {
    Page<Document> findByStatus(String status, Pageable pageable);
}
