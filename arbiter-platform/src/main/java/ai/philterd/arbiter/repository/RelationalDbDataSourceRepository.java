package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.RelationalDbDataSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RelationalDbDataSourceRepository extends MongoRepository<RelationalDbDataSource, String> {
    Optional<RelationalDbDataSource> findByName(String name);
    Optional<RelationalDbDataSource> findFirstByNameIgnoreCase(String name);
}
