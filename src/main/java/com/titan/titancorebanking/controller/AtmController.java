package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.AtmGenerateRequest;
import com.titan.titancorebanking.dto.request.AtmRedeemRequest;
import com.titan.titancorebanking.dto.response.AtmCodeResponse;
import com.titan.titancorebanking.service.AtmCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * ATM Cardless Withdrawal Controller
 *
 * <h2>Endpoints</h2>
 * <pre>
 * POST   /api/v1/atm/generate              — Customer generates a 12-digit ATM code (JWT required)
 * POST   /api/v1/atm/redeem                — ATM terminal redeems the code and triggers cash dispense
 * DELETE /api/v1/atm/cancel/{code}         — Customer cancels a pending code (JWT required)
 * GET    /api/v1/atm/status/{code}         — Customer checks code status (JWT required)
 * </pre>
 *
 * <h2>ATM Redeem Security Note</h2>
 * In production, the /redeem endpoint should be reached only by trusted ATM
 * terminals via mTLS or a dedicated API key. For now it requires a standard JWT
 * to avoid opening an unauthenticated endpoint until terminal auth is wired up.
 */
@RestController
@RequestMapping("/api/v1/atm")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "ATM Cardless Withdrawal", description = "Generate and redeem one-time 12-digit ATM codes for cardless cash withdrawal")
public class AtmController {

    private final AtmCodeService atmCodeService;

    // =========================================================================
    // 1. GENERATE — mobile app requests a withdrawal code
    // =========================================================================

    @PostMapping("/generate")
    @Operation(
        summary     = "Generate ATM withdrawal code",
        description = "Generates a cryptographically secure 12-digit one-time code. "
                    + "Valid for 10 minutes. Any previously pending code for the same account is cancelled."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code generated successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error (invalid account, amount, or PIN)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized – JWT token missing or invalid"),
        @ApiResponse(responseCode = "422", description = "Insufficient balance")
    })
    public ResponseEntity<AtmCodeResponse> generate(
            @Valid @RequestBody AtmGenerateRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("🏧 ATM code generation request for account={} by user={}",
                request.accountNumber(), userDetails.getUsername());

        AtmCodeResponse response = atmCodeService.generateCode(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // 2. REDEEM — ATM terminal processes the code
    // =========================================================================

    @PostMapping("/redeem")
    @Operation(
        summary     = "Redeem ATM code (ATM terminal endpoint)",
        description = "Validates the 12-digit code, deducts the balance, and marks the code as USED. "
                    + "This endpoint is intended to be called by ATM terminals. "
                    + "Currently requires a valid JWT; replace with mTLS/API-key auth when terminal infra is available."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code redeemed – cash dispensed"),
        @ApiResponse(responseCode = "400", description = "Code is invalid, expired, used, or cancelled"),
        @ApiResponse(responseCode = "422", description = "Insufficient funds at time of redemption")
    })
    public ResponseEntity<AtmCodeResponse> redeem(
            @Valid @RequestBody AtmRedeemRequest request
    ) {
        log.info("💵 ATM redeem request | code=[MASKED] | terminal={}", request.terminalId());
        AtmCodeResponse response = atmCodeService.redeemCode(request);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // 3. CANCEL — customer cancels their pending code
    // =========================================================================

    @DeleteMapping("/cancel/{code}")
    @Operation(
        summary     = "Cancel a pending ATM code",
        description = "Allows the customer to cancel a PENDING code before it is used or expires."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code cancelled successfully"),
        @ApiResponse(responseCode = "400", description = "Code is not in PENDING state"),
        @ApiResponse(responseCode = "403", description = "Customer does not own this code"),
        @ApiResponse(responseCode = "404", description = "Code not found")
    })
    public ResponseEntity<AtmCodeResponse> cancel(
            @Parameter(description = "12-digit ATM code to cancel")
            @PathVariable String code,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("🚫 ATM code cancel request by user={}", userDetails.getUsername());
        AtmCodeResponse response = atmCodeService.cancelCode(code, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // 4. STATUS — customer checks their code status
    // =========================================================================

    @GetMapping("/status/{code}")
    @Operation(
        summary     = "Get ATM code status",
        description = "Returns the current status and expiry time of an ATM code owned by the authenticated customer."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status returned"),
        @ApiResponse(responseCode = "403", description = "Customer does not own this code"),
        @ApiResponse(responseCode = "404", description = "Code not found")
    })
    public ResponseEntity<AtmCodeResponse> status(
            @Parameter(description = "12-digit ATM code to check")
            @PathVariable String code,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        AtmCodeResponse response = atmCodeService.getCodeStatus(code, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }
}
