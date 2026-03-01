CREATE TABLE transactions (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    stan              VARCHAR(6)  NOT NULL,          -- DE11 System Trace Audit Number
    retrieval_ref     VARCHAR(12),                   -- DE37
    auth_id           VARCHAR(6),                    -- DE38 Authorization ID Response
    message_type      VARCHAR(4)  NOT NULL,          -- 0100, 0110, 0200, 0400 …
    processing_code   VARCHAR(6),                    -- DE3
    pan_masked        VARCHAR(19),                   -- first6****last4, never full PAN
    amount            BIGINT,                        -- DE4, minor units (cents)
    currency          VARCHAR(3),                    -- DE49 ISO 4217
    response_code     VARCHAR(2),                    -- DE39
    acquirer_id       UUID REFERENCES participants(id),
    issuer_id         UUID REFERENCES participants(id),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','APPROVED','DECLINED','REVERSED','FLAGGED')),
    original_txn_id   UUID REFERENCES transactions(id), -- reversals point here
    fraud_flagged     BOOLEAN     NOT NULL DEFAULT FALSE,
    transmitted_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    responded_at      TIMESTAMP,
    clearing_batch_id UUID                           -- set after clearing
);

CREATE INDEX idx_txn_stan          ON transactions(stan);
CREATE INDEX idx_txn_retrieval_ref ON transactions(retrieval_ref);
CREATE INDEX idx_txn_status        ON transactions(status);
CREATE INDEX idx_txn_acquirer      ON transactions(acquirer_id);
CREATE INDEX idx_txn_issuer        ON transactions(issuer_id);
CREATE INDEX idx_txn_transmitted   ON transactions(transmitted_at);
CREATE INDEX idx_txn_pan_masked    ON transactions(pan_masked);
