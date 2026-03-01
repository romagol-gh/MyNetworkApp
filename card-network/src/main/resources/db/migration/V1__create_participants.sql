CREATE TABLE participants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    code       VARCHAR(11)  NOT NULL UNIQUE,  -- institution ID used in DE32/DE33
    type       VARCHAR(10)  NOT NULL CHECK (type IN ('ACQUIRER', 'ISSUER')),
    host       VARCHAR(255),                  -- TCP host for outbound issuer connections
    port       INT,                           -- TCP port for outbound issuer connections
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_participants_code   ON participants(code);
CREATE INDEX idx_participants_type   ON participants(type);
CREATE INDEX idx_participants_status ON participants(status);
