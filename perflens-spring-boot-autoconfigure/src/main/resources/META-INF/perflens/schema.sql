-- PerfLens tables. Plain SQL that runs on H2, MySQL/MariaDB and PostgreSQL.
-- Apply it with your migration tool (copy it into a Flyway/Liquibase changeset),
-- or set perflens.storage.initialize-schema=true for local development.

create table perflens_control (
  target_key    varchar(160) not null primary key,
  enabled       boolean      not null,
  enabled_until timestamp    null,
  updated_at    timestamp    default current_timestamp
);

create table perflens_trace (
  trace_id              varchar(36)   not null primary key,
  target_key            varchar(160)  not null,
  label                 varchar(240),
  boundary              varchar(20)   not null,
  route                 varchar(360),
  http_method           varchar(10),
  status_code           integer,
  error_message         varchar(1000),
  started_at            timestamp     not null,
  duration_micros       bigint        not null,
  sql_count             integer       not null,
  sql_micros            bigint        not null,
  transaction_count     integer       not null,
  connection_count      integer       not null,
  hibernate_event_count integer       not null,
  finding_count         integer       not null,
  max_severity          varchar(10),
  dropped_events        integer       not null,
  allocated_bytes       bigint        not null,
  cpu_micros            bigint        not null,
  gc_count              bigint        not null,
  gc_millis             bigint        not null,
  entity_load_count     integer       not null
);

create index perflens_trace_started on perflens_trace (started_at);
create index perflens_trace_target on perflens_trace (target_key, started_at);

create table perflens_span (
  trace_id        varchar(36)  not null,
  span_id         varchar(36)  not null,
  parent_span_id  varchar(36),
  target_key      varchar(160) not null,
  label           varchar(240),
  boundary        varchar(20)  not null,
  started_at      timestamp    not null,
  duration_micros bigint       not null,
  primary key (trace_id, span_id)
);

create table perflens_sql_event (
  trace_id        varchar(36)   not null,
  seq             integer       not null,
  span_id         varchar(36)   not null,
  sql_text        text          not null,
  duration_micros bigint        not null,
  success         boolean       not null,
  error_message   varchar(1000),
  primary key (trace_id, seq)
);

create table perflens_hibernate_event (
  trace_id        varchar(36)  not null,
  seq             integer      not null,
  span_id         varchar(36)  not null,
  event_type      varchar(20)  not null,
  entity_name     varchar(255),
  collection_role varchar(255),
  detail          varchar(255),
  primary key (trace_id, seq)
);

create table perflens_finding (
  trace_id     varchar(36)   not null,
  seq          integer       not null,
  finding_type varchar(60)   not null,
  severity     varchar(10)   not null,
  message      varchar(1000) not null,
  evidence     text          not null,
  primary key (trace_id, seq)
);
