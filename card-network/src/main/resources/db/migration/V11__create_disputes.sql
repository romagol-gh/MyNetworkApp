-- Dispute / chargeback lifecycle table
CREATE TABLE disputes (
    id                UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id    UUID    NOT NULL REFERENCES transactions(id),
    initiator_id      UUID    NOT NULL REFERENCES participants(id),
    reason_code       VARCHAR(10)  NOT NULL,
    reason_network    VARCHAR(15)  NOT NULL DEFAULT 'VISA',
    chargeback_amount BIGINT       NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'INITIATED',
    notes             TEXT,
    initiated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    responded_at      TIMESTAMP,
    resolved_at       TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disputes_transaction_id ON disputes(transaction_id);
CREATE INDEX idx_disputes_status         ON disputes(status);
CREATE INDEX idx_disputes_initiated_at   ON disputes(initiated_at DESC);
