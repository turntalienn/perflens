package io.perflens.autoconfigure.app;

import io.perflens.core.annotation.PerfLensTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@PerfLensTrace("nightly-job")
public class NightlyJob {

    private final JdbcTemplate jdbcTemplate;

    public NightlyJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String run() {
        return String.valueOf(jdbcTemplate.queryForObject("select count(*) from book", Integer.class));
    }
}
