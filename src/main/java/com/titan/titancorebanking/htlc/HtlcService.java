package com.titan.titancorebanking.htlc;

import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class HtlcService {

    private final HtlcRepository htlcRepository;
    private final AccountRepository accountRepository;
    private static final int BATCH_SIZE = 500;

    @Transactional
    public HtlcContract lock(Long senderAccountId, Long receiverAccountId,
                             BigDecimal amount, String hashLock, String memo) {
        Account sender = accountRepository.findByIdWithLock(senderAccountId)
                .orElseThrow(() -> new RuntimeException("Sender account not found"));

        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for HTLC lock");
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        accountRepository.save(sender);

        HtlcContract contract = HtlcContract.builder()
                .contractId(UUID.randomUUID().toString())
                .senderAccountId(senderAccountId)
                .receiverAccountId(receiverAccountId)
                .amount(amount)
                .currency(sender.getCurrency().name())
                .hashLock(hashLock)
                .status(HtlcContract.HtlcStatus.LOCKED)
                .expiresAt(LocalDateTime.now().plusMinutes(60))
                .memo(memo)
                .build();

        log.info("HTLC locked: contractId={}, amount={}, hashLock={}", contract.getContractId(), amount, hashLock);
        return htlcRepository.save(contract);
    }

    @Transactional
    public HtlcContract claim(String contractId, String preimage) {
        HtlcContract contract = htlcRepository.findById(contractId)
                .orElseThrow(() -> new RuntimeException("HTLC contract not found: " + contractId));

        if (contract.getStatus() != HtlcContract.HtlcStatus.LOCKED) {
            throw new IllegalStateException("Contract is not in LOCKED state: " + contract.getStatus());
        }

        if (LocalDateTime.now().isAfter(contract.getExpiresAt())) {
            throw new IllegalStateException("HTLC contract has expired");
        }

        String computedHash = sha256(preimage);
        if (!computedHash.equals(contract.getHashLock())) {
            throw new SecurityException("Invalid preimage - hash does not match hashLock");
        }

        Account receiver = accountRepository.findByIdWithLock(contract.getReceiverAccountId())
                .orElseThrow(() -> new RuntimeException("Receiver account not found"));

        receiver.setBalance(receiver.getBalance().add(contract.getAmount()));
        accountRepository.save(receiver);

        contract.setPreimage(preimage);
        contract.setStatus(HtlcContract.HtlcStatus.CLAIMED);
        contract.setSettledAt(LocalDateTime.now());

        log.info("HTLC claimed: contractId={}, receiver={}", contractId, contract.getReceiverAccountId());
        return htlcRepository.save(contract);
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireContracts() {
        List<HtlcContract> expired;
        do {
            // Fetch in chunks to prevent OOM
            expired = htlcRepository.findByStatusAndExpiresAtBefore(
                    HtlcContract.HtlcStatus.LOCKED,
                    LocalDateTime.now(),
                    PageRequest.of(0, BATCH_SIZE)
            );

            for (HtlcContract contract : expired) {
                try {
                    processRefund(contract);
                } catch (Exception e) {
                    log.error("Failed to process HTLC refund for contract {}: {}", contract.getContractId(), e.getMessage());
                }
            }
        } while (!expired.isEmpty());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processRefund(HtlcContract contract) {
        accountRepository.findByIdWithLock(contract.getSenderAccountId()).ifPresent(sender -> {
            sender.setBalance(sender.getBalance().add(contract.getAmount()));
            accountRepository.save(sender);
        });

        contract.setStatus(HtlcContract.HtlcStatus.REFUNDED);
        contract.setSettledAt(LocalDateTime.now());
        htlcRepository.save(contract);

        log.info("HTLC expired & refunded: contractId={}, amount={}", contract.getContractId(), contract.getAmount());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}