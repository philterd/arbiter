package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "audit_log")
public class AuditLog {

    @Id
    private String id;

    @Indexed
    private Instant timestamp;

    @Indexed
    private String userEmail;

    private String userId;

    @Indexed
    private String action;

    @Indexed
    private String resourceType;

    @Indexed
    private String resourceId;

    private String outcome;

    private String ipAddress;

    private Map<String, Object> details;

    public AuditLog() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(final String userEmail) { this.userEmail = userEmail; }

    public String getUserId() { return userId; }
    public void setUserId(final String userId) { this.userId = userId; }

    public String getAction() { return action; }
    public void setAction(final String action) { this.action = action; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(final String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(final String resourceId) { this.resourceId = resourceId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(final String outcome) { this.outcome = outcome; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(final String ipAddress) { this.ipAddress = ipAddress; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(final Map<String, Object> details) { this.details = details; }
}
