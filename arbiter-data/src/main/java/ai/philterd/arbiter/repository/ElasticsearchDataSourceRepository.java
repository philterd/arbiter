package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.ElasticsearchDataSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ElasticsearchDataSourceRepository extends MongoRepository<ElasticsearchDataSource, String> {
    Optional<ElasticsearchDataSource> findByName(String name);
    Optional<ElasticsearchDataSource> findFirstByNameIgnoreCase(String name);
}
