CREATE TABLE fraud_rule_configs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    rule_type    VARCHAR(30) NOT NULL
                 CHECK (rule_type IN ('VELOCITY','AMOUNT_LIMIT','BLACKLIST','DECLINE_VELOCITY','MCC')),
    parameters   JSONB       NOT NULL DEFAULT '{}',
    score_weight INT         NOT NULL DEFAULT 50 CHECK (score_weight BETWEEN 1 AND 100),
    action       VARCHAR(10) NOT NULL CHECK (action IN ('FLAG','DECLINE')),
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE fraud_alerts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID        NOT NULL REFERENCES transactions(id),
    fraud_score     INT         NOT NULL CHECK (fraud_score BETWEEN 0 AND 100),
    action_taken    VARCHAR(10) NOT NULL CHECK (action_taken IN ('FLAG','DECLINE')),
    triggered_rules TEXT,       -- comma-separated rule names
    reviewed        BOOLEAN     NOT NULL DEFAULT FALSE,
    reviewed_by     VARCHAR(100),
    reviewed_at     TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE blacklisted_cards (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    pan_hash   VARCHAR(64) NOT NULL UNIQUE, -- SHA-256 of full PAN
    reason     VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP               -- NULL = permanent block
);

CREATE INDEX idx_fraud_alerts_txn        ON fraud_alerts(transaction_id);
CREATE INDEX idx_fraud_alerts_reviewed   ON fraud_alerts(reviewed);
CREATE INDEX idx_fraud_alerts_created    ON fraud_alerts(created_at);
CREATE INDEX idx_blacklisted_pan_hash    ON blacklisted_cards(pan_hash);

-- Seed default fraud rules
INSERT INTO fraud_rule_configs (name, rule_type, parameters, score_weight, action) VALUES
    ('High velocity (5 txns/hour)',  'VELOCITY',         '{"max_count":5,"window_minutes":60}',  40, 'FLAG'),
    ('Daily velocity (20 txns/day)', 'VELOCITY',         '{"max_count":20,"window_minutes":1440}', 30, 'FLAG'),
    ('Large amount (>$5000)',        'AMOUNT_LIMIT',     '{"threshold_minor_units":500000,"currency":"USD"}', 50, 'FLAG'),
    ('Blacklisted card',             'BLACKLIST',        '{}',                                   100, 'DECLINE'),
    ('Decline velocity (3/hour)',    'DECLINE_VELOCITY', '{"max_declines":3,"window_minutes":60}', 60, 'DECLINE'),
    ('High-risk MCC',                'MCC',              '{"mcc_codes":["7995","5933","6051"]}',  50, 'FLAG');
