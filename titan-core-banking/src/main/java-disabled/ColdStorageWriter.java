package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class ColdStorageWriter implements ItemWriter<Transaction> {

    private final S3Client s3Client;
    private final TransactionRepository transactionRepository;

    @Value("${cold-storage.s3.bucket:titan-archive}")
    private String bucketName;

    @Value("${cold-storage.encryption.key}")
    private String encryptionKey;

    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        String fileName = "transactions_archive_" + LocalDate.now() + ".parquet.enc";
        Path tempFile = Files.createTempFile("titan-archive-", ".parquet");

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
            // Write Parquet-like CSV format
            writer.write("id,transaction_reference,type,amount,status,timestamp,from_account,to_account\n");
            for (Transaction tx : chunk.getItems()) {
                writer.write(String.format("%d,%s,%s,%s,%s,%s,%d,%d\n",
                        tx.getId(),
                        tx.getTransactionReference(),
                        tx.getTransactionType(),
                        tx.getAmount(),
                        tx.getStatus(),
                        tx.getTimestamp(),
                        tx.getFromAccount() != null ? tx.getFromAccount().getId() : 0,
                        tx.getToAccount() != null ? tx.getToAccount().getId() : 0
                ));
            }
        }

        // Encrypt file
        byte[] encryptedData = encryptFile(Files.readAllBytes(tempFile));
        
        // Upload to S3
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key("archives/" + fileName)
                        .build(),
                RequestBody.fromBytes(encryptedData)
        );

        // Delete from database
        chunk.getItems().forEach(tx -> transactionRepository.deleteById(tx.getId()));
        
        log.info("Archived {} transactions to S3: {}", chunk.size(), fileName);
        Files.delete(tempFile);
    }

    private byte[] encryptFile(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey key = new javax.crypto.spec.SecretKeySpec(
                Base64.getDecoder().decode(encryptionKey), "AES");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(data);
        
        // Prepend IV to encrypted data
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return result;
    }
}
