package com.titan.promotions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = {
    "com.titan.promotions.repository",
    "com.titan.promotions.clawback",
    "com.titan.promotions.eventsourcing",
    "com.titan.promotions.federation",
    "com.titan.promotions.geospatial",
    "com.titan.promotions.outbox",
    "com.titan.promotions.shadow"
})
@EnableNeo4jRepositories(basePackages = "com.titan.promotions.graph")
public class PromotionsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionsServiceApplication.class, args);
    }
}
