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
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.service.AuditLogService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AuditAuthEventListener {

    private final AuditLogService auditLogService;

    public AuditAuthEventListener(final AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @EventListener
    public void onAuthSuccess(final AuthenticationSuccessEvent event) {
        final String email = event.getAuthentication() == null ? null : event.getAuthentication().getName();
        auditLogService.logForUser(email, "LOGIN", "User", null,
                AuditLogService.OUTCOME_SUCCESS, null);
    }

    @EventListener
    public void onAuthFailure(final AbstractAuthenticationFailureEvent event) {
        final String email = event.getAuthentication() == null ? null : String.valueOf(event.getAuthentication().getName());
        final Map<String, Object> details = Map.of(
                "reason", event.getException() == null ? "unknown" : event.getException().getClass().getSimpleName());
        auditLogService.logForUser(email, "LOGIN", "User", null,
                AuditLogService.OUTCOME_FAILURE, details);
    }
}
