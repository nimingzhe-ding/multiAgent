package org.example;

import org.example.service.SessionDBService;
import org.example.service.SkillDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @Autowired
    private SessionDBService sessionDBService;

    @Autowired
    private SkillDBService skillDBService;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) {
        logger.info("Initializing SQLite databases...");
        try {
            sessionDBService.initialize();
            logger.info("Session database initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize session database: {}", e.getMessage());
        }
        try {
            skillDBService.initialize();
            logger.info("Skill database initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize skill database: {}", e.getMessage());
        }
        logger.info("SuperBizAgent startup complete");
    }
}
