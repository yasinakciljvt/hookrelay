package dev.hookrelay.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Her istege bir kimlik takar ve arkaya iletir.
 *
 * Dagitik sistemde hata ayiklamanin ilk kurali: bir istegin 4 servisteki
 * izini surebilmek. Musteri "saat 14:32'de bir hata aldim" dediginde
 * elinizde bir kimlik yoksa 4 servisin loglarini zaman damgasina gore
 * karsilastirmaya calisirsiniz - ki bu, saatler suren bir istir.
 *
 * (Tam cozum OpenTelemetry ile dagitik izleme. Bu filtre onun basit
 * hali: tek baslik, sifir bagimlilik, %80 fayda.)
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER, requestId)
                .build();

        exchange.getResponse().getHeaders().set(HEADER, requestId);

        String finalId = requestId;
        return chain.filter(exchange.mutate().request(mutated).build())
                .doOnSuccess(v -> log.debug("{} {} → {}",
                        mutated.getMethod(), mutated.getURI().getPath(), finalId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
