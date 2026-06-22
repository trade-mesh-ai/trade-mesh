-- Create vix_alerts table for storing VIX alert events
CREATE TABLE IF NOT EXISTS vix_alerts (
    id BIGSERIAL PRIMARY KEY,
    vix_value DECIMAL(10, 2) NOT NULL,
    vix_level VARCHAR(20) NOT NULL,
    alert_message TEXT NOT NULL,
    telegram_sent BOOLEAN DEFAULT FALSE,
    triggered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for common queries
CREATE INDEX IF NOT EXISTS idx_vix_alerts_triggered_at ON vix_alerts(triggered_at);
CREATE INDEX IF NOT EXISTS idx_vix_alerts_vix_level ON vix_alerts(vix_level);
CREATE INDEX IF NOT EXISTS idx_vix_alerts_telegram_sent ON vix_alerts(telegram_sent);
