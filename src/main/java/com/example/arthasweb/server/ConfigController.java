package com.example.arthasweb.server;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.arthasweb.config.LlmProperties;

@RestController
public class ConfigController {

    private final LlmProperties llmProperties;

    public ConfigController(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @GetMapping("/api/models")
    public List<LlmProperties.ModelInfo> listModels() {
        return llmProperties.getModels();
    }
}
