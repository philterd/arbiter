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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
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
     * Alphabet for the bootstrap password. Excludes characters that are easy to
     * mis-read in a terminal (0/O, 1/l/I) so an operator copying the printed
     * password by hand is less likely to introduce a typo.
     */
    private static final String PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PASSWORD_LENGTH = 24;

    /**
     * Optional environment variable. When set to a non-blank value of at least
     * {@link #MIN_INITIAL_PASSWORD_LENGTH} characters, the bootstrap admin is
     * created with that password instead of a freshly generated one. Useful in
     * containerized deployments where the operator wants a known credential
     * baked into the compose file rather than scraping it out of stdout. The
     * mustChangePassword flag is still set, so the user must rotate it on
     * first sign-in either way.
     */
    private static final String INITIAL_PASSWORD_ENV_VAR = "ARBITER_ADMIN_INITIAL_PASSWORD";

    /** Minimum length for a configured initial password — matches the policy enforced
     *  on /settings/password and the Admin → Users add form. */
    private static final int MIN_INITIAL_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InboxService inboxService;
    private final SecureRandom random = new SecureRandom();

    public DefaultUserLoader(final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final InboxService inboxService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.inboxService = inboxService;
    }

    @Override
    public void run(final ApplicationArguments args) {
        // Bootstrap is keyed on whether *any* admin exists, not on whether the user
        // table is empty. If every admin has been deleted (or only USER/AUDITOR
        // accounts remain) the system would otherwise be unrecoverable, so seed a
        // fresh admin in that case too.
        if (userRepository.countByRolesContaining(Roles.ADMIN) > 0) {
            return;
        }

        // Prefer an operator-supplied password from the environment when one is
        // present and meets the length policy. Falls back to a generated password
        // when the env var is unset, blank, or too short — the latter is logged
        // so a misconfiguration doesn't silently degrade to "we generated one".
        final String configured = readConfiguredPassword();
        final boolean usingConfigured = configured != null;
        final String password = usingConfigured ? configured : generatePassword();

        final User admin = new User();
        admin.setCreatedAt(LocalDateTime.now());
        admin.setId(UUID.randomUUID().toString());
        admin.setEmail(DEFAULT_ADMIN_EMAIL);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRoles(Set.of(Roles.ADMIN));
        // Force a rotation on first login *only* when the password was generated
        // here and printed to stdout — that path leaves the credential briefly
        // visible in start-up logs, so the operator should rotate it as soon as
        // they sign in. When the operator deliberately supplied the password via
        // ARBITER_ADMIN_INITIAL_PASSWORD, they already control where it lives
        // (compose file, secret store, etc.); forcing a rotation would defeat
        // the point of configuring it in the first place.
        admin.setMustChangePassword(!usingConfigured);
        userRepository.save(admin);
        inboxService.sendHtml(admin.getId(), WelcomeMessage.html(true));
        printBootstrapBanner(admin.getEmail(), password, usingConfigured);
        if (usingConfigured) {
            log.info("Seeded default admin '{}' using password from {}.",
                    admin.getEmail(), INITIAL_PASSWORD_ENV_VAR);
        } else {
            log.info("Seeded default admin '{}' with a generated password (printed to stdout).",
                    admin.getEmail());
        }
    }

    /**
     * Returns a trimmed, length-validated password from
     * {@link #INITIAL_PASSWORD_ENV_VAR}, or {@code null} if the variable is
     * unset, blank, or shorter than {@link #MIN_INITIAL_PASSWORD_LENGTH}. A
     * present-but-invalid value is logged at WARN so the operator can see
     * why the system fell back to a generated password.
     */
    private String readConfiguredPassword() {
        final String raw = System.getenv(INITIAL_PASSWORD_ENV_VAR);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() < MIN_INITIAL_PASSWORD_LENGTH) {
            log.warn("{} is set but is shorter than the {}-character minimum; falling back to a generated password.",
                    INITIAL_PASSWORD_ENV_VAR, MIN_INITIAL_PASSWORD_LENGTH);
            return null;
        }
        return raw;
    }

    private String generatePassword() {
        final char[] buf = new char[PASSWORD_LENGTH];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length()));
        }
        return new String(buf);
    }

    /**
     * Prints the bootstrap admin credentials directly to {@code System.out} so an
     * operator who's started the application via {@code docker compose up} or a
     * service supervisor can see them in the captured stdout. Uses a banner that's
     * easy to find in the surrounding log noise — the password line is intentionally
     * unprefixed so it can be selected without any extra trimming.
     *
     * <p>When the password came from {@link #INITIAL_PASSWORD_ENV_VAR}, the banner
     * tells the operator where to look up the value rather than echoing it back —
     * the value already lives in the operator's compose file or secret store and
     * dumping it to stdout adds another exposure surface for no benefit.
     */
    private void printBootstrapBanner(final String email,
                                      final String password,
                                      final boolean usingConfigured) {
        final String divider = "============================================================";
        System.out.println();
        System.out.println(divider);
        System.out.println("  Arbiter bootstrap admin account created");
        System.out.println(divider);
        System.out.println("  Email:    " + email);
        if (usingConfigured) {
            System.out.println("  Password: (from " + INITIAL_PASSWORD_ENV_VAR + " environment variable)");
        } else {
            System.out.println("  Password: " + password);
        }
        System.out.println();
        if (usingConfigured) {
            System.out.println("  Sign in with the password configured in " + INITIAL_PASSWORD_ENV_VAR + ".");
        } else {
            System.out.println("  This password is shown ONCE. Sign in and change it from");
            System.out.println("  Settings -> Account immediately. It is not recoverable.");
        }
        System.out.println(divider);
        System.out.println();
    }
}
