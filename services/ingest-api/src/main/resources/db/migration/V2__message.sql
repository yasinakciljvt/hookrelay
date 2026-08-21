-- Kabul edilen mantiksal olaylar.

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    application_id  UUID         NOT NULL,
    event_type      VARCHAR(120) NOT NULL,
    payload         TEXT         NOT NULL,
    idempotency_key VARCHAR(200),
    delivery_count  INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- Idempotency'nin ASIL garantisi burada. Redis hizli katman; dogruluk bu satirda.
--
-- Postgres'te NULL'lar birbirine esit sayilmadigi icin bu kisit,
-- Idempotency-Key gondermeyen istekleri engellemez. Tam istedigimiz davranis:
-- anahtar veren korunur, vermeyen korunmaz.
CREATE UNIQUE INDEX uk_message_idempotency
    ON message (application_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_message_app_created ON message (application_id, created_at DESC);
