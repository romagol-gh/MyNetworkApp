-- 1. Fee schedule table
CREATE TABLE interchange_rates (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(100) NOT NULL,
    description       TEXT,
    percentage_bps    INT    NOT NULL DEFAULT 0,
    flat_amount_minor BIGINT NOT NULL DEFAULT 0,
    fee_category      VARCHAR(20) NOT NULL DEFAULT 'INTERCHANGE',
    mcc_pattern       VARCHAR(10) NOT NULL DEFAULT 'DEFAULT',
    priority          INT NOT NULL DEFAULT 0,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. Per-transaction fee capture on clearing_records
ALTER TABLE clearing_records
    ADD COLUMN interchange_fee BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN network_fee     BIGINT NOT NULL DEFAULT 0;

-- 3. Batch-level fee total
ALTER TABLE clearing_batches
    ADD COLUMN total_fees BIGINT NOT NULL DEFAULT 0;

-- 4. Fee breakdown on settlement_records
ALTER TABLE settlement_records
    ADD COLUMN interchange_fees_paid     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN interchange_fees_received BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN network_fees_paid         BIGINT NOT NULL DEFAULT 0;

-- 5. MCC on transactions
ALTER TABLE transactions
    ADD COLUMN mcc VARCHAR(4);

-- 6. Seed default rates
INSERT INTO interchange_rates (name, description, percentage_bps, flat_amount_minor, fee_category, mcc_pattern, priority) VALUES
  ('Interchange Standard',  'Default interchange rate',           150,  10, 'INTERCHANGE', 'DEFAULT', 0),
  ('Interchange High-Risk', 'Gambling / betting (MCC 7995)',      250,  15, 'INTERCHANGE', '7995',   10),
  ('Interchange High-Risk', 'Pawnshops (MCC 5933)',               250,  15, 'INTERCHANGE', '5933',   10),
  ('Interchange High-Risk', 'Crypto / non-bank (MCC 6051)',       250,  15, 'INTERCHANGE', '6051',   10),
  ('Interchange Retail',    'Grocery/retail (MCC 5411)',          120,   8, 'INTERCHANGE', '5411',    5),
  ('Network Fee Acquirer',  'Scheme fee — acquirer side',           5,   2, 'NETWORK',     'DEFAULT', 0),
  ('Network Fee Issuer',    'Scheme fee — issuer side',             5,   2, 'NETWORK',     'DEFAULT', 0);
