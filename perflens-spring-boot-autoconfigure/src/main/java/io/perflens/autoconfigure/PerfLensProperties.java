package io.perflens.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for PerfLens.
 *
 * <p>The defaults keep runtime tracing safe. Applications can add the starter
 * and annotations without immediately exposing reports or creating tables. Teams
 * then opt into schema initialization and the viewer deliberately for local,
 * test, or controlled environments.</p>
 */
@ConfigurationProperties(prefix = "perflens")
public class PerfLensProperties {

    /**
     * Master switch for all PerfLens auto-configuration.
     */
    private boolean enabled = true;

    private final Storage storage = new Storage();
    private final Viewer viewer = new Viewer();
    private final Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Storage getStorage() {
        return storage;
    }

    public Viewer getViewer() {
        return viewer;
    }

    public Retention getRetention() {
        return retention;
    }

    /**
     * Storage-specific settings.
     */
    public static class Storage {

        /**
         * Creates PerfLens tables at startup when explicitly enabled.
         *
         * <p>Production applications should normally apply the SQL migration
         * deliberately instead of enabling this switch.</p>
         */
        private boolean initializeSchema;

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    /**
     * HTML viewer settings.
     */
    public static class Viewer {

        /**
         * Enables the read-only report viewer routes.
         */
        private boolean enabled;

        /**
         * Requires {@link jakarta.servlet.http.HttpServletRequest#isUserInRole(String)}
         * to pass before rendering reports.
         */
        private boolean requireRole = true;

        /**
         * Servlet role required to inspect reports when role checks are enabled.
         */
        private String requiredRole = "PERFLENS_ADMIN";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequireRole() {
            return requireRole;
        }

        public void setRequireRole(boolean requireRole) {
            this.requireRole = requireRole;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole;
        }
    }

    /**
     * Retention cleanup settings.
     */
    public static class Retention {

        /**
         * Age after which trace rows are eligible for deletion.
         */
        private Duration maxAge = Duration.ofDays(7);

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }
}
