-- NULL: all tools within granted scopes. Empty text: explicitly no tools.
-- Existing tokens retain their prior scopes and do not receive write scopes automatically.
ALTER TABLE gc_access_token ADD COLUMN allowed_tools TEXT;
