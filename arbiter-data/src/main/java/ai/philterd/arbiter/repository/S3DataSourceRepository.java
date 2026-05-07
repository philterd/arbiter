package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.S3DataSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface S3DataSourceRepository extends MongoRepository<S3DataSource, String> {
    Optional<S3DataSource> findByName(String name);
}
