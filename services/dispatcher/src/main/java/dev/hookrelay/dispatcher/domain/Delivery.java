package dev.hookrelay.dispatcher.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Bir endpoint'e yapilacak tek teslimatin guncel durumu. */
@Entity
@Table(name = "delivery")
public class Delivery {

    public enum Status {
        /** Kuyrukta veya deneniyor. */
        PENDING,
        /** 2xx alindi. Bitti. */
        SUCCEEDED,
        /** Butun denemeler tukendi, DLQ'da. */
        EXHAUSTED,
        /** Endpoint silinmis/kapali - hic denenmedi. */
        DISCARDED
    }

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false)     private UUID messageId;
    @Column(name = "application_id", nullable = false) private UUID applicationId;
    @Column(name = "endpoint_id", nullable = false)    private UUID endpointId;
    @Column(name = "event_type", nullable = false, length = 120) private String eventType;

    /**
     * Govdenin kopyasi.
     *
     * Bu bir DENORMALIZASYON ve bilincli. Govdenin "asil" sahibi
     * ingest-api'nin message tablosu. Buraya da yazmamizin tek sebebi
     * YENIDEN GONDERIM: kullanici arayuzden "tekrar dene" dediginde
     * dispatcher'in baska bir servise HTTP atmasi gerekmesin.
     *
     * Bedeli: disk. Kazanci: dispatcher'in kendi kendine yetmesi.
     * Bir mikroservisin baska bir servise sormadan isini bitirebilmesi,
     * odenmeye deger bir disk maliyetidir.
     */
    @Column(columnDefinition = "text")                 private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false) private int attempts;

    @Column(name = "last_status")                    private Integer lastStatus;
    @Column(name = "last_error", length = 1000)      private String lastError;
    @Column(name = "next_attempt_at")                private Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false)   private Instant createdAt;
    @Column(name = "updated_at", nullable = false)   private Instant updatedAt;

    protected Delivery() {}

    public Delivery(UUID id, UUID messageId, UUID applicationId, UUID endpointId,
                    String eventType, String payload) {
        this.id = id;
        this.messageId = messageId;
        this.applicationId = applicationId;
        this.endpointId = endpointId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void succeeded(int attempt, Integer httpStatus) {
        this.status = Status.SUCCEEDED;
        this.attempts = attempt;
        this.lastStatus = httpStatus;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void retrying(int attempt, Integer httpStatus, String error, Instant nextAt) {
        this.status = Status.PENDING;
        this.attempts = attempt;
        this.lastStatus = httpStatus;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAt;
        this.updatedAt = Instant.now();
    }

    /**
     * Hic istek atilmadan ertelendi (devre kesici / hiz siniri / bulkhead).
     *
     * attempts'e DOKUNMAZ. Onceki surumde bu, recordBlocked icinde
     * "retrying(task.attempt() - 1, ...)" diye ifade ediliyordu -
     * calisiyordu ama okuyana "neden bir eksik?" diye sorduruyordu.
     * Niyeti kodun kendisi soylemeli.
     */
    public void blocked(String reason, Instant nextAt) {
        this.status = Status.PENDING;
        this.lastStatus = null;
        this.lastError = truncate(reason);
        this.nextAttemptAt = nextAt;
        this.updatedAt = Instant.now();
    }

    public void exhausted(int attempt, Integer httpStatus, String error) {
        this.status = Status.EXHAUSTED;
        this.attempts = attempt;
        this.lastStatus = httpStatus;
        this.lastError = truncate(error);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void discarded(String reason) {
        this.status = Status.DISCARDED;
        this.lastError = truncate(reason);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    /** Yeniden gonderim icin durumu sifirla. */
    public void resetForReplay() {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }

    public UUID getId()             { return id; }
    public UUID getMessageId()      { return messageId; }
    public UUID getApplicationId()  { return applicationId; }
    public UUID getEndpointId()     { return endpointId; }
    public String getEventType()    { return eventType; }
    public String getPayload()      { return payload; }
    public Status getStatus()       { return status; }
    public int getAttempts()        { return attempts; }
    public Integer getLastStatus()  { return lastStatus; }
    public String getLastError()    { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
