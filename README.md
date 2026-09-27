# PerfLens

PerfLens is a Spring Boot starter that tells you what a request actually did to your
database and your heap. You get every SQL statement it ran, the lazy loads Hibernate
did behind your back, how long each transaction held a connection, how much memory
the request allocated, and a plain-English list of what looks wrong.

You need Java 17+ and Spring Boot 3.x.

## What it looks like

Hit an endpoint with a lazy-loading problem and you get this in the log:

```
WARN io.perflens : PerfLens [authors] GET /authors 200 28.5 ms: 6 SQL (0.9 ms), 1 tx, 1 connections, 1 findings, 2.5 MB allocated, 25.9 ms CPU, 12 entities loaded
  WARNING N_PLUS_ONE: Same query ran 5 times with different parameters. Load the rows in one query (join fetch, @EntityGraph, @BatchSize or an IN query).
    5 executions, 0.8 ms total: select b1_0.author_id,b1_0.id,b1_0.title from book b1_0 where b1_0.author_id=? | lazy collections initialized: io.perflens.demo.Author.books x5
```

Requests without problems are logged at DEBUG, so a healthy app stays quiet.

These are the things it looks for:

| Finding | When it fires |
|---|---|
| `N_PLUS_ONE` | One SELECT shape runs 4+ times with different parameters. Names the lazy collection responsible when there is one. |
| `DUPLICATE_QUERY` | The exact same SELECT, with the same parameters, runs twice in one request. |
| `SLOW_QUERY` | A statement takes over 200 ms. |
| `SLOW_TRANSACTION` | A transaction stays open over 500 ms. It tells you whether that time was SQL or something else done while holding the connection. |
| `CONNECTION_POOL_PRESSURE` | Waiting 50 ms+ for a connection, a fully used pool, or one request holding several connections at once (`REQUIRES_NEW`, parallel workers). |
| `LOCK_CONTENTION` | A deadlock, lock-wait timeout or serialization failure. Works with MySQL, PostgreSQL, SQL Server, Oracle and H2 error codes. |
| `HIGH_ALLOCATION` | The request allocated 64 MB+ of heap. |
| `GC_PRESSURE` | GC pauses adding up to 100 ms+ landed during the request. |
| `LARGE_PERSISTENCE_CONTEXT` | Hibernate loaded 1,000+ entities in one request. |

