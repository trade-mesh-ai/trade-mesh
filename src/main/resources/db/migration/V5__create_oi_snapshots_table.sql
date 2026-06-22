-- Create oi_snapshots table for storing OI analysis snapshots
CREATE TABLE IF NOT EXISTS oi_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    max_call_strike DECIMAL(10, 2),
    max_put_strike DECIMAL(10, 2),
    top_call_walls TEXT,
    top_put_walls TEXT,
    market_bias VARCHAR(20),
    data JSONB NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for common queries
CREATE INDEX IF NOT EXISTS idx_oi_snapshots_symbol ON oi_snapshots(symbol);
CREATE INDEX IF NOT EXISTS idx_oi_snapshots_symbol_recorded_at ON oi_snapshots(symbol, recorded_at);
CREATE INDEX IF NOT EXISTS idx_oi_snapshots_recorded_at ON oi_snapshots(recorded_at);
CREATE INDEX IF NOT EXISTS idx_oi_snapshots_market_bias ON oi_snapshots(market_bias);
