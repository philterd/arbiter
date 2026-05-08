/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
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
    public void setId(final String id) { this.id = id; }

    public String getDefaultInstanceId() { return defaultInstanceId; }
    public void setDefaultInstanceId(final String defaultInstanceId) { this.defaultInstanceId = defaultInstanceId; }
}
