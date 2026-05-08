/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimestampFormatterTest {

    private GeneralSettingsService settingsService(final String tz) {
        final GeneralSettingsService svc = mock(GeneralSettingsService.class);
        final GeneralSettings s = new GeneralSettings();
        s.setTimezone(tz);
        when(svc.load()).thenReturn(s);
        return svc;
    }

    @Test
    void nullValueReturnsEmptyString() {
        assertEquals("", new TimestampFormatter(settingsService("UTC")).format(null, "yyyy-MM-dd"));
    }

    @Test
    void invalidZoneFallsBackToUtc() {
        final TimestampFormatter f = new TimestampFormatter(settingsService("Atlantis/Trench"));
        // Should not throw; the method falls back to UTC silently.
        assertEquals("UTC", f.getZoneId());
        // Format works without exception.
        assertNotNull(f.format(LocalDateTime.of(2026, 5, 4, 12, 0), "yyyy"));
    }

    @Test
    void blankZoneFallsBackToUtc() {
        assertEquals("UTC", new TimestampFormatter(settingsService(" ")).getZoneId());
        assertEquals("UTC", new TimestampFormatter(settingsService(null)).getZoneId());
    }

    @Test
    void convertsToConfiguredZone() {
        final TimestampFormatter f = new TimestampFormatter(settingsService("America/New_York"));

        // Take an instant well-known to be 2026-06-01T12:00 UTC, expressed in the JVM zone.
        final ZonedDateTime utcMidday = ZonedDateTime.of(LocalDateTime.of(2026, 6, 1, 12, 0), ZoneId.of("UTC"));
        final LocalDateTime asJvmLocal = utcMidday.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

        // In June, NY is UTC-4. So 12:00 UTC → 08:00 New York.
        final String formatted = f.format(asJvmLocal, "HH:mm");
        assertEquals("08:00", formatted);
    }
}
