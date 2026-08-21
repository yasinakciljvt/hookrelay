-- Dagitik devre kesici (CLOSED / OPEN / HALF_OPEN).
--
-- Neden Resilience4j degil: Resilience4j'nin devre kesicisi JVM ICINDE yasar.
-- 4 dispatcher ornegi calistirinca 4 ayri devre olur; musteri coktugunde
-- dordunun de ayri ayri esigi doldurmasi gerekir ve durum ornek olulunce kaybolur.
-- Endpoint bazli devre PAYLASILAN durum ister, o yuzden Redis'te.
--
-- KEYS[1] = devre anahtari
-- ARGV[1] = islem: 'allow' | 'success' | 'failure'
-- ARGV[2] = simdi (ms)
-- ARGV[3] = hata esigi (pencerede kac hata devreyi acar)
-- ARGV[4] = acik kalma suresi (ms)
-- ARGV[5] = kapanmak icin gereken ardisik basarili yoklama sayisi
-- ARGV[6] = kayan pencere (ms) — bu sure hata gelmezse sayac sifirlanir
-- Doner: {izin(0|1), durum, pencere_hata_sayisi}

local key       = KEYS[1]
local op        = ARGV[1]
local now       = tonumber(ARGV[2])
local threshold = tonumber(ARGV[3])
local openMs    = tonumber(ARGV[4])
local probes    = tonumber(ARGV[5])
local windowMs  = tonumber(ARGV[6])

local h = redis.call('HMGET', key, 'state', 'failures', 'openedAt', 'inFlight', 'probeOk', 'winStart')
local state    = h[1] or 'CLOSED'
local failures = tonumber(h[2]) or 0
local openedAt = tonumber(h[3]) or 0
local inFlight = tonumber(h[4]) or 0
local probeOk  = tonumber(h[5]) or 0
local winStart = tonumber(h[6]) or now

-- OPEN suresi dolduysa kendiliginden HALF_OPEN'a gec
if state == 'OPEN' and (now - openedAt) >= openMs then
  state = 'HALF_OPEN'
  inFlight = 0
  probeOk = 0
end

-- Kayan pencere kaydi: son hatadan bu yana windowMs gectiyse sayac sifirlanir
if state == 'CLOSED' and (now - winStart) > windowMs then
  failures = 0
  winStart = now
end

local allowed = 0

if op == 'allow' then
  if state == 'CLOSED' then
    allowed = 1
  elseif state == 'HALF_OPEN' then
    -- Tek yoklama gecirilir; ikinci istek bekler. Coken servisi
    -- yeniden ayaga kalkarken bogmamak icin.
    if inFlight < 1 then
      allowed = 1
      inFlight = inFlight + 1
    end
  else
    allowed = 0
  end

elseif op == 'success' then
  if state == 'HALF_OPEN' then
    inFlight = math.max(0, inFlight - 1)
    probeOk = probeOk + 1
    if probeOk >= probes then
      state = 'CLOSED'
      failures = 0
      probeOk = 0
      winStart = now
    end
  else
    failures = 0
    winStart = now
  end
  allowed = 1

elseif op == 'failure' then
  if state == 'HALF_OPEN' then
    -- Yoklama da patladi: tam sureyle yeniden ac
    state = 'OPEN'
    openedAt = now
    inFlight = 0
    probeOk = 0
  else
    failures = failures + 1
    if failures >= threshold then
      state = 'OPEN'
      openedAt = now
    end
  end
  allowed = 0
end

redis.call('HSET', key,
  'state', state, 'failures', failures, 'openedAt', openedAt,
  'inFlight', inFlight, 'probeOk', probeOk, 'winStart', winStart)
redis.call('PEXPIRE', key, math.max(openMs, windowMs) * 3 + 60000)

return {allowed, state, failures}
