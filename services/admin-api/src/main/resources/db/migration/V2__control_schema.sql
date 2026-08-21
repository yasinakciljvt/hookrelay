-- Kontrol duzlemi semasi: kim var, nereye gonderiyoruz.

CREATE TABLE application (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL UNIQUE,
    api_key_hash    VARCHAR(128) NOT NULL,
    api_key_preview VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX idx_application_key_hash ON application (api_key_hash);

CREATE TABLE endpoint (
    id                    UUID PRIMARY KEY,
    application_id        UUID          NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    url                   VARCHAR(2000) NOT NULL,
    secret                VARCHAR(128)  NOT NULL,
    description           VARCHAR(255),
    event_types           VARCHAR(2000) NOT NULL DEFAULT '*',
    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    rate_limit_per_second INT           NOT NULL DEFAULT 0,
    max_attempts          INT           NOT NULL DEFAULT 6,
    timeout_ms            INT           NOT NULL DEFAULT 10000,
    version               BIGINT        NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_endpoint_application ON endpoint (application_id, created_at DESC);

-- PROJEKSIYON. Kaynak veri degil; delivery-results topic'inden turetilir.
-- Silinip topic bastan okunsa aynen geri gelir.
CREATE TABLE endpoint_health (
    endpoint_id          UUID PRIMARY KEY,
    application_id       UUID        NOT NULL,
    succeeded            BIGINT      NOT NULL DEFAULT 0,
    failed               BIGINT      NOT NULL DEFAULT 0,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    last_status          INT,
    last_error           VARCHAR(500),
    last_latency_ms      BIGINT,
    last_delivery_at     TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_endpoint_health_app ON endpoint_health (application_id);
