package com.titan.titancorebanking.utils;

import com.titan.titancorebanking.constant.TransactionConstants;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * PaginationUtils — Utility class for safe, bounded pagination in banking services and controllers.
 * Protects against unbounded limit attacks and out-of-memory errors caused by massive client-requested page sizes.
 */
public final class PaginationUtils {

    private PaginationUtils() {
        // Prevent instantiation of utility class
    }

    /**
     * Clamps user-supplied pagination parameters to safe operational bounds.
     * Enforces MIN_PAGE_SIZE and MAX_PAGE_SIZE, and defaults unpaged or null requests.
     *
     * @param pageable client-provided Pageable
     * @return safe, clamped Pageable
     */
    public static Pageable clampPageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(
                    TransactionConstants.DEFAULT_PAGE_NUMBER,
                    TransactionConstants.DEFAULT_PAGE_SIZE,
                    defaultSort()
            );
        }
        int page = Math.max(pageable.getPageNumber(), 0);
        int size = Math.min(Math.max(pageable.getPageSize(), TransactionConstants.MIN_PAGE_SIZE), TransactionConstants.MAX_PAGE_SIZE);
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : defaultSort();
        return PageRequest.of(page, size, sort);
    }

    /**
     * Builds a safe Pageable from nullable raw page and size parameters.
     *
     * @param page raw page index (0-indexed)
     * @param size raw page size
     * @return safe, clamped Pageable
     */
    public static Pageable of(Integer page, Integer size) {
        int pageNumber = page != null ? Math.max(page, 0) : TransactionConstants.DEFAULT_PAGE_NUMBER;
        int pageSize = size != null
                ? Math.min(Math.max(size, TransactionConstants.MIN_PAGE_SIZE), TransactionConstants.MAX_PAGE_SIZE)
                : TransactionConstants.DEFAULT_PAGE_SIZE;
        return PageRequest.of(pageNumber, pageSize, defaultSort());
    }

    /**
     * Standard deterministic descending sort order for audit and transaction history.
     */
    public static Sort defaultSort() {
        return Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"));
    }
}
