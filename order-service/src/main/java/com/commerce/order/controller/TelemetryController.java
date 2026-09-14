package com.commerce.order.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
public class TelemetryController {

    private final ObjectMapper objectMapper;

    public TelemetryController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/telemetry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void telemetry(@RequestBody Map<String, Object> body) {
        try {
            log.info("evt=web.{} payload={}",
                    body.getOrDefault("evt", "unknown"),
                    objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("evt=web.telemetry.error error={}", e.getMessage());
        }
    }
}