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
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.service.RedactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RedactionController.class)
public class RedactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RedactionService redactionService;

    @Test
    public void testIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    public void testRedactText() throws Exception {
        String text = "George Washington lived in Mount Vernon.";
        RedactionResponse response = new RedactionResponse(text, text, List.of(
                new Redaction(UUID.randomUUID().toString(), "George Washington", 0, 17, "PERSON")
        ));

        when(redactionService.redactText(any())).thenReturn(response);

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", text.getBytes());

        mockMvc.perform(multipart("/redact").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("redact"))
                .andExpect(model().attributeExists("redactionResponse"))
                .andExpect(model().attribute("fileName", "test.txt"));
    }
}
