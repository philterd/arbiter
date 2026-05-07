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
    public void setId(final String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(final boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(final String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(final int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(final String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(final String password) { this.password = password; }

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(final String fromAddress) { this.fromAddress = fromAddress; }

    public String getFromName() { return fromName; }
    public void setFromName(final String fromName) { this.fromName = fromName; }

    public boolean isUseStartTls() { return useStartTls; }
    public void setUseStartTls(final boolean useStartTls) { this.useStartTls = useStartTls; }

    public boolean isUseSsl() { return useSsl; }
    public void setUseSsl(final boolean useSsl) { this.useSsl = useSsl; }
}
