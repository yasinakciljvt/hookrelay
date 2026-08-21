package dev.hookrelay.ingestapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.crypto.ApiKeys;
import dev.hookrelay.common.redis.AppConfigCache;
import dev.hookrelay.common.redis.EndpointConfigCache;
import dev.hookrelay.contracts.ApplicationConfig;
import dev.hookrelay.contracts.EndpointConfig;
import dev.hookrelay.ingestapi.repo.MessageRepository;
import dev.hookrelay.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Uctan uca giris akisi - gercek Postgres, gercek Redis, gercek Kafka.
 *
 * NEDEN H2 DEGIL: bu testin dogruladigi seylerin cogu H2'de calismaz veya
 * farkli calisir - kismi UNIQUE indeks, FOR UPDATE SKIP LOCKED, jsonb.
 * H2 ile gecen bir test, uretimde patlayan bir kod anlamina gelebilir.
 *
 * Docker gerekir. Yoksa Testcontainers testi atlar degil, HATA verir -
 * bu da bilincli: "test gecti" ile "test calismadi" ayni sey degil.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IngestFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hookrelay_ingest");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Replikatorler kapali: bu testte konfigurasyonu Redis'e ELLE koyuyoruz,
        // boylece admin-api'ye ihtiyac duymadan giris akisini test edebiliyoruz.
        registry.add("hookrelay.endpoint-config.replicate", () -> "false");
        registry.add("hookrelay.app-config.replicate", () -> "false");
        registry.add("hookrelay.outbox.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppConfigCache apps;
    @Autowired EndpointConfigCache endpoints;
    @Autowired MessageRepository messages;
    @Autowired OutboxRepository outbox;

    private static final UUID APP_ID = UUID.randomUUID();

    // Endpoint kimlikleri SABIT.
    //
    // Ilk yazilisinda her @BeforeEach icinde UUID.randomUUID() cagriliyordu.
    // Redis konteyneri sinif boyunca yasadigi ve hr:app:{id}:eps kumesi hic
    // temizlenmedigi icin endpoint sayisi her testte ikiser artiyordu:
    // 1. test 2 endpoint gorurken 3. test 6 goruyordu ve deliveryCount
    // beklentileri tutmuyordu. Testin kendisi sizintiliydi.
    //
    // Sabit kimlikle put() ayni anahtarin uzerine yaziyor, kume hep 2 kalir.
    private static final UUID EP_ALL  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EP_PAID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private String apiKey;

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        outbox.deleteAll();

        apiKey = ApiKeys.generate();
        apps.put(new ApplicationConfig(APP_ID, "test-app", ApiKeys.hash(apiKey),
                true, false, 1));

        // Iki endpoint: biri her seye abone, digeri sadece order.paid'e
        endpoints.put(new EndpointConfig(EP_ALL, APP_ID,
                "http://ornek/hepsi", "whsec_1", Set.of("*"),
                true, false, 0, 6, 10_000, 1));
        endpoints.put(new EndpointConfig(EP_PAID, APP_ID,
                "http://ornek/odeme", "whsec_2", Set.of("order.paid"),
                true, false, 0, 6, 10_000, 1));

        // Testin kendi varsayimini dogrula: tam iki endpoint gorunmeli.
        // Bu satir olmasaydi yukaridaki sizinti sessizce geri gelebilirdi.
        assertThat(endpoints.listByApplication(APP_ID))
                .as("her test tam iki endpoint ile baslamali")
                .hasSize(2);
    }

    @Test
    @DisplayName("Olay kabul edilir ve abone endpoint sayisi kadar teslimat uretilir")
    void fan_out_dogru_calisir() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"order.paid","payload":{"orderId":4711}}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryCount").value(2))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(messages.count()).isEqualTo(1);
        assertThat(outbox.count())
                .as("her endpoint icin bir outbox kaydi")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Abone olmayan olay tipi teslimat uretmez")
    void abone_olmayan_tip_atlanir() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"user.deleted","payload":{}}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryCount").value(1));   // sadece "*"
    }

    @Test
    @DisplayName("Ayni Idempotency-Key ikinci kez mesaj olusturmaz")
    void idempotency_calisir() throws Exception {
        String body = """
                {"eventType":"order.paid","payload":{"orderId":1}}""";

        mockMvc.perform(post("/v1/events")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Idempotency-Key", "siparis-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicate").value(false));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/v1/events")
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Idempotency-Key", "siparis-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.duplicate").value(true));
        }

        assertThat(messages.count())
                .as("dort istek, tek mesaj")
                .isEqualTo(1);
        assertThat(outbox.count())
                .as("teslimat da bir kez uretilir")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Gecersiz API anahtari 401 doner")
    void gecersiz_anahtar_reddedilir() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .header("Authorization", "Bearer hr_sahte")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"x","payload":{}}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Authorization basligi olmadan 401")
    void baslik_yoksa_reddedilir() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"x","payload":{}}"""))
                .andExpect(status().isUnauthorized());
    }
}
