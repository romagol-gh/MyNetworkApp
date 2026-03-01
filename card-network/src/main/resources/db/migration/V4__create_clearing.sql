CREATE TABLE clearing_batches (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_date   DATE        NOT NULL UNIQUE,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','PROCESSING','COMPLETE','FAILED')),
    record_count INT,
    total_amount BIGINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE clearing_records (
    id             UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id       UUID   NOT NULL REFERENCES clearing_batches(id),
    transaction_id UUID   NOT NULL REFERENCES transactions(id),
    acquirer_id    UUID   NOT NULL REFERENCES participants(id),
    issuer_id      UUID   NOT NULL REFERENCES participants(id),
    amount         BIGINT NOT NULL,
    currency       VARCHAR(3) NOT NULL
);

CREATE INDEX idx_clearing_records_batch ON clearing_records(batch_id);
CREATE INDEX idx_clearing_records_txn   ON clearing_records(transaction_id);

-- Add FK from transactions back to clearing_batches
ALTER TABLE transactions
    ADD CONSTRAINT fk_txn_clearing_batch
    FOREIGN KEY (clearing_batch_id) REFERENCES clearing_batches(id);
