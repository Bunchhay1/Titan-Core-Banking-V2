-- ============================================================
-- V11: Remove new account / first 1000 users bonus promotion
-- ============================================================
-- Delete FIRST_1000 campaign so new accounts/first deposits
-- do not receive the automatic $5 welcome bonus.
-- ============================================================

DELETE FROM campaigns WHERE campaign_code = 'FIRST_1000';
