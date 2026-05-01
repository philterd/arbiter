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
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.core.model.RedactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {PhileasClient.class})
public class PhileasClientTest {

    @Autowired
    private PhileasClient phileasClient;

    @Test
    public void testRedactSSN() throws IOException {
        String text = "My SSN is 123-45-6789.";
        String context = UUID.randomUUID().toString();
        
        RedactionResponse response = phileasClient.redact(text, context);
        
        assertNotNull(response);
        assertNotEquals(text, response.getRedactedText());
        assertFalse(response.getRedactions().isEmpty());
        assertEquals("ssn", response.getRedactions().get(0).getType());
        assertTrue(response.getRedactedText().contains("SSN"));
    }
}
