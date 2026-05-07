package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "policies")
public class Policy {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String content;

    private LocalDateTime createdAt;

    public Policy() {
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(final String content) { this.content = content; }
}
