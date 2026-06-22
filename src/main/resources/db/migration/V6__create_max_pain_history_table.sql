-- Create max_pain_history table for storing max pain calculation snapshots
CREATE TABLE IF NOT EXISTS max_pain_history (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    expiry_date DATE NOT NULL,
    max_pain_strike DECIMAL(12, 2) NOT NULL,
    spot_price DECIMAL(12, 2) NOT NULL,
    distance_from_spot DECIMAL(12, 2),
    distance_percent DECIMAL(10, 4),
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for common queries
CREATE INDEX IF NOT EXISTS idx_max_pain_symbol ON max_pain_history(symbol);
CREATE INDEX IF NOT EXISTS idx_max_pain_expiry_date ON max_pain_history(expiry_date);
CREATE INDEX IF NOT EXISTS idx_max_pain_calculated_at ON max_pain_history(calculated_at);
CREATE INDEX IF NOT EXISTS idx_max_pain_symbol_calculated_at ON max_pain_history(symbol, calculated_at DESC);
