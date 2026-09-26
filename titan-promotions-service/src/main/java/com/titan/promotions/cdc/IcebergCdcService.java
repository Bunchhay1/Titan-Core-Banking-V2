package com.titan.promotions.cdc;

import io.debezium.config.Configuration;
import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.connect.source.SourceRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class IcebergCdcService {
    private DebeziumEngine<RecordChangeEvent<SourceRecord>> engine;
    private ExecutorService executor;
    
    @Value("${spring.datasource.url}")
    private String dbUrl;
    
    @Value("${spring.datasource.username}")
    private String dbUser;
    
    @Value("${spring.datasource.password}")
    private String dbPassword;
    
    @PostConstruct
    public void start() {
        Configuration config = Configuration.create()
            .with("connector.class", "io.debezium.connector.postgresql.PostgresConnector")
            .with("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
            .with("offset.storage.file.filename", "/tmp/offsets.dat")
            .with("offset.flush.interval.ms", 60000)
            .with("name", "titan-promotions-cdc")
            .with("database.hostname", extractHost(dbUrl))
            .with("database.port", "5432")
            .with("database.user", dbUser)
            .with("database.password", dbPassword)
            .with("database.dbname", "titandb")
            .with("table.include.list", "public.applied_promotions")
            .with("plugin.name", "pgoutput")
            .build();
        
        engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
            .using(config.asProperties())
            .notifying(this::handleChangeEvent)
            .build();
        
        executor = Executors.newSingleThreadExecutor();
        executor.execute(engine);
        log.info("Debezium CDC engine started for Iceberg export");
    }
    
    private void handleChangeEvent(RecordChangeEvent<SourceRecord> event) {
        SourceRecord record = event.record();
        log.debug("CDC event: {} - {}", record.topic(), record.key());
        // Export to Iceberg data lake (S3/HDFS)
    }
    
    private String extractHost(String jdbcUrl) {
        return jdbcUrl.split("//")[1].split(":")[0];
    }
    
    @PreDestroy
    public void stop() throws Exception {
        if (engine != null) engine.close();
        if (executor != null) executor.shutdown();
    }
}
