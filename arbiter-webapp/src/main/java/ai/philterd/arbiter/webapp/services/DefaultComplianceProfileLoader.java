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
import ai.philterd.arbiter.model.ExemptionCode;
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
        seedProfile("HIPAA", List.of(
                new ExemptionCode("NAME", "Individual's full or partial name (first, last, or initials)"),
                new ExemptionCode("GEO", "Geographic data smaller than a state, such as addresses, ZIP codes, or counties"),
                new ExemptionCode("DATE", "Dates directly related to an individual, including birth, death, admission, and discharge dates"),
                new ExemptionCode("PHONE", "Telephone and fax numbers"),
                new ExemptionCode("ID", "Social security, account, certificate, or license numbers"),
                new ExemptionCode("BIO", "Biometric identifiers including fingerprints and voice prints"),
                new ExemptionCode("PHOTO", "Full-face photographs and comparable identifying images")));
        seedProfile("FOIA", List.of(
                new ExemptionCode("(b)(1) National Security", "Classified national defense and foreign policy information"),
                new ExemptionCode("(b)(2) Internal Personnel Rules", "Internal agency personnel rules and practices not of public interest"),
                new ExemptionCode("(b)(3) Statutory Prohibition", "Information explicitly exempt under another federal statute"),
                new ExemptionCode("(b)(4) Trade Secrets", "Confidential business information and trade secrets obtained from private parties"),
                new ExemptionCode("(b)(5) Privileged Communication", "Inter- or intra-agency privileged communications, including deliberative process and attorney-client materials"),
                new ExemptionCode("(b)(6) Personal Privacy", "Personnel, medical, and similar files whose disclosure would constitute a clearly unwarranted invasion of personal privacy"),
                new ExemptionCode("(b)(7) Law Enforcement", "Records compiled for law enforcement purposes where disclosure could harm the investigation or individuals involved"),
                new ExemptionCode("(b)(9) Geological Information", "Geological and geophysical information and data, including maps, concerning wells")));
        seedProfile("General", List.of(
                new ExemptionCode("Third-Party Privacy", "Information about individuals other than the requester whose privacy interests outweigh the public benefit of disclosure"),
                new ExemptionCode("Confidential Business Info", "Proprietary or commercially sensitive business data provided in confidence"),
                new ExemptionCode("Legal Privilege", "Material protected by attorney-client privilege or the attorney work-product doctrine"),
                new ExemptionCode("Security Risk", "Information that could create a security vulnerability or endanger individuals if disclosed"),
                new ExemptionCode("Non-Responsive", "Content outside the specific scope of the request and therefore not subject to release")));
    }

    private void seedProfile(final String name, final List<ExemptionCode> exemptionCodes) {
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
