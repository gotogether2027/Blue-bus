-- Phase 8.3: opaque refresh tokens / session families (hash-only storage).

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    user_id UUID NOT NULL,
    token_hash BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_id UUID,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expires_after_issued CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_tokens_hash_length CHECK (octet_length(token_hash) = 32)
);

-- At most one non-revoked refresh token per family (rotation concurrency guard).
CREATE UNIQUE INDEX uq_refresh_tokens_active_family
    ON refresh_tokens (family_id)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_refresh_tokens_user_status
    ON refresh_tokens (user_id, revoked_at, expires_at);

CREATE INDEX ix_refresh_tokens_family
    ON refresh_tokens (family_id);

CREATE INDEX ix_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);

CREATE INDEX ix_refresh_tokens_revoked_at
    ON refresh_tokens (revoked_at)
    WHERE revoked_at IS NOT NULL;
