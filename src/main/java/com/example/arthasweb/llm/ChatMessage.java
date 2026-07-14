package com.example.arthasweb.llm;

import java.util.List;
import java.util.Map;

public record ChatMessage(String role, String content) {

    public static ChatMessage system(String c) {
        return new ChatMessage("system", c);
    }

    public static ChatMessage user(String c) {
        return new ChatMessage("user", c);
    }

    public static ChatMessage assistant(String c) {
        return new ChatMessage("assistant", c);
    }

    public Map<String, String> toMap() {
        return Map.of("role", role, "content", content);
    }

    public Map<String, Object> toObjectMap() {
        return Map.of("role", role, "content", content);
    }
}
