-- Add agent fields to transactions
ALTER TABLE transactions
    ADD COLUMN agent_id    VARCHAR(16),
    ADD COLUMN intent_hash VARCHAR(64),
    ADD COLUMN agent_chain TEXT;

CREATE INDEX idx_transactions_agent_id ON transactions(agent_id);

-- Agent registrations table
CREATE TABLE agent_registrations (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id       VARCHAR(16)  NOT NULL UNIQUE,
    participant_id UUID         NOT NULL REFERENCES participants(id),
    public_key     TEXT,
    mcc_scope      VARCHAR(255),
    per_txn_limit  BIGINT,
    daily_limit    BIGINT,
    time_window    VARCHAR(50),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    registered_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMP,
    expires_at     TIMESTAMP
);

CREATE INDEX idx_agent_reg_participant ON agent_registrations(participant_id);
CREATE INDEX idx_agent_reg_status      ON agent_registrations(status);

-- Expand fraud_rule_configs rule_type check to include agent rule types
ALTER TABLE fraud_rule_configs
    DROP CONSTRAINT IF EXISTS fraud_rule_configs_rule_type_check;

ALTER TABLE fraud_rule_configs
    ADD CONSTRAINT fraud_rule_configs_rule_type_check
    CHECK (rule_type IN ('VELOCITY','AMOUNT_LIMIT','BLACKLIST','DECLINE_VELOCITY','MCC',
                         'AGENT_VELOCITY','AGENT_SCOPE','AGENT_CHAIN'));

-- Seed agent fraud rule configs
INSERT INTO fraud_rule_configs (name, rule_type, parameters, score_weight, action) VALUES
    ('Agent Velocity Check',  'AGENT_VELOCITY', '{"maxTransactions":10,"windowMinutes":5}',  35, 'FLAG'),
    ('Agent Scope Violation', 'AGENT_SCOPE',    '{"enforceStrict":true}',                    45, 'FLAG'),
    ('Agent Chain Anomaly',   'AGENT_CHAIN',    '{"maxChainDepth":3}',                        40, 'FLAG');
