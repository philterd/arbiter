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
package ai.philterd.arbiter.core.model;

import java.util.List;

public class RedactionResponse {
    private String originalText;
    private String redactedText;
    private List<Redaction> redactions;

    public RedactionResponse() {
    }

    public RedactionResponse(String originalText, String redactedText, List<Redaction> redactions) {
        this.originalText = originalText;
        this.redactedText = redactedText;
        this.redactions = redactions;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getRedactedText() {
        return redactedText;
    }

    public void setRedactedText(String redactedText) {
        this.redactedText = redactedText;
    }

    public List<Redaction> getRedactions() {
        return redactions;
    }

    public void setRedactions(List<Redaction> redactions) {
        this.redactions = redactions;
    }
}
