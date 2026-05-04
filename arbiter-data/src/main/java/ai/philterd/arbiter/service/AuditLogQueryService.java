package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.AuditLog;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AuditLogQueryService {

    private final MongoOperations mongoOperations;

    public AuditLogQueryService(final MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    public List<AuditLog> find(final Instant start,
                               final Instant end,
                               final String userEmail,
                               final String resourceType,
                               final String resourceId,
                               final int limit) {
        final List<Criteria> clauses = new ArrayList<>();
        if (start != null && end != null) {
            clauses.add(Criteria.where("timestamp").gte(start).lte(end));
        } else if (start != null) {
            clauses.add(Criteria.where("timestamp").gte(start));
        } else if (end != null) {
            clauses.add(Criteria.where("timestamp").lte(end));
        }
        if (userEmail != null && !userEmail.isBlank()) {
            clauses.add(Criteria.where("userEmail").is(userEmail.trim()));
        }
        if (resourceType != null && !resourceType.isBlank()) {
            clauses.add(Criteria.where("resourceType").is(resourceType.trim()));
        }
        if (resourceId != null && !resourceId.isBlank()) {
            clauses.add(Criteria.where("resourceId").is(resourceId.trim()));
        }

        final Query query = new Query();
        if (!clauses.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(clauses.toArray(new Criteria[0])));
        }
        query.with(Sort.by(Sort.Direction.ASC, "timestamp"));
        if (limit > 0) {
            query.limit(limit);
        }
        return mongoOperations.find(query, AuditLog.class);
    }
}
