package io.perflens.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** JFR event for one finding on an analyzed trace. */
@Name("io.perflens.Finding")
@Label("PerfLens Finding")
@Category("PerfLens")
@Description("A performance problem PerfLens found in a trace")
@StackTrace(false)
public final class FindingEvent extends jdk.jfr.Event {

    @Label("Trace Id")
    String traceId;

    @Label("Target Key")
    String targetKey;

    @Label("Type")
    String findingType;

    @Label("Severity")
    String severity;

    @Label("Message")
    String message;

    @Label("Evidence")
    String evidence;
}
