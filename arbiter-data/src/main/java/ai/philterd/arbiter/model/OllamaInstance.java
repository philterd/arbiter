package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "ollama_instances")
public class OllamaInstance {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String endpoint;

    private int port = 11434;

    private LocalDateTime createdAt;

    public OllamaInstance() {
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
}
