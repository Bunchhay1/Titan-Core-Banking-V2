package com.titan.titancorebanking.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    @GetMapping("/{accountId}/pdf")
    public ResponseEntity<byte[]> generatePdf(@PathVariable Long accountId) {
        // Mock PDF Content
        String dummyContent = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n... (Statement for Account " + accountId + ") ...";
        byte[] pdfBytes = dummyContent.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}