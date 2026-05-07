package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "philter_instances")
public class PhilterInstance {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String endpoint;

    private int port = 8080;

    /**
     * Encrypted API key for this Philter instance. The plaintext is never stored — the value
     * is encrypted with the application's symmetric secret before persistence and decrypted at
     * the moment of use. Null/empty means no API key is configured for the instance.
     */
    private String encryptedApiKey;

    private LocalDateTime createdAt;

    public PhilterInstance() {
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(final String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(final int port) { this.port = port; }

    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(final String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }
}
