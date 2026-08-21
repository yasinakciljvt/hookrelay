package dev.hookrelay.contracts;

/** Kafka kayit basliklari ve giden HTTP basliklari. */
public final class Headers {

    private Headers() {}

    // --- Kafka kayit basliklari ---

    /** Bu kaydin islenebilecegi en erken an (epoch millis). Retry katmanlarinin kalbi. */
    public static final String NOT_BEFORE = "x-hr-not-before";
    /** Kacinci deneme (1 tabanli). */
    public static final String ATTEMPT = "x-hr-attempt";
    /** Dagitik izleme baglami. */
    public static final String TRACE_ID = "x-hr-trace-id";
    /** DLQ'ya dusme sebebi. */
    public static final String DEAD_REASON = "x-hr-dead-reason";

    // --- Musteriye giden HTTP basliklari ---

    /** Teslimatin tekil kimligi. Alici tarafin idempotency anahtari budur. */
    public static final String OUT_ID = "X-HookRelay-Id";
    /** Imza zaman damgasi (epoch saniye). Replay saldirisi penceresini kapatir. */
    public static final String OUT_TIMESTAMP = "X-HookRelay-Timestamp";
    /** "t=<ts>,v1=<hex hmac>" - Stripe'in kullandigi bicim. */
    public static final String OUT_SIGNATURE = "X-HookRelay-Signature";
    /** Olay tipi, alicinin govdeyi acmadan yonlendirme yapabilmesi icin. */
    public static final String OUT_EVENT_TYPE = "X-HookRelay-Event-Type";
    /** Kacinci deneme oldugu. Alici "bu tekrar mi" diye bakabilsin. */
    public static final String OUT_ATTEMPT = "X-HookRelay-Attempt";
}
