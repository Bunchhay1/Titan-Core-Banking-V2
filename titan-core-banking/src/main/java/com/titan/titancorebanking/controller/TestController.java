package com.titan.titancorebanking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;

@RestController
public class TestController {

    @Autowired
    private DataSource dataSource;

    @GetMapping("/test-connection")
    public String testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return "✅ SUCCESS: Connected to Titan Bank DB! (PostgreSQL on Proxmox)";
        } catch (Exception e) {
            return "❌ FAILED: " + e.getMessage();
        }
    }
}