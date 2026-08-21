# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Tek Dockerfile, alti servis.
#
# Neden tek dosya: butun servisler ayni Maven reaktorunden cikiyor ve
# ayni JRE'de calisiyor. Alti ayri Dockerfile tutmak, alti kez ayni
# seyi guncellemek demekti.
#
# Cok asamali (multi-stage) yapi: derleme icin Maven imaji (yaklasik
# 700 MB), calistirma icin sadece JRE (yaklasik 250 MB). Uretim imajinda
# ne Maven var ne kaynak kod ne de ~/.m2.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# ONCE sadece pom dosyalari, sonra bagimliliklar.
#
# Docker katmanlari sirayla onbelleklenir. Kaynak kod pom'dan once
# kopyalansaydi, tek satirlik bir kod degisikligi butun bagimliliklari
# yeniden indirtirdi. Bu sirayla: pom degismedikce indirme katmani
# onbellekten gelir ve derleme 3 dakikadan 20 saniyeye duser.
COPY pom.xml .
COPY libs/contracts/pom.xml         libs/contracts/pom.xml
COPY libs/common/pom.xml            libs/common/pom.xml
COPY libs/outbox/pom.xml            libs/outbox/pom.xml
COPY services/gateway/pom.xml       services/gateway/pom.xml
COPY services/admin-api/pom.xml     services/admin-api/pom.xml
COPY services/ingest-api/pom.xml    services/ingest-api/pom.xml
COPY services/dispatcher/pom.xml    services/dispatcher/pom.xml
COPY services/retry-scheduler/pom.xml services/retry-scheduler/pom.xml
COPY services/chaos-target/pom.xml  services/chaos-target/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline -DskipTests || true

COPY libs libs
COPY services services
# BuildKit onbellek baglantisi: ~/.m2 katmanlar arasinda korunur.
# Ilk derleme 3 dakika, sonrakiler 30 saniye.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
ARG SERVICE
WORKDIR /app

# root olarak calistirmiyoruz. Konteynerden kacis acigi bulunursa
# saldirgan root degil, hicbir seye yetkisi olmayan bir kullanici olur.
RUN useradd --system --uid 10001 --create-home hookrelay
USER hookrelay

COPY --from=build --chown=hookrelay:hookrelay /build/services/${SERVICE}/target/${SERVICE}.jar /app/app.jar

# MaxRAMPercentage: JVM'e "konteyner limitinin %75'ini kullan" der.
# Sabit -Xmx yazmak, compose'da limiti degistirdiginizde JVM'in
# haberi olmamasi demek — ve OOMKilled.
# networkaddress.cache.ttl: JVM'in DNS onbellegi. Varsayilan 30 saniye,
# ama konteyner ortaminda bir servis yeniden olusunca 30 saniye kor
# kalmak bile fazla. 10 saniyeye cekiyoruz.
# (Bkz. gateway/HttpClientDnsConfig — Reactor Netty'yi JVM cozumleyicisine
#  yonlendirmeden bu ayarin gateway'e hicbir etkisi olmaz.)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC \
  -Dnetworkaddress.cache.ttl=10 -Dnetworkaddress.cache.negative.ttl=5 \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
