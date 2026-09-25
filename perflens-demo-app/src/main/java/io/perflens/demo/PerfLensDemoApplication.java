package io.perflens.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Build with {@code ./mvnw clean install} and start the jar from {@code perflens-demo-app/target}.
 * Call {@code /authors} and {@code /authors/fixed}, then open {@code /perflens/reports}.
 */
@SpringBootApplication
public class PerfLensDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerfLensDemoApplication.class, args);
    }
}
