package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.Set;

@Document(collection = "groups")
public class Group {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private Set<String> userIds = new HashSet<>();

    private LocalDateTime createdAt;

    public Group() {
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Set<String> getUserIds() {
        return userIds;
    }

    public void setUserIds(final Set<String> userIds) {
        this.userIds = userIds;
    }
}
