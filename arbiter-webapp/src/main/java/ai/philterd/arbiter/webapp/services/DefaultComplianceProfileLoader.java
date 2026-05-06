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
package ai.philterd.arbiter.webapp.services;

import ai.philterd.arbiter.model.ComplianceProfile;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@Order(1)
public class DefaultComplianceProfileLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultComplianceProfileLoader.class);

    private final ComplianceProfileRepository complianceProfileRepository;

    public DefaultComplianceProfileLoader(final ComplianceProfileRepository complianceProfileRepository) {
        this.complianceProfileRepository = complianceProfileRepository;
    }

    @Override
    public void run(final ApplicationArguments args) {
        seedProfile("HIPAA",
                List.of("NAME", "GEO", "DATE", "PHONE", "ID", "BIO", "PHOTO"));
        seedProfile("FOIA",
                List.of("(b)(1) National Security", "(b)(2) Internal Personnel Rules", "(b)(3) Statutory Prohibition", "(b)(4) Trade Secrets", "(b)(5) Privileged Communication", "(b)(6) Personal Privacy", "(b)(7) Law Enforcement", "(b)(9) Geological Information"));
        seedProfile("General",
                List.of("Third-Party Privacy", "Confidential Business Info", "Legal Privilege",
                        "Security Risk", "Non-Responsive"));
    }

    private void seedProfile(final String name, final List<String> exemptionCodes) {
        if (complianceProfileRepository.findByName(name).isPresent()) {
            return;
        }
        final ComplianceProfile profile = new ComplianceProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(name);
        profile.setExemptionCodes(exemptionCodes);
        profile.setPreset(true);
        profile.setCreatedAt(LocalDateTime.now());
        complianceProfileRepository.save(profile);
        log.info("Seeded default compliance profile '{}'.", name);
    }
}
