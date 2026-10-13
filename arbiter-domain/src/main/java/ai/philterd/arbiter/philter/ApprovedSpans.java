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
package ai.philterd.arbiter.philter;

import ai.philterd.arbiter.core.model.Redaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies reviewer-approved spans to text.
 */
public final class ApprovedSpans {

    private ApprovedSpans() {
    }

    /** Replace each span with its upper-cased type, working from the end so offsets stay valid. */
    public static String apply(final String text, final List<Redaction> approvedSpans) {
        final StringBuilder sb = new StringBuilder(text);
        final List<Redaction> sorted = new ArrayList<>(approvedSpans);
        sorted.sort(Comparator.comparingInt(Redaction::getStart).reversed());
        for (Redaction span : sorted) {
            sb.replace(span.getStart(), span.getEnd(), span.getType().toUpperCase());
        }
        return sb.toString();
    }
}
