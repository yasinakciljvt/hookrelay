-- Teslimat durumu ve denetim izi. Bu veritabaninin sahibi: dispatcher.

CREATE TABLE delivery (
    id              UUID PRIMARY KEY,
    message_id      UUID         NOT NULL,
    application_id  UUID         NOT NULL,
    endpoint_id     UUID         NOT NULL,
    event_type      VARCHAR(120) NOT NULL,
    payload         TEXT,
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_status     INT,
    last_error      VARCHAR(1000),
    next_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_delivery_endpoint  ON delivery (endpoint_id, created_at DESC);
CREATE INDEX idx_delivery_app       ON delivery (application_id, created_at DESC);
CREATE INDEX idx_delivery_status    ON delivery (status, updated_at DESC);
CREATE INDEX idx_delivery_message   ON delivery (message_id);

-- Sadece EKLENIR. Guncellenmez, silinmez.
CREATE TABLE delivery_attempt (
    id               UUID PRIMARY KEY,
    delivery_id      UUID          NOT NULL,
    endpoint_id      UUID          NOT NULL,
    attempt          INT           NOT NULL,
    http_status      INT,
    latency_ms       BIGINT        NOT NULL,
    error            VARCHAR(1000),
    response_snippet VARCHAR(2000),
    occurred_at      TIMESTAMPTZ   NOT NULL
);

-- MUKERRER TESLIMAT KORUMASI.
--
-- Kafka "en az bir kez" teslim ettigi icin ayni (teslimat, deneme)
-- ikilisi iki kez islenebilir. Bu kisit, ikinci kaydin acilmasini
-- veritabani seviyesinde imkansiz kilar. Uygulama kodundaki kontrol
-- unutulabilir; kisit unutulmaz.
CREATE UNIQUE INDEX uk_attempt_delivery_no ON delivery_attempt (delivery_id, attempt);

CREATE INDEX idx_attempt_endpoint ON delivery_attempt (endpoint_id, occurred_at DESC);
