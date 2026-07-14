package com.example.arthasweb.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.arthasweb.config.ArthasProperties;
import com.example.arthasweb.tunnel.TunnelWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal MCP (Model Context Protocol) server over Streamable HTTP, so external MCP
 * clients (Cherry Studio / Cursor / Claude Desktop) can connect to this platform and
 * diagnose any registered Arthas agent. Tools are executed through the tunnel.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArthasCommandExecutor executor;
    private final TunnelWebSocketHandler tunnel;
    private final String mcpPassword;

    public McpController(ArthasProperties props, TunnelWebSocketHandler tunnel,
                         @Value("${server.port:8080}") int serverPort) {
        this.executor = new ArthasCommandExecutor(props, serverPort, tunnel);
        this.tunnel = tunnel;
        this.mcpPassword = props.getPassword();
    }

    /**
     * Validate Authorization header if password is configured.
     * Expects 'Authorization: Bearer <password>' format.
     */
    private boolean validateAuth(String authHeader) {
        if (mcpPassword == null || mcpPassword.isEmpty()) {
            return true; // no password configured, skip validation
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        return mcpPassword.equals(token);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", "use POST"));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(@RequestBody String body,
                                       @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
                                       @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Validate authorization if password is configured
        if (!validateAuth(authHeader)) {
            logger.warn("mcp authentication failed for session={}", sessionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("Mcp-Session-Id", sessionId == null ? UUID.randomUUID().toString() : sessionId)
                    .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"authentication failed\"}}");
        }
        try {
            JsonNode req = objectMapper.readTree(body);
            String method = req.has("method") ? req.get("method").asText() : "";
            JsonNode idNode = req.get("id");
            Object id = (idNode == null || idNode.isNull()) ? null : (idNode.isNumber() ? idNode.asLong() : idNode.asText());
            JsonNode params = req.has("params") ? req.get("params") : objectMapper.createObjectNode();

            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "arthas-web", "version", "1.0.0"));
                case "notifications/initialized", "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", ArthasMcpTools.mcpToolList());
                case "tools/call" -> handleToolCall(params);
                default -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("code", -32601);
                    err.put("message", "method not found: " + method);
                    yield err;
                }
            };

            String session = sessionId == null ? UUID.randomUUID().toString() : sessionId;
            String response;
            if ("notifications/initialized".equals(method) || "ping".equals(method)) {
                return ResponseEntity.ok()
                        .header("Mcp-Session-Id", session)
                        .body("");
            }
            if (result.containsKey("code") && result.containsKey("message")) {
                Map<String, Object> errResp = new LinkedHashMap<>();
                errResp.put("jsonrpc", "2.0");
                errResp.put("id", id);
                errResp.put("error", result);
                response = objectMapper.writeValueAsString(errResp);
                return ResponseEntity.status(400).header("Mcp-Session-Id", session).body(response);
            }

            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("jsonrpc", "2.0");
            ok.put("id", id);
            ok.put("result", result);
            response = objectMapper.writeValueAsString(ok);
            return ResponseEntity.ok().header("Mcp-Session-Id", session).body(response);

        } catch (Exception e) {
            logger.error("mcp error", e);
            return ResponseEntity.status(500).body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
        }
    }

    private Map<String, Object> handleToolCall(JsonNode params) throws Exception {
        String name = params.has("name") ? params.get("name").asText() : "";
        JsonNode argsNode = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        @SuppressWarnings("unchecked")
        Map<String, Object> args = objectMapper.convertValue(argsNode, Map.class);

        ArthasMcpTools.Tool tool = ArthasMcpTools.find(name);
        if (tool == null) {
            return Map.of("isError", true, "content", Map.of("type", "text", "text", "unknown tool: " + name));
        }
        String target = args.containsKey("target") ? String.valueOf(args.get("target")) : null;
        if (target == null || target.isEmpty()) {
            return Map.of("isError", true, "content", Map.of("type", "text", "text", "missing required arg: target (agentId)"));
        }
        if (!tunnel.isAgentOnline(target)) {
            return Map.of("isError", true, "content", Map.of("type", "text",
                    "text", "agent [" + target + "] is not connected. Run the attach command on the target container first."));
        }
        String command = tool.build.apply(args);
        String output = executor.execute(target, command);
        if (output == null) output = "";
        if (output.length() > 50000) output = output.substring(0, 50000) + "\n... (output truncated)";
        return Map.of("isError", false, "content", Map.of("type", "text", "text", output));
    }
}
