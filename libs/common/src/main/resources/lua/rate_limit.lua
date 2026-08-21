-- Token bucket hiz siniri.
--
-- Neden Lua: "oku - hesapla - yaz" ucusu tek Redis komutu olmak zorunda.
-- Java tarafinda yapilsaydi iki dispatcher ornegi ayni anda okur, ikisi de
-- "yer var" der ve limit asilirdi. Redis Lua'yi tek is parcaciginda calistirir,
-- yani bu betik atomiktir.
--
-- KEYS[1] = kova anahtari            ARGV[1] = kapasite (jeton)
-- ARGV[2] = saniyedeki dolum hizi    ARGV[3] = simdi (ms)
-- ARGV[4] = istenen jeton
-- Doner: {izin(0|1), beklenmesi_gereken_ms}

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

if rate <= 0 then return {1, 0} end   -- 0 = sinirsiz

local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- Gecen sureye gore kovayi doldur (tavan: kapasite)
local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + (elapsed * rate / 1000.0))

local allowed = 0
local wait = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  wait = math.ceil(((requested - tokens) / rate) * 1000)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
-- Kova bos kalsa bile dolmasi ne kadar surerse o kadar yasasin + pay
redis.call('PEXPIRE', key, math.ceil((capacity / rate) * 1000) + 10000)

return {allowed, wait}
