package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.LocalDirectoryDataSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocalDirectoryDataSourceRepository extends MongoRepository<LocalDirectoryDataSource, String> {
    Optional<LocalDirectoryDataSource> findByName(String name);
    Optional<LocalDirectoryDataSource> findFirstByNameIgnoreCase(String name);
}
