-- PerfLens baseline schema for MySQL and MariaDB deployments.
-- Applications may run this through Flyway, Liquibase, or their normal DBA
-- process instead of enabling perflens.storage.initialize-schema.

create table if not exists perflens_control (
  target_key varchar(160) primary key,
  enabled boolean not null,
  enabled_until timestamp null,
  capture_parameters boolean not null default false,
  updated_at timestamp not null default current_timestamp
);

create table if not exists perflens_trace (
  trace_id varchar(36) primary key,
  target_key varchar(160) not null,
  label varchar(240) null,
  boundary varchar(40) not null,
  route varchar(360) null,
  http_method varchar(16) null,
  status_code int null,
  error_message longtext null,
  started_at timestamp not null,
  ended_at timestamp not null,
  duration_ms double not null,
  query_count int not null,
  hibernate_event_count int not null,
  finding_count int not null
);

create table if not exists perflens_span (
  id bigint auto_increment primary key,
  span_id varchar(36) not null,
  trace_id varchar(36) not null,
  parent_span_id varchar(36) null,
  target_key varchar(160) not null,
  label varchar(240) null,
  boundary varchar(40) not null,
  started_at timestamp not null,
  ended_at timestamp not null,
  duration_ms double not null
);

create table if not exists perflens_sql_event (
  id bigint auto_increment primary key,
  trace_id varchar(36) not null,
  span_id varchar(36) not null,
  ordinal_value int not null,
  sql_text longtext not null,
  normalized_sql longtext not null,
  duration_ms double not null,
  success boolean not null,
  error_message longtext null
);

create table if not exists perflens_hibernate_event (
  id bigint auto_increment primary key,
  trace_id varchar(36) not null,
  span_id varchar(36) not null,
  ordinal_value int not null,
  event_type varchar(60) not null,
  entity_name varchar(300) null,
  collection_role varchar(300) null,
  detail_text longtext null
);

create table if not exists perflens_finding (
  id bigint auto_increment primary key,
  trace_id varchar(36) not null,
  finding_type varchar(100) not null,
  severity varchar(40) not null,
  message_text longtext not null,
  evidence_text longtext not null
);

create table if not exists perflens_retention_run (
  id bigint auto_increment primary key,
  started_at timestamp not null,
  completed_at timestamp null,
  deleted_trace_count int not null default 0,
  error_message longtext null
);

create index idx_perflens_trace_target_started on perflens_trace(target_key, started_at);
create index idx_perflens_trace_ended_at on perflens_trace(ended_at);
create index idx_perflens_span_trace on perflens_span(trace_id);
create index idx_perflens_sql_trace_ordinal on perflens_sql_event(trace_id, ordinal_value);
create index idx_perflens_hibernate_trace_ordinal on perflens_hibernate_event(trace_id, ordinal_value);
create index idx_perflens_finding_trace on perflens_finding(trace_id);
