/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaQrCodesTest {

    @Test
    void drawsAPngDataUri() {
        final String uri = new MfaQrCodes().dataUri("otpauth://totp/Philter:jdoe?secret=JBSWY3DPEHPK3PXP&issuer=Philter");
        assertTrue(uri.startsWith("data:image/png;base64,"));
        final byte[] png = Base64.getDecoder().decode(uri.substring("data:image/png;base64,".length()));
        assertEquals((byte) 0x89, png[0]);
        assertEquals("PNG", new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
