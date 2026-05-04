package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class NotificationSettings {

    public static final String SINGLETON_ID = "notifications";

    @Id
    private String id = SINGLETON_ID;

    private boolean enabled;
    private String host;
    private int port = 587;
    private String username;
    private String password;
    private String fromAddress;
    private String fromName;
    private boolean useStartTls = true;
    private boolean useSsl;

    public NotificationSettings() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public boolean isUseStartTls() { return useStartTls; }
    public void setUseStartTls(boolean useStartTls) { this.useStartTls = useStartTls; }

    public boolean isUseSsl() { return useSsl; }
    public void setUseSsl(boolean useSsl) { this.useSsl = useSsl; }
}
