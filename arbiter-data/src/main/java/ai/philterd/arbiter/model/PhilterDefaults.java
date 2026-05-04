package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class PhilterDefaults {

    public static final String SINGLETON_ID = "philter-defaults";

    @Id
    private String id = SINGLETON_ID;

    private String defaultInstanceId;

    public PhilterDefaults() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDefaultInstanceId() { return defaultInstanceId; }
    public void setDefaultInstanceId(String defaultInstanceId) { this.defaultInstanceId = defaultInstanceId; }
}
