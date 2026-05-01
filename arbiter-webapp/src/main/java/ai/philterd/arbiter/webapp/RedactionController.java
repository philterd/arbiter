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

import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.service.RedactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class RedactionController {

    private static final Logger log = LoggerFactory.getLogger(RedactionController.class);

    private final RedactionService redactionService;

    public RedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/redact")
    public String redact(@RequestParam("file") MultipartFile file, Model model, HttpSession session) throws IOException {
        String contentType = file.getContentType();
        byte[] fileBytes = file.getBytes();
        session.setAttribute("originalFile", fileBytes);
        
        RedactionResponse response;

        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            response = redactionService.redactPdf(new ByteArrayInputStream(fileBytes));
        } else {
            String text = new String(fileBytes, StandardCharsets.UTF_8);
            response = redactionService.redactText(text);
        }

        model.addAttribute("redactionResponse", response);
        model.addAttribute("fileName", file.getOriginalFilename());
        model.addAttribute("contentType", contentType);

        return "redact";
    }

    @PostMapping("/preview")
    public ResponseEntity<Resource> preview(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @RequestBody RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            if (originalFile != null) {
                byte[] redactedPdf = redactionService.getRedactedPdf(new ByteArrayInputStream(originalFile), redactionResponse);
                ByteArrayResource resource = new ByteArrayResource(redactedPdf);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(resource);
            }
        }

        return ResponseEntity.notFound().build();
    }

    @PostMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @ModelAttribute RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        byte[] content;
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            if (originalFile != null) {
                content = redactionService.getRedactedPdf(new ByteArrayInputStream(originalFile), redactionResponse);
            } else {
                content = redactedText.getBytes(StandardCharsets.UTF_8);
                fileName = fileName.replace(".pdf", "_redacted.txt");
                contentType = MediaType.TEXT_PLAIN_VALUE;
            }
        } else {
            content = redactedText.getBytes(StandardCharsets.UTF_8);
            fileName = fileName.replace(".", "_redacted.");
        }

        ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
