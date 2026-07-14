package com.example.arthasweb.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.arthasweb.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    public static final String SYSTEM_PROMPT = """
            You are ArthasGPT, an expert in Alibaba Arthas (a Java diagnostic / observability tool).
            You help SRE/developers analyze and debug remote Java servers through Arthas.

            Rules:
            1. When you need to run an Arthas command, put it inside a fenced code block tagged `arthas`.
               Example:
               ```arthas
               thread -n 3
               ```
            2. Only output ONE arthas command per fenced block, and keep it a single line with no extra flags
               that require interactive input.
            3. Prefer safe, read-only commands: dashboard, thread, jvm, sysprop, sysenv, getstatic, ognl (read),
               trace, watch, stack, monitor, tt, heapdump, classloader, sc, sm, jad, vmtool, perfcounter.
               Avoid destructive operations (e.g. shutdown, stop, reset only when explicitly asked).
            4. After the user provides command output, explain what it means in plain language, point out
               anomalies (high CPU threads, OOM risks, slow methods, memory leaks) and suggest next steps.
            5. If you don't need a command, just answer normally.
            6. Respond in the same language as the user (default Chinese if ambiguous).
            7. You have arthas tool functions available (dashboard, thread, jvm, trace, watch,
               stack, monitor, sc, sm, ognl, heapdump, etc.). Prefer calling these tools over
               writing raw commands. After the tool results come back, analyze them and reply
               in natural language.
            """;

    private final LlmProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(LlmProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + (props.getApiKey() == null ? "" : props.getApiKey()))
                .build();
    }

    /** Calls the OpenAI-compatible chat completions endpoint and returns the assistant content. */
    public String chat(List<ChatMessage> messages) {
        List<Map<String, Object>> tools = null;
        LlmCallResult r = chatWithTools(messages, tools);
        return r.content() == null ? "" : r.content();
    }

    /**
     * Calls chat completions with optional tool definitions. The model may reply with
     * tool_calls instead of (or in addition to) text.
     */
    public LlmCallResult chatWithTools(List<ChatMessage> messages, List<Map<String, Object>> tools) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (ChatMessage m : messages) {
            msgs.add(m.toObjectMap());
        }
        return doCall(msgs, tools);
    }

    /** Raw call accepting already-built message maps with optional model override. */
    public LlmCallResult chatWithToolsRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return doCall(messages, tools, null);
    }

    /** Raw call with model override (null = use configured default). */
    public LlmCallResult chatWithToolsRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model) {
        return doCall(messages, tools, model);
    }

    /** Raw call with model override and thinking flag. When thinking=true, sends thinking parameter to API. */
    public LlmCallResult chatWithToolsRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model, boolean thinking) {
        return doCall(messages, tools, model, thinking);
    }

    /** Plain call without tools, accepting raw message maps. */
    public String chatRaw(List<Map<String, Object>> messages) {
        return doCall(messages, null, null).content();
    }

    private LlmCallResult doCall(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return doCall(messages, tools, null);
    }

    private LlmCallResult doCall(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model) {
        return doCall(messages, tools, model, false);
    }

    private LlmCallResult doCall(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model, boolean thinking) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model != null && !model.isBlank() ? model : props.getModel());
        body.put("temperature", props.getTemperature());
        body.put("messages", messages);
        body.put("enable_thinking", thinking);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String raw = restClient.post()
                .uri("/chat/completions")
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return parseCallResult(raw);
    }

    private LlmCallResult parseCallResult(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                JsonNode error = root.get("error");
                if (error != null) return new LlmCallResult("[LLM error] " + error, List.of());
                return new LlmCallResult("[LLM returned empty response]", List.of());
            }
            JsonNode msg = choices.get(0).get("message");
            String content = msg.has("content") && !msg.get("content").isNull() ? msg.get("content").asText() : null;
            List<LlmToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = msg.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    JsonNode fn = tc.get("function");
                    if (fn == null) continue;
                    String name = fn.get("name").asText();
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                        try {
                            args = objectMapper.convertValue(fn.get("arguments"), Map.class);
                        } catch (Exception e) {
                            args = Map.of("__raw", fn.get("arguments").asText());
                        }
                    }
                    String id = tc.has("id") ? tc.get("id").asText() : name;
                    calls.add(new LlmToolCall(id, name, args));
                }
            }
            return new LlmCallResult(content, calls);
        } catch (Exception e) {
            logger.error("failed to parse LLM tool response", e);
            return new LlmCallResult("[LLM response parse error] " + (raw == null ? "null" : raw), List.of());
        }
    }

    public record LlmToolCall(String id, String name, Map<String, Object> arguments) {}

    public record LlmCallResult(String content, List<LlmToolCall> toolCalls) {}
}
