package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "document_comments")
public class DocumentComment {

    @Id
    private String id;

    @Indexed
    private String documentId;

    private String userEmail;
    private String userId;

    private String text;

    @Indexed
    private Instant timestamp;

    public DocumentComment() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(final String documentId) { this.documentId = documentId; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(final String userEmail) { this.userEmail = userEmail; }

    public String getUserId() { return userId; }
    public void setUserId(final String userId) { this.userId = userId; }

    public String getText() { return text; }
    public void setText(final String text) { this.text = text; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
}
