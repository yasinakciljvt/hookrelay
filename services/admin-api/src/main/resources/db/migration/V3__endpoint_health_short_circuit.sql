-- Devre kesici / hiz siniri yuzunden HIC ATILMAYAN istekler.
--
-- failed sutunundan ayri: bunlar musterinin hatasi degil, bizim kararimiz.
-- Ayni kovada tutmak "basari orani" metrigini anlamsiz yapiyordu — devresi
-- acik bir endpoint hicbir istek almadigi halde saniyede yuzlerce "hata"
-- biriktiriyordu.
ALTER TABLE endpoint_health
    ADD COLUMN short_circuited BIGINT NOT NULL DEFAULT 0;
