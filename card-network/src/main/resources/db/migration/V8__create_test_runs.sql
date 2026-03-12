CREATE TABLE test_runs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario       VARCHAR(50)  NOT NULL,
    config_json    TEXT         NOT NULL,
    total_txns     INT          NOT NULL DEFAULT 0,
    passed_txns    INT          NOT NULL DEFAULT 0,
    failed_txns    INT          NOT NULL DEFAULT 0,
    fraud_flagged  INT          NOT NULL DEFAULT 0,
    fraud_declined INT          NOT NULL DEFAULT 0,
    detail_json    TEXT,
    started_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP,
    duration_ms    BIGINT
);

CREATE INDEX idx_test_runs_started ON test_runs(started_at DESC);
