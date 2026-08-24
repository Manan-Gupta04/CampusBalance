package com.campusbalance.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnalyticsApplication {

    public static void main(String[] args) {
        // Mongo URI and server port now come from application.properties, which reads them
        // from the MONGODB_URI / PORT environment variables — see application.properties.
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
