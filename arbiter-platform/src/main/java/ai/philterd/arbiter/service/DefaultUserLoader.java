/*
 * Copyright 2026 Philterd, LLC.
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
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.InboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class DefaultUserLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserLoader.class);

    /**
     * Default email for the bootstrap admin created on a fresh install. The operator
     * is expected to change the password on first login.
     */
    private static final String DEFAULT_ADMIN_EMAIL = "admin@philterd.ai";

    /**
     * Mandatory environment variable carrying the bootstrap admin's initial
     * password. On a fresh install (no admin exists) the variable must be set
     * to a non-blank value of at least {@link #MIN_INITIAL_PASSWORD_LENGTH}
     * characters, or the application refuses to start.
     *
     * <p>Previously this was optional and the loader would generate a random
     * password and print it to {@code System.out} for the operator to copy.
     * That path left the credential in journalctl / Docker / k8s pod logs and
     * any log aggregator forever — explicitly contradicting CLAUDE.md's
     * "Strict Prohibition on PII / credentials in System.out". Fail-fast on
     * missing config matches the existing pattern for {@code arbiter.crypto.secret}.
     */
    static final String INITIAL_PASSWORD_ENV_VAR = "ARBITER_ADMIN_INITIAL_PASSWORD";

    /** Minimum length for a configured initial password — matches the policy enforced
     *  on /settings/password and the Admin → Users add form. */
    static final int MIN_INITIAL_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InboxService inboxService;
    /** Initial password, resolved by Spring from the {@code arbiter.admin.initial-password}
     *  property — which Spring's relaxed binding also populates from the
     *  {@link #INITIAL_PASSWORD_ENV_VAR} environment variable. May be blank on a
     *  fresh install; {@link #requireConfiguredPassword()} fail-fasts in that case. */
    private final String initialPassword;
    /** In {@code philter} sign-in mode the administrators are Philter's, so none is seeded here. */
    private final boolean philterSignIn;

    public DefaultUserLoader(final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final InboxService inboxService,
                             final String initialPassword) {
        this(userRepository, passwordEncoder, inboxService, initialPassword, SignInSettings.MODE_LOCAL);
    }

    @Autowired
    public DefaultUserLoader(final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final InboxService inboxService,
                             @Value("${arbiter.admin.initial-password:}") final String initialPassword,
                             @Value("${arbiter.sign-in.mode:local}") final String signInMode) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.inboxService = inboxService;
        this.initialPassword = initialPassword;
        this.philterSignIn = SignInSettings.MODE_PHILTER.equalsIgnoreCase(signInMode == null ? "" : signInMode.trim());
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (philterSignIn) {
            clearApiKeys();
            return;
        }
        // Bootstrap is keyed on whether *any* admin exists, not on whether the user
        // table is empty. If every admin has been deleted (or only USER/AUDITOR
        // accounts remain) the system would otherwise be unrecoverable, so seed a
        // fresh admin in that case too.
        // An admin row from Philter sign-in has no local password and cannot sign in here, so
        // after switching back to local sign-in only an admin with a password counts.
        if (userRepository.countByRolesContaining(Roles.ADMIN) > 0 && localAdminExists()) {
            return;
        }

        final String password = requireConfiguredPassword();

        final User admin = userRepository.findByEmail(DEFAULT_ADMIN_EMAIL).orElseGet(User::new);
        if (admin.getId() == null) {
            admin.setId(UUID.randomUUID().toString());
        }
        admin.setCreatedAt(LocalDateTime.now());
        admin.setEmail(DEFAULT_ADMIN_EMAIL);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRoles(Set.of(Roles.ADMIN));
        // The operator deliberately supplied the password via the env var, so
        // they already control where it lives (compose file, secret store).
        // Don't force a rotation — that would defeat the point of configuring
        // a known credential.
        admin.setMustChangePassword(false);
        userRepository.save(admin);
        inboxService.sendHtml(admin.getId(), WelcomeMessage.html(true));
        log.info("Seeded default admin '{}' using password from {}.",
                admin.getEmail(), INITIAL_PASSWORD_ENV_VAR);
    }

    /**
     * Arbiter API keys are refused with Philter sign-in, since a key is checked only against Arbiter's
     * record. Clearing them keeps any from working again if the deployment returns to local sign-in,
     * for example one belonging to someone since deactivated in Philter.
     */
    private void clearApiKeys() {
        int cleared = 0;
        for (final User user : userRepository.findAll()) {
            if (user.getApiKey() != null) {
                user.setApiKey(null);
                userRepository.save(user);
                cleared++;
            }
        }
        if (cleared > 0) {
            log.info("Cleared {} Arbiter API key(s): API keys are not used with Philter sign-in.", cleared);
        }
    }

    private boolean localAdminExists() {
        return userRepository.findAll().stream().anyMatch(u -> u.getRoles() != null
                && u.getRoles().contains(Roles.ADMIN)
                && u.getPasswordHash() != null && !u.getPasswordHash().isBlank());
    }

    /**
     * Returns the trimmed, length-validated password from
     * {@link #INITIAL_PASSWORD_ENV_VAR}, or throws {@link IllegalStateException}
     * with an actionable message if the variable is unset, blank, or shorter
     * than {@link #MIN_INITIAL_PASSWORD_LENGTH}. Fail-fast prevents the previous
     * "fall back to a generated password printed on stdout" behaviour, which
     * leaked the credential to every captured log stream.
     */
    private String requireConfiguredPassword() {
        if (initialPassword == null || initialPassword.isBlank()) {
            throw new IllegalStateException(
                    INITIAL_PASSWORD_ENV_VAR + " must be set on first startup so the bootstrap "
                            + "admin can be seeded. Set it to a password of at least "
                            + MIN_INITIAL_PASSWORD_LENGTH + " characters in your deployment "
                            + "config (compose env, k8s Secret, etc.).");
        }
        if (initialPassword.length() < MIN_INITIAL_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    INITIAL_PASSWORD_ENV_VAR + " is set but shorter than the "
                            + MIN_INITIAL_PASSWORD_LENGTH + "-character minimum. "
                            + "Set a stronger value before starting Arbiter.");
        }
        return initialPassword;
    }
}
