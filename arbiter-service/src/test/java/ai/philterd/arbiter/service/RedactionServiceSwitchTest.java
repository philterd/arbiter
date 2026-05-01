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
import ai.philterd.arbiter.philter.PhilterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RedactionServiceSwitchTest {

    @Mock
    private PhilterClient philterClient;

    @Mock
    private PhilterClient phileasClient;

    private RedactionServiceImpl redactionService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        redactionService = new RedactionServiceImpl(philterClient, phileasClient);
    }

    @Test
    public void testUsePhilterWhenUrlSet() throws Exception {
        ReflectionTestUtils.setField(redactionService, "philterUrl", "http://some-philter-url");
        
        RedactionResponse philterResponse = new RedactionResponse("test", "philter", new ArrayList<>());
        when(philterClient.redact(anyString(), anyString())).thenReturn(philterResponse);

        RedactionResponse response = redactionService.redactText("test");

        assertEquals("philter", response.getRedactedText());
        verify(philterClient, times(1)).redact(anyString(), anyString());
        verify(phileasClient, never()).redact(anyString(), anyString());
    }

    @Test
    public void testUsePhileasWhenUrlNotSet() throws Exception {
        ReflectionTestUtils.setField(redactionService, "philterUrl", "");
        
        RedactionResponse phileasResponse = new RedactionResponse("test", "phileas", new ArrayList<>());
        when(phileasClient.redact(anyString(), anyString())).thenReturn(phileasResponse);

        RedactionResponse response = redactionService.redactText("test");

        assertEquals("phileas", response.getRedactedText());
        verify(phileasClient, times(1)).redact(anyString(), anyString());
        verify(philterClient, never()).redact(anyString(), anyString());
    }

    @Test
    public void testUsePhileasWhenUrlIsNull() throws Exception {
        ReflectionTestUtils.setField(redactionService, "philterUrl", null);
        
        RedactionResponse phileasResponse = new RedactionResponse("test", "phileas", new ArrayList<>());
        when(phileasClient.redact(anyString(), anyString())).thenReturn(phileasResponse);

        RedactionResponse response = redactionService.redactText("test");

        assertEquals("phileas", response.getRedactedText());
        verify(phileasClient, times(1)).redact(anyString(), anyString());
        verify(philterClient, never()).redact(anyString(), anyString());
    }
}
