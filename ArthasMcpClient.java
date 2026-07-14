package com.example.inspector.client;

import com.example.inspector.model.McpToolDef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arthas MCP 客户端（Java 8 + OkHttp 版本）。
 *
 * 依赖：implementation 'com.squareup.okhttp3:okhttp:4.12.0'
 *
 * 通过 JSON-RPC 2.0 与 Arthas MCP Server 通信，支持：
 *   - 初始化连接 (initialize)
 *   - 列出可用工具 (tools/list)
 *   - 调用工具 (tools/call)
 */
public class ArthasMcpClient {

    private static final Logger log = LoggerFactory.getLogger(ArthasMcpClient.class);

    private static final String MCP_PROTOCOL_VERSION = "2025-06-18";
    private static final String CLIENT_NAME    = "arthas-ai-inspector";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final MediaType JSON_TYPE   = MediaType.get("application/json; charset=utf-8");

    private final String mcpEndpoint;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    private volatile String sessionId;
    private String authToken;
    private List<McpToolDef> cachedTools;

    public ArthasMcpClient(String arthasHost, int arthasPort) {
        this.mcpEndpoint = String.format("http://%s:%d/mcp", arthasHost, arthasPort);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)  // trace/watch 可能需要较长时间
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
        log.info("ArthasMcpClient 初始化, endpoint={}", mcpEndpoint);
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    // =========================================================================
    //  公开 API
    // =========================================================================

    public void initialize() throws Exception {
        log.info("正在初始化 MCP 连接...");

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", MCP_PROTOCOL_VERSION);
        params.putObject("capabilities");

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        JsonNode response = sendJsonRpcRequest("initialize", params);

        JsonNode result = response.path("result");
        String serverName    = result.path("serverInfo").path("name").asText("unknown");
        String serverVersion = result.path("serverInfo").path("version").asText("unknown");
        String serverProtocol = result.path("protocolVersion").asText("unknown");
        log.info("MCP 服务端: {} v{}, protocol={}", serverName, serverVersion, serverProtocol);

        sendJsonRpcNotification("notifications/initialized", mapper.createObjectNode());
        log.info("MCP 连接初始化完成");
    }

