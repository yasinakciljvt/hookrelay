package dev.hookrelay.dispatcher.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * BULKHEAD (gemi bolmesi) deseni - "gurultulu komsu" problemi.
 *
 * PROBLEM
 * Tek bir musterinin sunucusu 30 saniyede cevap veriyor. Dispatcher'da
 * 12 tuketici is parcacigi var. O musteriye 12 mesaj gelirse 12 is
 * parcaciginin hepsi 30 saniye bloke olur ve DIGER BUTUN musterilerin
 * teslimati durur. Bir musterinin yavasligi, sistemin tamaminin
 * yavasligina donusur.
 *
 * COZUM
 * -----
 * Her endpoint'e ayri bir kota: ayni anda en fazla N istek. Kota
 * dolduysa mesaj beklemez - kuyruga geri konur, is parcacigi serbest
 * kalir ve baska musterilere hizmet etmeye devam eder.
 *
 * Gemi metaforu buradan: govdeyi bolmelere ayirirsiniz, bir bolme su
 * alinca gemi batmaz.
 *
 * Neden tryAcquire(0) ve tryAcquire(5 saniye) degil: beklemek, bloke
 * olmanin baska adi. Bekleyen is parcacigi da calismiyor demektir.
 */
@Component
public class EndpointBulkhead {

    private final Map<UUID, Semaphore> permits = new ConcurrentHashMap<>();
    private final int maxConcurrentPerEndpoint;

    public EndpointBulkhead(
            @Value("${hookrelay.bulkhead.max-concurrent-per-endpoint:4}") int max) {
        this.maxConcurrentPerEndpoint = max;
    }

    /** @return true ise izin alindi ve release() cagrilmak ZORUNDA. */
    public boolean tryAcquire(UUID endpointId) {
        return semaphore(endpointId).tryAcquire();
    }

    public void release(UUID endpointId) {
        semaphore(endpointId).release();
    }

    public int available(UUID endpointId) {
        return semaphore(endpointId).availablePermits();
    }

    private Semaphore semaphore(UUID endpointId) {
        return permits.computeIfAbsent(endpointId,
                id -> new Semaphore(maxConcurrentPerEndpoint));
    }
}
