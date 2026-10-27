CREATE TABLE gc_access_token (
    id VARCHAR(32) PRIMARY KEY,
    owner_name VARCHAR(120) NOT NULL,
    token_name VARCHAR(80) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    scopes VARCHAR(200) NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    use_count BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX gc_access_token_owner ON gc_access_token(owner_name, created_at);
