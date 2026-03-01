CREATE TABLE bin_ranges (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    low        VARCHAR(19) NOT NULL,   -- e.g. '400000'
    high       VARCHAR(19) NOT NULL,   -- e.g. '499999'
    issuer_id  UUID        NOT NULL REFERENCES participants(id),
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_bin_low_le_high CHECK (low <= high)
);

CREATE INDEX idx_bin_ranges_issuer ON bin_ranges(issuer_id);
CREATE INDEX idx_bin_ranges_low    ON bin_ranges(low);
