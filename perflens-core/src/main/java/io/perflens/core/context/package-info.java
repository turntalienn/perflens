/**
 * Trace context and immutable evidence objects produced during one profiled call.
 *
 * <p>The context is thread-bound, while the report objects are immutable. This
 * split keeps runtime collection fast and makes persisted reports safe to pass to
 * storage, viewers, and future exporters.</p>
 */
package io.perflens.core.context;
