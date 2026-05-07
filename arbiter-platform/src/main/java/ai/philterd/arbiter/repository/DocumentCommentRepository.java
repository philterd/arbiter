package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.DocumentComment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentCommentRepository extends MongoRepository<DocumentComment, String> {
    List<DocumentComment> findByDocumentIdOrderByTimestampDesc(String documentId);
}
