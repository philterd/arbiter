package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.OpenSearchDataSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OpenSearchDataSourceRepository extends MongoRepository<OpenSearchDataSource, String> {
    Optional<OpenSearchDataSource> findByName(String name);
    Optional<OpenSearchDataSource> findFirstByNameIgnoreCase(String name);
}
