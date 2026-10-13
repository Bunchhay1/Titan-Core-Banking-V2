package com.titan.titancorebanking.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public byte[] generateAccountStatementPdf(Long accountId, String currentUsername) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("Unauthorized access to account statement.");
        }

        List<Transaction> transactions = transactionRepository
                .findTop100ByFromAccountIdOrToAccountIdOrderByTimestampDesc(accountId, accountId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            Document document = new Document(PageSize.A4, 36, 36, 54, 54);
            PdfWriter.getInstance(document, out);
            document.open();

            // --- HEADER SECTION ---
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.DARK_GRAY);
            Paragraph title = new Paragraph("TITAN CORE BANKING - OFFICIAL STATEMENT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            document.add(new Paragraph(" ")); // Spacer

            Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY);
            document.add(new Paragraph("Account Number: " + account.getAccountNumber(), metaFont));
            document.add(new Paragraph("Account Type: " + account.getAccountType(), metaFont));
            document.add(new Paragraph("Currency: " + account.getCurrency(), metaFont));
            document.add(new Paragraph("Current Balance: " + account.getBalance(), metaFont));
            document.add(new Paragraph("Generated At: " + java.time.LocalDateTime.now(), metaFont));

            document.add(new Paragraph("----------------------------------------------------------------------------------------------------------------------------------"));
            document.add(new Paragraph(" "));

            // --- TRANSACTION TABLE ---
            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{2f, 3f, 2f, 2f, 3f});

            addTableHeader(table, "Date", "Type", "Amount", "Status", "Reference / Note");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

            for (Transaction tx : transactions) {
                addTableCell(table, tx.getTimestamp() != null ? tx.getTimestamp().format(formatter) : "N/A");
                addTableCell(table, tx.getTransactionType() != null ? tx.getTransactionType().name() : "UNKNOWN");
                addTableCell(table, tx.getAmount() != null ? tx.getAmount().toPlainString() : "0.00");
                addTableCell(table, tx.getStatus() != null ? tx.getStatus().name() : "N/A");

                String details = tx.getTransactionReference() != null ? tx.getTransactionReference() : tx.getNote();
                addTableCell(table, details != null ? details : "");
            }

            document.add(table);
            document.close();

            log.info("PDF statement successfully generated for accountId={}", accountId);
        } catch (Exception e) {
            log.error("Failed to generate PDF statement for accountId={}: {}", accountId, e.getMessage());
            throw new RuntimeException("Statement generation failed.", e);
        }

        return out.toByteArray();
    }

    private void addTableHeader(PdfPTable table, String... headers) {
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setBackgroundColor(Color.BLUE);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(6);
            table.addCell(cell);
        }
    }

    private void addTableCell(PdfPTable table, String value) {
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
        PdfPCell cell = new PdfPCell(new Phrase(value, cellFont));
        cell.setPadding(5);
        table.addCell(cell);
    }
}