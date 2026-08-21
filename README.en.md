# HookRelay

[![CI](https://github.com/yasinakciljvt/hookrelay/actions/workflows/ci.yml/badge.svg)](https://github.com/yasinakciljvt/hookrelay/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**A reliable webhook delivery service.** It gets an event to your customer's server — even if that server crashes, stalls, returns 429, or stays down for three hours.

Spring Boot 3 · Java 21 · Kafka · Redis · PostgreSQL · Docker

> The codebase, guides and inline comments are written in **Turkish**. They explain *why* each decision was made, not what the code does. This page is a short English summary.

```bash
./scripts/baslat.sh   # build + start everything, wait until healthy
./scripts/demo.sh     # create 1 app + 4 endpoints, send 40 events
```

→ **http://localhost:3000**

## What it solves

Naive webhook code (`restClient.post().uri(url)...`) survives about a week in production. Then: lost events on 5xx, thread pool exhaustion from one slow customer, three hours of events gone during a customer's maintenance window, duplicate deliveries after adding retries, and no audit trail when someone says "the webhook never arrived."

Stripe, GitHub and Shopify all had to solve this. Svix and Hookdeck built companies on it.

## Architecture

Six services, split by **who writes which data**:

| Service | Port | Owns | Role |
|---|---|---|---|
| `gateway` | 8080 | — | Single entry point |
| `admin-api` | 8081 | applications, endpoints, health projection | Control plane |
| `ingest-api` | 8082 | messages, outbox | Event intake, idempotency, fan-out |
| `dispatcher` | 8083 | deliveries, attempts | HTTP send, retry decisions |
| `retry-scheduler` | 8084 | *(stateless)* | Delayed retries |
| `chaos-target` | 8085 | *(in-memory)* | Demo victim — a badly behaved customer server |

The dispatcher never calls admin-api on the hot path. Endpoint config is replicated from a **compacted Kafka topic** into Redis. Consequence: **the control plane can be completely down and delivery keeps working.**

## Three things worth reading the code for

**1. Delayed messages in Kafka.** Kafka has no "deliver this in 30 minutes." `Thread.sleep` blows past `max.poll.interval.ms` and deadlocks the whole consumer group. The solution is one topic per delay tier plus `pause`/`seek`: when the head record isn't due, pause the partition and seek back to it, while still calling `poll()` so heartbeats keep flowing. It works because every record in a tier shares the same delay, so `not-before` order equals offset order — checking only the head record is sufficient.
→ [`RetrySchedulerRunner.java`](services/retry-scheduler/src/main/java/dev/hookrelay/retryscheduler/runner/RetrySchedulerRunner.java)

**2. Transactional Outbox.** A DB write and a Kafka publish cannot share a transaction. Write the Kafka record into a table inside the business transaction; a separate poller (`FOR UPDATE SKIP LOCKED`) drains it. The cost is at-least-once delivery, which is why every outgoing request carries `X-HookRelay-Id`.
→ [`OutboxPublisher.java`](libs/outbox/src/main/java/dev/hookrelay/outbox/OutboxPublisher.java)

**3. A distributed circuit breaker in Redis Lua.** Resilience4j's breaker lives inside one JVM; four dispatcher replicas would each keep their own view and lose it on restart. Per-endpoint breaking needs shared state.
→ [`circuit_breaker.lua`](libs/common/src/main/resources/lua/circuit_breaker.lua)

## Break it on purpose

```bash
./scripts/kir.sh broker          # stop Kafka   → ingest still returns 202 (outbox)
./scripts/kir.sh redis           # stop Redis   → why the replica is not "just a cache"
./scripts/kir.sh cokuk-tuketici  # SIGKILL dispatcher → at-least-once, live
./scripts/kir.sh olcekle 3       # 3 replicas  → how 12 partitions get shared
```

## Honest limits

No exactly-once (it doesn't exist in distributed systems). Single outbox poller — `SKIP LOCKED` breaks ordering across replicas. Three databases on one Postgres server. Single Kafka broker, RF=1. API-key auth only.

Each is documented in code with its reasoning: chosen, not forgotten.

## License

MIT
