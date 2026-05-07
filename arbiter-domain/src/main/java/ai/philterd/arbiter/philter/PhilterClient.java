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
package ai.philterd.arbiter.philter;

import ai.philterd.arbiter.core.model.RedactionResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface PhilterClient {
    RedactionResponse redact(String text, String context) throws IOException;
    RedactionResponse redactPdf(byte[] pdfBytes, String context) throws IOException;
    Map<String, Object> explain(String text, String context) throws IOException;
    String redact(String text, String context, List<ai.philterd.arbiter.core.model.Redaction> approvedSpans) throws IOException;
}
