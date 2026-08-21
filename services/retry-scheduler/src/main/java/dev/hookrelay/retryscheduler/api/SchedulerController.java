package dev.hookrelay.retryscheduler.api;

import dev.hookrelay.contracts.Topics;
import dev.hookrelay.retryscheduler.runner.RetrySchedulerRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zamanlayicinin ic durumunu disari acar.
 *
 * "Hangi partition duraklatilmis, ne kadar sonra devam edecek" -
 * bu bilgi olmadan sistem sihirli bir kara kutu gibi gorunur.
 * Arayuzde canli gostermek, tasarimin en ogretici parcasini
 * gozle gorulur kiliyor.
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final RetrySchedulerRunner runner;

    public SchedulerController(RetrySchedulerRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tiers", Topics.RETRY_TIERS);
        out.put("pausedPartitions", runner.pausedPartitions());
        return out;
    }
}
