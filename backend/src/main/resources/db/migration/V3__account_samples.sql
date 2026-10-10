CREATE TABLE gc_account_sample (
    symbol VARCHAR(40) NOT NULL,
    sampled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    balance TEXT NOT NULL,
    equity TEXT,
    unrealized TEXT,
    state VARCHAR(60) NOT NULL,
    PRIMARY KEY(symbol,sampled_at)
);
