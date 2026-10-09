package com.titan.titancorebanking.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

/**
 * Statement generation controller.
 *
 * SECURITY FIX: Now validates account ownership before generating statements.
 * TODO: Replace mock PDF with actual PDF generation library (e.g., iText, Apache PDFBox).
 */
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@Slf4j
public class StatementController {

    /**
     * Generate PDF statement for an account.
     *
     * BUG FIXES:
     * 1. Added ownership validation via @PreAuthorize - prevents unauthorized statement access (IDOR)
     * 2. Clearly marked as mock/placeholder implementation - not production-ready PDF
     *
     * SECURITY: User can only access their own account statements, or ADMIN can access any.
     *
     * TODO - PRODUCTION REQUIREMENTS:
     * 1. Replace mock PDF with real PDF generation:
     *    - Option A: iText 7 (commercial license for commercial use)
     *    - Option B: Apache PDFBox (Apache License 2.0, free)
     *    - Option C: Flying Saucer (LGPL, renders HTML to PDF)
     *
     * 2. Include actual statement data:
     *    - Account holder name, address
     *    - Statement period (start date, end date)
     *    - Opening balance, closing balance
     *    - All transactions in the period (date, description, debit, credit, balance)
     *    - Bank logo and branding
     *    - Digital signature or verification code
     *
     * 3. Add statement archiving:
     *    - Store generated PDFs in S3 or file storage
     *    - Track generation date and requesting user for audit
     *    - Implement statement history endpoint
     *
     * 4. Add rate limiting to prevent abuse (expensive operation)
     *
     * @param accountId Account ID for statement generation
     * @return Mock PDF statement (PLACEHOLDER - NOT PRODUCTION-READY)
     */
    @GetMapping("/{accountId}/pdf")
    @PreAuthorize("@accountSecurity.isAccountOwner(authentication, #accountId) or hasRole('ADMIN')")
    public ResponseEntity<byte[]> generatePdf(@PathVariable Long accountId) {
        log.warn("⚠️ MOCK PDF GENERATION: Generating placeholder PDF for account {}. NOT PRODUCTION-READY!", accountId);

        /*
         * ⚠️ WARNING: This is a MOCK implementation!
         * Real PDF generation requires:
         * 1. PDF library dependency (iText, PDFBox, etc.)
         * 2. Query transactions for the account and statement period
         * 3. Format data into proper PDF with tables, headers, footers
         * 4. Add bank logo, styling, and legal disclaimers
         */

        // Mock PDF Content (just a string, not a real PDF)
        String dummyContent = String.format("""
                %%PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                2 0 obj
                << /Type /Pages /Kids [3 0 R] /Count 1 >>
                endobj
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
                endobj
                
                ========================================
                TITAN CORE BANKING - ACCOUNT STATEMENT
                ========================================
                
                Account ID: %d
                
                ⚠️ WARNING: THIS IS A MOCK PDF PLACEHOLDER
                
                TODO: Replace with real PDF generation using:
                - iText 7 library
                - Apache PDFBox
                - Or similar PDF generation solution
                
                Real statement should include:
                - Account holder information
                - Statement period
                - Transaction history
                - Opening/closing balance
                - Bank branding
                ========================================
                """, accountId);

        byte[] pdfBytes = dummyContent.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement_" + accountId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}