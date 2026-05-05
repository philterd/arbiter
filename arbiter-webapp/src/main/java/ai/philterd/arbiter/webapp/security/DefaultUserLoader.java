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

import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class DefaultUserLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserLoader.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InboxService inboxService;

    public DefaultUserLoader(final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final InboxService inboxService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.inboxService = inboxService;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        final User admin = new User();
        admin.setCreatedAt(LocalDateTime.now());
        admin.setId(UUID.randomUUID().toString());
        admin.setEmail("admin@philterd.ai");
        admin.setPasswordHash(passwordEncoder.encode("admin"));
        admin.setRoles(Set.of(Roles.ADMIN));
        userRepository.save(admin);
        inboxService.sendHtml(admin.getId(), WelcomeMessage.html(true));
        log.info("Seeded default user '{}' with role {}.", admin.getEmail(), Roles.ADMIN);
    }
}
