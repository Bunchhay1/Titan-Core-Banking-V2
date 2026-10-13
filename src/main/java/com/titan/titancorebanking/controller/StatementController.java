package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.service.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Statement API", description = "Secure PDF Bank Statement generation and download")
public class StatementController {

    private final StatementService statementService;

    @GetMapping("/{accountId}/pdf")
    @Operation(summary = "Download Account Statement PDF", description = "Generates and streams a secure PDF statement for the specified account.")
    @PreAuthorize("@accountSecurity.isAccountOwner(authentication, #accountId) or hasRole('ADMIN')")
    public ResponseEntity<byte[]> generatePdf(
            @PathVariable Long accountId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("PDF statement download requested for accountId={} by user={}", accountId, userDetails.getUsername());

        byte[] pdfBytes = statementService.generateAccountStatementPdf(accountId, userDetails.getUsername());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "titan-statement-" + accountId + ".pdf");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }
}