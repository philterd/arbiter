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
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterClientFactory;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RedactionServiceSwitchTest {

    @Mock
    private PhilterClient phileasClient;

    @Mock
    private PhilterClient remotePhilterClient;

    @Mock
    private PhilterClientFactory philterClientFactory;

    @Mock
    private PhilterInstanceRepository philterInstanceRepository;

    private RedactionServiceImpl redactionService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        redactionService = new RedactionServiceImpl(phileasClient, philterClientFactory,
                philterInstanceRepository);
    }

    @Test
    public void testUsePhilterWhenInstanceProvided() throws Exception {
        final PhilterInstance instance = new PhilterInstance();
        instance.setId("philter-1");
        instance.setName("primary");
        instance.setEndpoint("philter");
        instance.setPort(8080);

        when(philterInstanceRepository.findById("philter-1")).thenReturn(Optional.of(instance));
        when(philterClientFactory.create("http://philter:8080")).thenReturn(remotePhilterClient);

        final RedactionResponse philterResponse = new RedactionResponse("test", "philter", new ArrayList<>());
        when(remotePhilterClient.redact(anyString(), anyString())).thenReturn(philterResponse);

        final RedactionResponse response = redactionService.redactText("test", "philter-1");

        assertEquals("philter", response.getRedactedText());
        verify(remotePhilterClient, times(1)).redact(anyString(), anyString());
        verify(phileasClient, never()).redact(anyString(), anyString());
    }

    @Test
    public void testUsePhileasWhenNoInstanceProvided() throws Exception {
        final RedactionResponse phileasResponse = new RedactionResponse("test", "phileas", new ArrayList<>());
        when(phileasClient.redact(anyString(), anyString())).thenReturn(phileasResponse);

        final RedactionResponse response = redactionService.redactText("test", null);

        assertEquals("phileas", response.getRedactedText());
        verify(phileasClient, times(1)).redact(anyString(), anyString());
        verify(philterClientFactory, never()).create(anyString());
    }

    @Test
    public void testUsePhileasWhenInstanceMissing() throws Exception {
        when(philterInstanceRepository.findById("ghost")).thenReturn(Optional.empty());

        final RedactionResponse phileasResponse = new RedactionResponse("test", "phileas", new ArrayList<>());
        when(phileasClient.redact(anyString(), anyString())).thenReturn(phileasResponse);

        final RedactionResponse response = redactionService.redactText("test", "ghost");

        assertEquals("phileas", response.getRedactedText());
        verify(phileasClient, times(1)).redact(anyString(), anyString());
        verify(philterClientFactory, never()).create(anyString());
    }
}