    public List<McpToolDef> listTools() throws Exception {
        if (cachedTools != null) {
            return cachedTools;
        }

        log.info("正在获取 MCP 工具列表...");
        JsonNode response = sendJsonRpcRequest("tools/list", mapper.createObjectNode());

        JsonNode toolsArray = response.path("result").path("tools");
        List<McpToolDef> tools = new ArrayList<McpToolDef>();

        if (toolsArray.isArray()) {
            for (JsonNode toolNode : toolsArray) {
                McpToolDef tool = new McpToolDef();
                tool.setName(toolNode.path("name").asText());
                tool.setDescription(toolNode.path("description").asText(""));

                JsonNode inputSchema = toolNode.path("inputSchema");
                if (!inputSchema.isMissingNode() && inputSchema.isObject()) {
                    tool.setInputSchema(
                            mapper.convertValue(inputSchema, new TypeReference<Map<String, Object>>() {}));
                } else {
                    Map<String, Object> emptySchema = new LinkedHashMap<String, Object>();
                    emptySchema.put("type", "object");
                    emptySchema.put("properties", new LinkedHashMap<String, Object>());
                    tool.setInputSchema(emptySchema);
                }
                tools.add(tool);
                log.debug("  发现工具: {} - {}", tool.getName(), tool.getDescription());
            }
        }

        log.info("共获取到 {} 个 MCP 工具", tools.size());
        this.cachedTools = tools;
        return tools;
    }

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        log.info("调用 MCP 工具: {} , 参数: {}", toolName, arguments);

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);

        if (arguments != null && !arguments.isEmpty()) {
            params.set("arguments", mapper.valueToTree(arguments));
        } else {
            params.putObject("arguments");
        }

        JsonNode response = sendJsonRpcRequest("tools/call", params);
        JsonNode result = response.path("result");

        if (result.has("isError") && result.path("isError").asBoolean()) {
            String errorText = extractContentText(result);
            log.warn("工具执行返回错误: {}", errorText);
            return "ERROR: " + errorText;
        }

        String contentText = extractContentText(result);
        log.debug("工具 {} 返回结果: {}", toolName,
                contentText.length() > 200 ? contentText.substring(0, 200) + "..." : contentText);
        return contentText;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public void close() {
        log.info("关闭 MCP 客户端连接");
        this.sessionId  = null;
        this.cachedTools = null;
    }

    // =========================================================================
    //  JSON-RPC 通信层
    // =========================================================================

    private JsonNode sendJsonRpcRequest(String method, ObjectNode params) throws Exception {
        int id = requestIdCounter.incrementAndGet();

        ObjectNode rpcRequest = mapper.createObjectNode();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", id);
        rpcRequest.put("method", method);
        rpcRequest.set("params", params);

        String requestBody = mapper.writeValueAsString(rpcRequest);
        log.debug("MCP Request [id={}]: method={}", id, method);

        Request.Builder httpBuilder = new Request.Builder()
                .url(mcpEndpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(JSON_TYPE, requestBody));

        if (sessionId != null) {
            httpBuilder.addHeader("Mcp-Session-Id", sessionId);
        }
        if (authToken != null && !authToken.isEmpty()) {
            httpBuilder.addHeader("Authorization", "Bearer " + authToken);
        }

        try (Response httpResponse = httpClient.newCall(httpBuilder.build()).execute()) {
            // 提取并缓存 Session ID
            String sid = httpResponse.header("Mcp-Session-Id");
            if (sid != null && !sid.equals(this.sessionId)) {
                log.debug("MCP Session ID 更新: {}", sid);
                this.sessionId = sid;
            }

            int statusCode = httpResponse.code();
            ResponseBody body = httpResponse.body();
            String responseBody = body != null ? body.string() : "";

            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("MCP 请求失败, HTTP status=" + statusCode + ", body=" + responseBody);
            }

            String contentType = httpResponse.header("Content-Type", "");

            JsonNode jsonResponse;
            if (contentType != null && contentType.contains("text/event-stream")) {
                jsonResponse = parseSSEResponse(responseBody, id);
            } else {
                jsonResponse = mapper.readTree(responseBody);
            }

            JsonNode error = jsonResponse.path("error");
            if (!error.isMissingNode() && error.isObject()) {
                int errorCode = error.path("code").asInt();
                String errorMessage = error.path("message").asText("Unknown error");
                throw new RuntimeException("MCP JSON-RPC 错误: code=" + errorCode + ", message=" + errorMessage);
            }

            return jsonResponse;
        }
    }

    private void sendJsonRpcNotification(String method, ObjectNode params) throws Exception {
        ObjectNode rpcNotification = mapper.createObjectNode();
        rpcNotification.put("jsonrpc", "2.0");
        rpcNotification.put("method", method);
        rpcNotification.set("params", params);

        String requestBody = mapper.writeValueAsString(rpcNotification);
        log.debug("MCP Notification: method={}", method);

        Request.Builder httpBuilder = new Request.Builder()
                .url(mcpEndpoint)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON_TYPE, requestBody));

        if (sessionId != null) {
            httpBuilder.addHeader("Mcp-Session-Id", sessionId);
        }
        if (authToken != null && !authToken.isEmpty()) {
            httpBuilder.addHeader("Authorization", "Bearer " + authToken);
        }

        try (Response httpResponse = httpClient.newCall(httpBuilder.build()).execute()) {
            String sid = httpResponse.header("Mcp-Session-Id");
            if (sid != null) this.sessionId = sid;

            int statusCode = httpResponse.code();
            if (statusCode >= 400) {
                log.warn("MCP 通知响应状态异常: {}", statusCode);
            }
        }
    }

    private JsonNode parseSSEResponse(String sseBody, int expectedId) throws Exception {
        JsonNode lastValidResponse = null;

        String[] lines = sseBody.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;

            String data = trimmed.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;

            try {
                JsonNode node = mapper.readTree(data);
                if (node.has("id") && node.path("id").asInt() == expectedId) {
                    return node;
                }
                if (node.has("jsonrpc")) {
                    lastValidResponse = node;
                }
            } catch (Exception e) {
                log.trace("跳过无效的 SSE data: {}", data);
            }
        }

        if (lastValidResponse != null) {
            return lastValidResponse;
        }

        throw new RuntimeException("无法从 SSE 响应中解析 JSON-RPC 结果");
    }

    private String extractContentText(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.path("text").asText());
                }
            }
            return sb.toString();
        }
        if (result.has("text")) {
            return result.path("text").asText();
        }
        return result.toString();
    }
}
