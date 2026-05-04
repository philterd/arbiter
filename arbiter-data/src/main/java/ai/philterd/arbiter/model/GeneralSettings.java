package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class GeneralSettings {

    public static final String SINGLETON_ID = "general";

    @Id
    private String id = SINGLETON_ID;

    private String arbiterUrl;
    private String timezone;

    public GeneralSettings() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArbiterUrl() { return arbiterUrl; }
    public void setArbiterUrl(String arbiterUrl) { this.arbiterUrl = arbiterUrl; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
