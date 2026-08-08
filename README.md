# PerfLens

PerfLens is a Java 17 / Spring Boot 3 library for answering one practical
question after an API call:

> What did Hibernate and the database layer actually do during this call?

Applications add the starter, mark selected controller or service boundaries with
`@PerfLensTrace`, enable a target key through the database control table, and then
inspect persisted trace reports. A report captures SQL text and timing, Hibernate
entity and collection events, flush/dirty-change evidence, likely N+1 patterns,
and a read-only HTML view for quick inspection.

## Modules

- `perflens-core`: framework-neutral annotation, trace model, context, SQL normalizer, and finding analyzers.
- `perflens-spring-boot-autoconfigure`: Spring Boot auto-configuration, MVC/AOP boundaries, JDBC capture, Hibernate listener registration, storage, retention, and viewer.
- `perflens-spring-boot-starter`: dependency-only starter for applications.
- `perflens-demo-app`: minimal Spring Boot MVC/JPA app used to prove starter wiring.
- `perflens-integration-tests`: cross-module tests for starter behavior and hardening paths.

## Quick Start

Add the starter to a Spring Boot app:

```xml
<dependency>
  <groupId>io.perflens</groupId>
  <artifactId>perflens-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Annotate an API or service boundary:

```java
@GetMapping("/orders/{id}")
@PerfLensTrace(value = "order-detail", label = "Order detail")
OrderDto getOrder(@PathVariable long id) {
    return orderService.getOrder(id);
}
```

Create the schema deliberately with your migration tool. A MySQL/MariaDB
baseline script is included at
`perflens-spring-boot-autoconfigure/src/main/resources/META-INF/perflens/mysql/V1__perflens_schema.sql`.
For local tests only, schema creation can be enabled with:

```yaml
perflens:
  storage:
    initialize-schema: true
```

Enable one target key without restarting the app:

```sql
insert into perflens_control(target_key, enabled, capture_parameters)
values ('order-detail', true, false)
on duplicate key update enabled = true, updated_at = current_timestamp;
```

Enable the read-only viewer only in a controlled environment:

```yaml
perflens:
  viewer:
    enabled: true
    require-role: true
    required-role: PERFLENS_ADMIN
```

Viewer routes:

- `/perflens/reports`
- `/perflens/reports/{traceId}`

## Building

```bash
mvn test
```
