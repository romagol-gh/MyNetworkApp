CREATE TABLE settlement_records (
    id              UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_date DATE    NOT NULL,
    participant_id  UUID    NOT NULL REFERENCES participants(id),
    net_position    BIGINT  NOT NULL,  -- positive = owed TO participant, negative = participant owes
    debit_total     BIGINT  NOT NULL DEFAULT 0,
    credit_total    BIGINT  NOT NULL DEFAULT 0,
    batch_id        UUID    REFERENCES clearing_batches(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (settlement_date, participant_id)
);

CREATE INDEX idx_settlement_date        ON settlement_records(settlement_date);
CREATE INDEX idx_settlement_participant ON settlement_records(participant_id);
