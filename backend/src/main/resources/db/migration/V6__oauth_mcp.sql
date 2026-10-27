CREATE TABLE gc_oauth_client (
    id VARCHAR(80) PRIMARY KEY,
    client_name VARCHAR(80) NOT NULL,
    redirect_uris VARCHAR(12000) NOT NULL,
    scopes VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE gc_oauth_grant (
    id VARCHAR(32) PRIMARY KEY,
    owner_name VARCHAR(120) NOT NULL,
    client_id VARCHAR(80) NOT NULL REFERENCES gc_oauth_client(id),
    redirect_uri VARCHAR(2048) NOT NULL,
    resource_uri VARCHAR(2048) NOT NULL,
    scopes VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    use_count BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX gc_oauth_grant_owner ON gc_oauth_grant(owner_name, created_at);
CREATE TABLE gc_oauth_code (
    code_hash VARCHAR(64) PRIMARY KEY,
    grant_id VARCHAR(32) NOT NULL REFERENCES gc_oauth_grant(id),
    challenge VARCHAR(43) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE gc_oauth_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    grant_id VARCHAR(32) NOT NULL REFERENCES gc_oauth_grant(id),
    token_type VARCHAR(10) NOT NULL,
    scopes VARCHAR(200) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX gc_oauth_token_grant ON gc_oauth_token(grant_id);
