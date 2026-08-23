package io.perflens.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** JFR event covering one traced request or method call. */
@Name("io.perflens.Trace")
@Label("PerfLens Trace")
@Category("PerfLens")
@Description("One traced request or method call, with its SQL, transaction and resource totals")
@StackTrace(false)
public final class TraceEvent extends jdk.jfr.Event {

    @Label("Trace Id")
    String traceId;

    @Label("Target Key")
    String targetKey;

    @Label("Route")
    String route;

    @Label("HTTP Method")
    String httpMethod;

    @Label("Status Code")
    int statusCode;

    @Label("SQL Statements")
    int sqlCount;

    @Label("SQL Time")
    @Timespan(Timespan.NANOSECONDS)
    long sqlTime;

    @Label("Transactions")
    int transactionCount;

    @Label("Connection Checkouts")
    int connectionCount;

    @Label("Entities Loaded")
    int entitiesLoaded;

    @Label("Allocated")
    @DataAmount
    long allocatedBytes;

    @Label("CPU Time")
    @Timespan(Timespan.NANOSECONDS)
    long cpuTime;
}
