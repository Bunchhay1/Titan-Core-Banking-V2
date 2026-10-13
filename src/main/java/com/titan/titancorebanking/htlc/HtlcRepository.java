package com.titan.titancorebanking.htlc;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface HtlcRepository extends JpaRepository<HtlcContract, String> {

    // STAFF ENGINEER FIX: Added Pageable to prevent unbounded result sets
    List<HtlcContract> findByStatusAndExpiresAtBefore(HtlcContract.HtlcStatus status, LocalDateTime now, Pageable pageable);
}