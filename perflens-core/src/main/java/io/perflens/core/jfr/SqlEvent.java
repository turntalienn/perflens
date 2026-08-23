package io.perflens.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** JFR event for one JDBC statement executed under a trace. Carries the issuing stack trace. */
@Name("io.perflens.Sql")
@Label("PerfLens SQL")
@Category("PerfLens")
@Description("A JDBC statement executed inside a PerfLens trace")
public final class SqlEvent extends jdk.jfr.Event {

    @Label("Trace Id")
    String traceId;

    @Label("SQL")
    String sql;

    @Label("Success")
    boolean success;
}
