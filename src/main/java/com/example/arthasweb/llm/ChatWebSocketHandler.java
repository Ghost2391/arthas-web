package com.example.arthasweb.llm;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.arthasweb.config.ArthasProperties;
import com.example.arthasweb.server.ServerConfig;
import com.example.arthasweb.server.ServerService;
import com.example.arthasweb.tunnel.TunnelWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ServerService serverService;
    private final TunnelWebSocketHandler tunnel;
    private final LlmService llm;
    private final ArthasCommandExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ServerService serverService, TunnelWebSocketHandler tunnel,
                                LlmService llm, ArthasProperties props,
                                @Value("${server.port:8080}") int serverPort) {
        this.serverService = serverService;
        this.tunnel = tunnel;
        this.llm = llm;
        this.executor = new ArthasCommandExecutor(props, serverPort, tunnel);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        send(session, Map.of("type", "system",
                "text", "已连接到对话分析。输入服务器的问题，我会用 Arthas 命令帮你分析。\n输入 /tools 查看可用工具箱，输入 /clear 清空上下文。"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String serverId = extractServerId(session);
        String payload = message.getPayload();

        if (payload.trim().equals("/clear")) {
            histories.remove(serverId);
            send(session, Map.of("type", "system", "text", "上下文已清空。"));
            return;
        }

        if (payload.trim().equals("/tools")) {
            StringBuilder sb = new StringBuilder("可用工具箱 (" + ArthasMcpTools.TOOLS.size() + " 个):\n\n");
            for (ArthasMcpTools.Tool t : ArthasMcpTools.TOOLS) {
                sb.append("• ").append(t.name).append(" — ").append(t.description).append("\n");
            }
            send(session, Map.of("type", "system", "text", sb.toString()));
            return;
        }

        String userText = payload;
        int maxRounds = 5;
        String model = null;
        boolean thinking = false;
        try {
            JsonNode json = objectMapper.readTree(payload);
            if (json.has("text")) {
                userText = json.get("text").asText();
            }
            if (json.has("maxRounds")) {
                maxRounds = json.get("maxRounds").asInt();
                if (maxRounds < 1) maxRounds = 1;
                if (maxRounds > 20) maxRounds = 20;
            }
            if (json.has("model")) {
                model = json.get("model").asText();
            }
            if (json.has("thinking")) {
                thinking = json.get("thinking").asBoolean();
            }
        } catch (Exception e) {
            // plain text message, use defaults
        }

        final String finalText = userText;
        final int finalMaxRounds = maxRounds;
        final String finalModel = model;
        final boolean finalThinking = thinking;
        CompletableFuture.runAsync(() -> handleUserMessage(session, serverId, finalText, finalMaxRounds, finalModel, finalThinking));
    }

    private void handleUserMessage(WebSocketSession session, String serverId, String userText, int maxRounds, String model, boolean thinking) {
        try {
            ServerConfig server = serverService.get(serverId);
            if (server == null) {
                send(session, Map.of("type", "error", "text", "未找到服务器配置: " + serverId));
                return;
            }
            String agentId = server.getAgentId();
            if (!tunnel.isAgentOnline(agentId)) {
                send(session, Map.of("type", "error", "text",
                        "服务器 [" + server.getName() + "] 的 Arthas agent 未连接。请先在目标容器执行 attach 命令。"));
                return;
            }

            List<ChatMessage> history = histories.computeIfAbsent(serverId, k -> new ArrayList<>());
            List<Map<String, Object>> raw = new ArrayList<>();
            raw.add(Map.of("role", "system", "content", LlmService.SYSTEM_PROMPT));
            for (ChatMessage m : history) raw.add(m.toObjectMap());
            raw.add(Map.of("role", "user", "content", userText));

            String finalAnswer = null;
            StringBuilder allOutput = new StringBuilder();

            for (int round = 0; round < maxRounds; round++) {
                send(session, Map.of("type", "status",
                        "text", round == 0 ? "🤔 思考中..." : "📊 分析中..."));
                LlmService.LlmCallResult result = llm.chatWithToolsRaw(raw, ArthasMcpTools.functionSpecs(), model, thinking);

                if (result.toolCalls() == null || result.toolCalls().isEmpty()) {
                    finalAnswer = result.content();
                    break;
                }

                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.content() == null ? "" : result.content());
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (LlmService.LlmToolCall tc : result.toolCalls()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    try {
                        fn.put("arguments", objectMapper.writeValueAsString(tc.arguments()));
                    } catch (Exception e) {
                        fn.put("arguments", "{}");
                    }
                    Map<String, Object> tcj = new LinkedHashMap<>();
                    tcj.put("id", tc.id());
                    tcj.put("type", "function");
                    tcj.put("function", fn);
                    tcList.add(tcj);
                }
                assistantMsg.put("tool_calls", tcList);
                raw.add(assistantMsg);

                for (LlmService.LlmToolCall tc : result.toolCalls()) {
                    ArthasMcpTools.Tool tool = ArthasMcpTools.find(tc.name());
                    String cmd = tool == null ? tc.name() : tool.build.apply(tc.arguments());
                    send(session, Map.of("type", "command", "text", cmd));
                    String output = executor.execute(agentId, cmd);
                    if (output == null) output = "";
                    if (output.length() > 8000) output = output.substring(0, 8000) + "\n... (output truncated)";
                    send(session, Map.of("type", "result", "text", output));
                    allOutput.append("### ").append(cmd).append("\n").append(output).append("\n\n");

                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("role", "tool");
                    tr.put("tool_call_id", tc.id());
                    tr.put("content", output);
                    raw.add(tr);
                }
                finalAnswer = result.content();
            }

            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = allOutput.length() > 0
                        ? allOutput.toString()
                        : "(LLM 未返回分析结果)";
            }
            send(session, Map.of("type", "assistant", "text", finalAnswer));
            history.add(ChatMessage.user(userText));
            history.add(ChatMessage.assistant(finalAnswer));
            if (history.size() > 40) {
                history.subList(0, history.size() - 40).clear();
            }
        } catch (Exception e) {
            logger.error("chat error", e);
            send(session, Map.of("type", "error", "text", "处理出错: " + e.getMessage()));
        }
    }

    private String extractServerId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return "";
        String p = uri.getPath(); // /ws/chat/{serverId}
        String[] parts = p.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "";
    }

    private synchronized void send(WebSocketSession session, Map<String, String> msg) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            logger.warn("send to client failed", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // histories are kept per serverId for continuity across reconnects
    }
}
