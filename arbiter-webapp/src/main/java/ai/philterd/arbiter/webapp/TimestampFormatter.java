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
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.service.GeneralSettingsService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Spring bean exposed to Thymeleaf via {@code @timestampFormatter}. Treats stored
 * {@link LocalDateTime} values as being in the JVM's default zone (which is what
 * {@code LocalDateTime.now()} produced) and converts to the timezone configured
 * under Admin → General before formatting.
 */
@Component("timestampFormatter")
public class TimestampFormatter {

    private final GeneralSettingsService generalSettingsService;

    public TimestampFormatter(final GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    public String format(final LocalDateTime value, final String pattern) {
        if (value == null) return "";
        final ZoneId target = displayZone();
        final ZonedDateTime zoned = value.atZone(ZoneId.systemDefault()).withZoneSameInstant(target);
        return zoned.format(DateTimeFormatter.ofPattern(pattern));
    }

    /** Format an {@link Instant} for display, using a sensible default pattern. */
    public String format(final Instant value) {
        if (value == null) return "";
        return value.atZone(displayZone()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getZoneId() {
        return displayZone().getId();
    }

    private ZoneId displayZone() {
        final String tz = generalSettingsService.load().getTimezone();
        if (tz == null || tz.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
