package ai.philterd.arbiter.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "local_directory_data_sources")
public class LocalDirectoryDataSource {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    /** Absolute filesystem path on the server, e.g. {@code /var/lib/arbiter/incoming}. */
    private String directoryPath;

    /** File glob filter applied within the directory, e.g. {@code *.txt} or {@code **\/*.pdf}. */
    private String filenameGlob;

    private LocalDateTime createdAt;

    public LocalDirectoryDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getDirectoryPath() { return directoryPath; }
    public void setDirectoryPath(final String directoryPath) { this.directoryPath = directoryPath; }

    public String getFilenameGlob() { return filenameGlob; }
    public void setFilenameGlob(final String filenameGlob) { this.filenameGlob = filenameGlob; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
