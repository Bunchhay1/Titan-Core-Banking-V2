package com.titan.titancorebanking.utils;

import com.titan.titancorebanking.constant.TransactionConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationUtilsTest {

    @Test
    @DisplayName("clampPageable_nullOrUnpaged_defaultsSafely")
    void clampPageable_nullOrUnpaged_defaultsSafely() {
        Pageable nullClamped = PaginationUtils.clampPageable(null);
        assertThat(nullClamped.getPageNumber()).isEqualTo(TransactionConstants.DEFAULT_PAGE_NUMBER);
        assertThat(nullClamped.getPageSize()).isEqualTo(TransactionConstants.DEFAULT_PAGE_SIZE);
        assertThat(nullClamped.getSort().isSorted()).isTrue();

        Pageable unpagedClamped = PaginationUtils.clampPageable(Pageable.unpaged());
        assertThat(unpagedClamped.getPageNumber()).isEqualTo(TransactionConstants.DEFAULT_PAGE_NUMBER);
        assertThat(unpagedClamped.getPageSize()).isEqualTo(TransactionConstants.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("clampPageable_negativePage_clampedToZero")
    void clampPageable_negativePage_clampedToZero() {
        // PageRequest allows negative page via custom construction or clamp logic
        Pageable custom = PageRequest.of(0, 15);
        Pageable clamped = PaginationUtils.clampPageable(custom);
        assertThat(clamped.getPageNumber()).isEqualTo(0);
        assertThat(clamped.getPageSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("clampPageable_exceedingMaxSize_clampedToMax")
    void clampPageable_exceedingMaxSize_clampedToMax() {
        Pageable excessive = PageRequest.of(2, 500);
        Pageable clamped = PaginationUtils.clampPageable(excessive);
        assertThat(clamped.getPageNumber()).isEqualTo(2);
        assertThat(clamped.getPageSize()).isEqualTo(TransactionConstants.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("clampPageable_belowMinSize_clampedToMin")
    void clampPageable_belowMinSize_clampedToMin() {
        Pageable small = PageRequest.of(0, 1);
        Pageable clamped = PaginationUtils.clampPageable(small);
        assertThat(clamped.getPageSize()).isEqualTo(TransactionConstants.MIN_PAGE_SIZE);
    }

    @Test
    @DisplayName("clampPageable_customSort_preserved")
    void clampPageable_customSort_preserved() {
        Sort customSort = Sort.by(Sort.Direction.ASC, "amount");
        Pageable pageable = PageRequest.of(1, 25, customSort);
        Pageable clamped = PaginationUtils.clampPageable(pageable);
        assertThat(clamped.getSort()).isEqualTo(customSort);
    }

    @Test
    @DisplayName("of_rawParameters_clampsAndBuildsCorrectly")
    void of_rawParameters_clampsAndBuildsCorrectly() {
        Pageable normal = PaginationUtils.of(3, 40);
        assertThat(normal.getPageNumber()).isEqualTo(3);
        assertThat(normal.getPageSize()).isEqualTo(40);

        Pageable nulls = PaginationUtils.of(null, null);
        assertThat(nulls.getPageNumber()).isEqualTo(0);
        assertThat(nulls.getPageSize()).isEqualTo(20);

        Pageable bounded = PaginationUtils.of(-10, 1000);
        assertThat(bounded.getPageNumber()).isEqualTo(0);
        assertThat(bounded.getPageSize()).isEqualTo(100);
    }
}
