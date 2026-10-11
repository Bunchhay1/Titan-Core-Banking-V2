package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.model.FixedDeposit;
import com.titan.titancorebanking.repository.FixedDepositRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Hardened Fixed Deposit Controller.
 * Removes generic Map generic payloads in favor of strictly typed Records.
 * Implements proper transaction boundaries for the fixed deposit creation.
 */
@RestController
@RequestMapping("/api/v1/fixed-deposits")
@RequiredArgsConstructor
@Tag(name = "Fixed Deposits", description = "Manage term deposits and maturity yields")
public class FixedDepositController {

    private final FixedDepositRepository repository;

    @PostMapping("/create")
    @Transactional
    @Operation(summary = "Open a new Fixed Deposit account")
    public ResponseEntity<FixedDeposit> create(@RequestBody FixedDepositRequest request) {

        BigDecimal principal = request.amount();
        int months = request.termMonths();
        BigDecimal rate = new BigDecimal("0.06"); // 6% APY

        // Maturity = Principal * (1 + (Rate * (Months / 12)))
        BigDecimal termFraction = new BigDecimal(months).divide(new BigDecimal(12), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal interestMultiplier = BigDecimal.ONE.add(rate.multiply(termFraction));
        BigDecimal maturityAmount = principal.multiply(interestMultiplier).setScale(2, java.math.RoundingMode.HALF_EVEN);

        FixedDeposit fd = FixedDeposit.builder()
                .accountId(request.accountId())
                .principalAmount(principal)
                .maturityAmount(maturityAmount)
                .termMonths(months)
                .interestRate(rate)
                .maturityDate(LocalDateTime.now().plusMonths(months))
                .status("ACTIVE")
                .build();

        return ResponseEntity.ok(repository.save(fd));
    }

    public record FixedDepositRequest(Long accountId, BigDecimal amount, Integer termMonths) {}
}