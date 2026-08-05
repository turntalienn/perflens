package io.perflens.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal application used by the build to prove that the starter can join a
 * normal Spring Boot MVC/JPA application context.
 */
@SpringBootApplication
public class PerfLensDemoApplication {

    /**
     * Starts the demo app when a developer wants to exercise the starter
     * manually.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(PerfLensDemoApplication.class, args);
    }
}
