CREATE TABLE gc_external_command (
    id VARCHAR(36) PRIMARY KEY,
    owner_name VARCHAR(120) NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    actor_id VARCHAR(32) NOT NULL,
    process_id VARCHAR(36) NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    succeeded BOOLEAN,
    result_body TEXT,
    UNIQUE(owner_name, command_key)
);
CREATE INDEX gc_external_command_owner ON gc_external_command(owner_name, created_at);
