package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "opensearch_data_sources")
public class OpenSearchDataSource {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String endpoint;

    /**
     * OpenSearch query (typically JSON DSL, e.g. {@code GET /index/_search} body) that returns
     * the documents to import.
     */
    private String query;

    private String username;

    /**
     * AES-GCM ciphertext of the OpenSearch password (see {@code SymmetricCipher}). Null/empty
     * means no credentials are configured for the instance.
     */
    private String encryptedPassword;

    private LocalDateTime createdAt;

    public OpenSearchDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(final String endpoint) { this.endpoint = endpoint; }

    public String getQuery() { return query; }
    public void setQuery(final String query) { this.query = query; }

    public String getUsername() { return username; }
    public void setUsername(final String username) { this.username = username; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(final String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
