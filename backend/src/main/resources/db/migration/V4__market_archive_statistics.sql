CREATE TABLE gc_market_frame_stats (
    symbol VARCHAR(40) NOT NULL,
    channel VARCHAR(60) NOT NULL,
    frames BIGINT NOT NULL,
    first_sequence BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    first_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (symbol, channel)
);
INSERT INTO gc_market_frame_stats
SELECT symbol,channel,COUNT(*),MIN(sequence),MAX(sequence),MIN(received_at),MAX(received_at)
FROM gc_market_frame GROUP BY symbol,channel;
CREATE INDEX gc_market_frame_symbol_channel_sequence ON gc_market_frame(symbol,channel,sequence);
CREATE INDEX gc_market_frame_symbol_channel_received ON gc_market_frame(symbol,channel,received_at);
