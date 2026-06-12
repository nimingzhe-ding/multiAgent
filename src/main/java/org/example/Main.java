package org.example;

import org.example.service.SessionDBService;
import org.example.service.SkillDBService;
import org.example.service.UserDBService;
import org.example.service.KnowledgeSourceService;
import org.example.service.McpConnectionService;
import org.example.service.MemoryMonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Main implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @Autowired
    private SessionDBService sessionDBService;

    @Autowired
    private SkillDBService skillDBService;

    @Autowired
    private UserDBService userDBService;

    @Autowired
    private KnowledgeSourceService knowledgeSourceService;

    @Autowired
    private MemoryMonitoringService memoryMonitoringService;

    @Autowired
    private McpConnectionService mcpConnectionService;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) {
        logger.info("Initializing persistence stores...");
        try {
            userDBService.initialize();
            logger.info("User store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize user store: {}", e.getMessage());
        }
        try {
            sessionDBService.initialize();
            logger.info("Session store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize session store: {}", e.getMessage());
        }
        try {
            skillDBService.initialize();
            logger.info("Skill store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize skill store: {}", e.getMessage());
        }
        try {
            knowledgeSourceService.initialize();
            logger.info("Knowledge source store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize knowledge source store: {}", e.getMessage());
        }
        try {
            memoryMonitoringService.initialize();
            logger.info("Memory monitoring store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize memory monitoring store: {}", e.getMessage());
        }
        try {
            mcpConnectionService.initialize();
            logger.info("MCP connection store initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize MCP connection store: {}", e.getMessage());
        }
        logger.info("SuperBizAgent startup complete");
    }
}