All the thresholds can be changed (see [Configuration](#configuration)).

## Getting started

PerfLens isn't on Maven Central yet, so install it locally first:

```bash
./mvnw install -DskipTests
```

Add the starter to your app:

```xml
<dependency>
  <groupId>io.perflens</groupId>
  <artifactId>perflens-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Then either trace everything, which is handy locally:

```yaml
perflens:
  trace-all-requests: true
```

or pick the endpoints you care about:

```java
@GetMapping("/orders/{id}")
@PerfLensTrace("order-detail")
OrderDto order(@PathVariable long id) { ... }
```

```yaml
perflens:
  control:
    targets: order-detail   # comma-separated, or "*" for every annotated method
```

An annotation on its own does nothing until its key is listed. That way you can leave
annotations in the code everywhere and switch tracing on only where you need it.
`@PerfLensTrace` also works on service methods. Inside a request they show up as
nested spans. Outside one (scheduled jobs, message listeners) they start their own
trace.

## Catching problems in tests

`PerfLens.capture` runs a piece of code under a trace and hands back the report. This
makes it easy to fail the build when someone reintroduces an N+1:

```java
@Test
void orderListHasNoNPlusOne() throws Exception {
    TraceReport report = PerfLens.capture("orders",
            () -> mockMvc.perform(get("/orders")).andExpect(status().isOk())).report();

    assertThat(report.findingsOfType(NPlusOneAnalyzer.TYPE)).isEmpty();
    assertThat(report.sqlEvents()).hasSizeLessThanOrEqualTo(2);
}
```

For this, the starter only needs to be on the test classpath. You don't need
annotations or config.

## Memory, CPU and GC

Each trace records how much heap the request allocated and how much CPU it used,
read from the JVM's per-thread counters. Work on worker threads that joined the trace
(see below) is included. It also notes any GC pauses that happened while the request
was running.

That gives you a quick answer to "which endpoint is generating all this garbage"
without attaching a profiler. Keep in mind that GC is JVM-wide. `GC_PRESSURE` means a
pause hit this request, not that this request caused it. Look at `HIGH_ALLOCATION` for
that.

The counters need a HotSpot or OpenJ9 JVM. Elsewhere they read as `-1` and the memory
checks are skipped.

## Java Flight Recorder

PerfLens also publishes JFR events: `io.perflens.Trace` per request, `io.perflens.Sql`
per statement (with the stack trace that ran it) and `io.perflens.Finding`. They cost
nothing unless a recording is running. During an incident you can record for a while
and open the file in JDK Mission Control. Your requests then sit on the same timeline
as GC pauses, allocation samples and lock waits:

```bash
jcmd <pid> JFR.start name=incident settings=profile
jcmd <pid> JFR.dump name=incident filename=incident.jfr
jfr print --events io.perflens.Trace,io.perflens.Finding incident.jfr
```

Set `perflens.jfr: false` to turn it off.

## @Async and worker threads

A trace lives on the thread that started the request. To follow work onto other
threads, PerfLens registers a `TaskDecorator`, so `@Async` methods and Spring-managed
executors report into the caller's trace. If your app already has a `TaskDecorator`,
PerfLens backs off. Wrap yours to keep both:

```java
@Bean
TaskDecorator taskDecorator() {
    return new PerfLensTaskDecorator(new MdcTaskDecorator());
}
```

With a plain `ExecutorService`, wrap the task yourself:

```java
executor.submit(PerfLensTraceContext.wrap(() -> calculate(plate)));
```

## Keeping reports

Out of the box, reports only go to the log. To keep them, turn on storage and
optionally the built-in viewer:

```yaml
perflens:
  storage:
    enabled: true
    initialize-schema: true   # local only; use a migration elsewhere
  viewer:
    enabled: true
```

The schema is plain SQL in `META-INF/perflens/schema.sql` inside the autoconfigure jar.
Copy it into a Flyway or Liquibase migration for real environments. Reports older than
seven days are deleted automatically.

The viewer lives at `/perflens/reports` and by default requires the `PERFLENS_ADMIN`
role. SQL text can contain customer data, so keep it behind your normal security.

You can also send reports anywhere else (metrics, Kafka, your own test helper) by
declaring a `TraceReportListener` bean.

## Turning tracing on without a restart

Set `perflens.control.database: true` and PerfLens will also read enabled keys from the
`perflens_control` table, refreshed every 10 seconds:

```sql
insert into perflens_control (target_key, enabled, enabled_until)
values ('order-detail', true, current_timestamp + interval '1' hour);
```

The `enabled_until` column lets you trace a production endpoint for an hour and have
it switch itself off. To control tracing some other way, for example from a feature
flag, provide your own `TraceControl` bean.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `perflens.enabled` | `true` | Set to `false` to switch PerfLens off completely. |
| `perflens.trace-all-requests` | `false` | Trace every MVC request, annotated or not. |
| `perflens.control.targets` | empty | Keys to trace, or `*`. |
| `perflens.control.database` | `false` | Also read keys from `perflens_control`. |
| `perflens.control.refresh-interval` | `10s` | |
| `perflens.analysis.repeated-select-threshold` | `4` | Repeats that count as N+1. |
| `perflens.analysis.slow-query` | `200ms` | |
| `perflens.analysis.slow-transaction` | `500ms` | |
| `perflens.analysis.connection-wait` | `50ms` | |
| `perflens.analysis.allocation` | `64MB` | |
| `perflens.analysis.gc-time` | `100ms` | |
| `perflens.analysis.entity-load-threshold` | `1000` | |
| `perflens.log.enabled` | `true` | |
| `perflens.storage.enabled` | `false` | |
| `perflens.storage.initialize-schema` | `false` | |
| `perflens.storage.retention` | `7d` | |
| `perflens.viewer.enabled` | `false` | Needs storage. |
| `perflens.viewer.require-role` | `true` | |
| `perflens.viewer.required-role` | `PERFLENS_ADMIN` | |
| `perflens.jfr` | `true` | |
| `perflens.propagate-to-executors` | `true` | |
| `perflens.async-reporting` | `true` | Analyze and save reports off the request thread. |
| `perflens.max-events-per-trace` | `2000` | Stops one huge request from using unbounded memory. |

## How it works

PerfLens wraps your `DataSource` bean in a proxy. It keeps the bean's real class, so
injecting `HikariDataSource` and Actuator's pool metrics still work. Connections and
statements are wrapped too. Every `execute` call is timed. Bound parameters are hashed
but never stored, and that hash is what tells an N+1 (different values) apart from a
duplicate query (same values). Transactions are timed from `setAutoCommit(false)` to
commit or rollback, which covers `@Transactional` with both JPA and plain JDBC. Pool
gauges are read from HikariCP when a connection is handed out.

On the Hibernate side, a small integrator listens for entity loads, lazy collection
loads, flushes and writes. A Spring MVC interceptor keeps each trace open until the
response is fully written, so lazy loading during JSON serialization is counted. An
aspect handles `@PerfLensTrace` on service methods.

When nothing is being traced, the overhead is a proxy hop and a thread-local check per
JDBC call. PerfLens's own queries (control table, storage) are excluded from your
traces.

## Limitations

- Threads that never joined the trace aren't seen. That includes parallel streams,
  `CompletableFuture.supplyAsync` without an executor, and unwrapped executor tasks.
- Async MVC handlers (`Callable`, `DeferredResult`) are skipped.
- Transaction timing relies on `setAutoCommit(false)`. If you've set
  `hibernate.connection.provider_disables_autocommit=true`, no transactions are reported.
- No WebFlux or R2DBC support.

## Project layout

- `perflens-core`: the annotation, trace model, analyzers, JFR events and
  `PerfLens.capture`. No dependencies. Modules that only need the annotation can
  depend on this.
- `perflens-spring-boot-autoconfigure`: the JDBC and Hibernate hooks, MVC interceptor,
  aspect, storage and viewer.
- `perflens-spring-boot-starter`: what applications add.
- `perflens-demo-app`: a small app with an N+1 endpoint and its fixed version.

## Building and running the demo

```bash
./mvnw verify
```

Some tests start PostgreSQL and MySQL in containers. They're skipped if Docker isn't
around. With Podman, set `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and
`TESTCONTAINERS_RYUK_DISABLED=true`.

To try the demo:

```bash
./mvnw clean install -DskipTests
java -jar perflens-demo-app/target/perflens-demo-app-0.1.0-SNAPSHOT.jar
```

Then open http://localhost:8080/authors and http://localhost:8080/authors/fixed, and
compare the two in the log or at http://localhost:8080/perflens/reports.
