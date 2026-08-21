-- HER SERVISE AYRI VERITABANI.
--
-- Mikroservisin en sik atlanan ve en onemli kurali: iki servis ayni
-- tabloya yazamaz. Yazarsa, semayi degistirmek icin iki takimin
-- anlasmasi gerekir ve elinizde "dagitik monolit" olur — mikroservisin
-- butun maliyeti, hicbir faydasi.
--
-- Burada uc AYRI VERITABANI var ama TEK Postgres sunucusunda. Bu bir
-- taviz ve bilincli: yerel gelistirmede uc konteyner calistirmak
-- 600 MB fazladan RAM demek. Onemli olan izolasyonun SEMA duzeyinde
-- gercek olmasi — hicbir servis digerinin tablosunu goremiyor.
-- Uretimde ayri sunuculara tasimak, sadece bir baglanti dizgisi degisikligi.

CREATE DATABASE hookrelay_control;
CREATE DATABASE hookrelay_ingest;
CREATE DATABASE hookrelay_delivery;

GRANT ALL PRIVILEGES ON DATABASE hookrelay_control  TO hookrelay;
GRANT ALL PRIVILEGES ON DATABASE hookrelay_ingest   TO hookrelay;
GRANT ALL PRIVILEGES ON DATABASE hookrelay_delivery TO hookrelay;
