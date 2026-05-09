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

public final class Roles {

    /** Full access — manage users, configure integrations, perform every operation. */
    public static final String ADMIN = "ADMIN";

    /** Default role. Group-scoped reviewer with write access inside their groups. */
    public static final String USER = "USER";

    /**
     * Read-only counterpart to {@link #ADMIN}. Sees the same cross-group data an admin
     * sees (queue, search, audit log, batches, reports) but cannot mutate state. Treated
     * as mutually exclusive with USER and ADMIN at assignment time, even though the role
     * set technically supports multiple roles per user.
     */
    public static final String AUDITOR = "AUDITOR";

    private Roles() {
    }
}
