/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "compliance_profiles")
public class ComplianceProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private List<ExemptionCode> exemptionCodes = new ArrayList<>();

    private boolean preset;

    private boolean archived;

    private LocalDateTime createdAt;

    private LocalDateTime archivedAt;

    public ComplianceProfile() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public List<ExemptionCode> getExemptionCodes() { return exemptionCodes; }
    public void setExemptionCodes(final List<ExemptionCode> exemptionCodes) { this.exemptionCodes = exemptionCodes; }

    public boolean isPreset() { return preset; }
    public void setPreset(final boolean preset) { this.preset = preset; }

    public boolean isArchived() { return archived; }
    public void setArchived(final boolean archived) { this.archived = archived; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(final LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
