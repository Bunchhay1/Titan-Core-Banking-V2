package com.titan.titancorebanking.merkle;

import com.titan.titancorebanking.model.LedgerEntry;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Task 5: Cryptographic Merkle Tree Ledger Pruning
 * Fixed Spring AOP Proxy bypass by utilizing TransactionTemplate for explicit batch commits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerkleLedgerPruningService {

    private final LedgerRepository ledgerRepository;
    private final MerkleArchiveRepository merkleArchiveRepository;
    private final S3Client s3Client;
    private final TransactionTemplate transactionTemplate;

    @Value("${merkle.archive.bucket:titan-ledger-glacier}")
    private String glacierBucket;

    @Value("${merkle.prune.days-old:90}")
    private int daysOld;

    @Value("${merkle.batch.size:1000}")
    private int batchSize;

    @Scheduled(cron = "0 0 2 1 * *")
    public void pruneAndArchive() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        log.info("Starting Merkle ledger pruning for entries before {}", cutoff);

        int totalPruned = 0;
        boolean hasMore = true;

        while (hasMore) {
            hasMore = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                List<LedgerEntry> batch = ledgerRepository.findOlderThan(cutoff, batchSize);
                if (batch.isEmpty()) {
                    return false;
                }

                String merkleRoot = buildMerkleRoot(batch);
                String archiveKey = archiveToGlacier(batch, merkleRoot, cutoff);

                saveMerkleRecord(merkleRoot, archiveKey, batch.size(),
                        batch.getFirst().getId(), batch.getLast().getId());

                ledgerRepository.deleteAll(batch);
                log.info("Pruned batch of {} entries, merkleRoot={}", batch.size(), merkleRoot);

                return batch.size() == batchSize;
            }));

            if (hasMore) {
                totalPruned += batchSize;
            }
        }
        log.info("  Merkle pruning complete. Total pruned: {}", totalPruned);
    }

    public String buildMerkleRoot(List<LedgerEntry> entries) {
        if (entries.isEmpty()) throw new IllegalArgumentException("Cannot build Merkle tree from empty list");

        List<String> hashes = new ArrayList<>();
        for (LedgerEntry e : entries) {
            hashes.add(sha256(canonicalize(e)));
        }

        while (hashes.size() > 1) {
            List<String> nextLevel = new ArrayList<>();
            for (int i = 0; i < hashes.size(); i += 2) {
                String left = hashes.get(i);
                String right = (i + 1 < hashes.size()) ? hashes.get(i + 1) : left;
                nextLevel.add(sha256(left + right));
            }
            hashes = nextLevel;
        }
        return hashes.getFirst();
    }

    public boolean verifyEntry(LedgerEntry entry, String expectedRoot, List<String> proofPath, List<Boolean> isLeft) {
        String hash = sha256(canonicalize(entry));
        for (int i = 0; i < proofPath.size(); i++) {
            String sibling = proofPath.get(i);
            hash = isLeft.get(i) ? sha256(hash + sibling) : sha256(sibling + hash);
        }
        return hash.equals(expectedRoot);
    }

    private String archiveToGlacier(List<LedgerEntry> entries, String merkleRoot, LocalDateTime cutoff) {
        String key = String.format("ledger-archive/%s/merkle-%s.json",
                cutoff.toLocalDate(), merkleRoot.substring(0, 16));

        StringBuilder sb = new StringBuilder("{\"merkleRoot\":\"").append(merkleRoot).append("\",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            LedgerEntry e = entries.get(i);
            sb.append(String.format("{\"id\":%d,\"txId\":%d,\"accountId\":%d,\"type\":\"%s\",\"amount\":\"%s\",\"date\":\"%s\"}",
                    e.getId(), e.getTransactionId(), e.getAccountId(),
                    e.getEntryType(), e.getAmount(), e.getEntryDate()));
        }
        sb.append("]}");

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(glacierBucket)
                        .key(key)
                        .storageClass("GLACIER")
                        .build(),
                RequestBody.fromString(sb.toString())
        );

        return key;
    }

    private void saveMerkleRecord(String root, String archiveKey, int count, Long firstId, Long lastId) {
        merkleArchiveRepository.save(MerkleArchiveRecord.builder()
                .merkleRoot(root)
                .s3ArchiveKey(archiveKey)
                .entryCount(count)
                .firstEntryId(firstId)
                .lastEntryId(lastId)
                .archivedAt(LocalDateTime.now())
                .build());
    }

    private String canonicalize(LedgerEntry e) {
        return e.getId() + "|" + e.getTransactionId() + "|" + e.getAccountId() + "|"
                + e.getEntryType() + "|" + e.getAmount().toPlainString() + "|" + e.getEntryDate();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}