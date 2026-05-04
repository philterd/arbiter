package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.LinkedHashMap;
import java.util.Map;

@Document(collection = "weight_sets")
public class WeightSet {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private Map<String, Integer> weights = new LinkedHashMap<>();

    private LocalDateTime createdAt;

    public WeightSet() {
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public Map<String, Integer> getWeights() { return weights; }
    public void setWeights(final Map<String, Integer> weights) { this.weights = weights; }
}
