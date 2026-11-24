-- Stage 11: password reset by email (ASVS 2.5, ADR 0034). Expand only: a new token purpose.
ALTER TABLE user_token DROP CONSTRAINT user_token_purpose_check;
ALTER TABLE user_token ADD CONSTRAINT user_token_purpose_check CHECK (purpose IN ('VERIFY_EMAIL', 'PASSWORD_RESET'));
