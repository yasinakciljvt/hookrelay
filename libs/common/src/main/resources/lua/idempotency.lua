-- Idempotency anahtari rezervasyonu.
--
-- Iki durumu ayirt eder:
--   ilk kez goruldu  → rezerve et, {1, ''} don
--   daha once vardi  → saklanan cevabi {0, cevap} olarak don
--
-- SETNX + GET'i ayri komut yapmak yaris aciyordu: iki istek ayni anda
-- SETNX deneyip ikisi de "yeni" sanabiliyordu. Tek betik bunu kapatir.
--
-- KEYS[1] = idempotency anahtari
-- ARGV[1] = saklanacak deger (ilk kez ise)
-- ARGV[2] = TTL (ms)
-- Doner: {yeni_mi(0|1), mevcut_deger}

local existing = redis.call('GET', KEYS[1])
if existing then
  return {0, existing}
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', tonumber(ARGV[2]))
return {1, ''}
