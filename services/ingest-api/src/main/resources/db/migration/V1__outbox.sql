-- Transactional Outbox tablosu.
-- Bu sema, outbox kullanan HER servisin kendi veritabaninda ayri ayri bulunur.
-- Paylasilmaz: her servis kendi verisinin sahibi.

CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    msg_key        VARCHAR(128),
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);

-- Poller'in tek sorgusu bu indeksi kullanir:
--   WHERE published_at IS NULL ORDER BY created_at
-- Kismi indeks (WHERE published_at IS NULL) secildi cunku tablo cogunlukla
-- yayinlanmis kayitlardan olusur; onlari indekslemek bosuna yer ve yazma maliyeti.
CREATE INDEX idx_outbox_pending
    ON outbox_event (created_at, id)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_published_at
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;
