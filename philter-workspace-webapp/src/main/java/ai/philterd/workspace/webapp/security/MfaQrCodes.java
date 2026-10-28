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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Draws the QR code a person scans to enroll in MFA, for an enrollment Philter started. */
@Service
public class MfaQrCodes {

    /** A base64-encoded PNG data URI for a QR code of {@code otpauthUri} as given. */
    public String dataUri(final String otpauthUri) {
        try {
            final BitMatrix matrix = new QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, 350, 350);
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }
}
