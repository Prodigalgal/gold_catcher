ALTER TABLE gc_oauth_grant ADD COLUMN source_pat_id VARCHAR(32) REFERENCES gc_access_token(id);
-- Existing session-only grants have no backing token and must reconnect using a PAT.
UPDATE gc_oauth_grant SET revoked_at=CURRENT_TIMESTAMP WHERE source_pat_id IS NULL AND revoked_at IS NULL;
CREATE INDEX gc_oauth_grant_source_pat ON gc_oauth_grant(source_pat_id);
