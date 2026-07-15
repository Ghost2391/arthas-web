package com.example.arthasweb.llm;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.arthasweb.config.ArthasProperties;
import com.example.arthasweb.tunnel.TunnelWebSocketHandler;

public class ArthasCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ArthasCommandExecutor.class);
    private static final Pattern PROMPT = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*$");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\?0-9]*[a-zA-Z]");

    private final ArthasProperties props;
    private final int serverPort;
    private final TunnelWebSocketHandler tunnelHandler;

    public ArthasCommandExecutor(ArthasProperties props, int serverPort) {
        this(props, serverPort, null);
    }

    public ArthasCommandExecutor(ArthasProperties props, int serverPort, TunnelWebSocketHandler tunnelHandler) {
        this.props = props;
        this.serverPort = serverPort;
        this.tunnelHandler = tunnelHandler;
    }

    public String execute(String agentId, String command) throws Exception {
        if (tunnelHandler != null && tunnelHandler.isAgentOnline(agentId)) {
            // 对于远程 agent，优先尝试 HTTP API（端口 8563），fallback 到 tunnel
            String remoteHost = tunnelHandler.getAgentRemoteHost(agentId);
            if (remoteHost != null && !"127.0.0.1".equals(remoteHost) && !"localhost".equals(remoteHost)) {
                try {
                    return executeViaHttp(remoteHost, agentId, command);
                } catch (Exception e) {
                    logger.warn("http execute failed for agent={}, falling back to tunnel: {}", agentId, e.toString());
                }
            }

            try {
                return executeViaTunnel(agentId, command);
            } catch (Exception e) {
                logger.warn("tunnel execute failed for agent={}: {}", agentId, e.toString());
                String isLocal = System.getProperty("arthas.agent." + agentId + ".local");
                if ("true".equals(isLocal)) {
                    return executeViaTelnet(agentId, command);
                }
                throw e;
            }
        }
        return executeViaTelnet(agentId, command);
    }

    private String executeViaHttp(String remoteHost, String agentId, String command) throws Exception {
        int httpPort = props.getHttpPort() > 0 ? props.getHttpPort() : 8563;
        int timeout = props.getIdleSeconds() > 0 ? props.getIdleSeconds() : 25;
        String url = "http://" + remoteHost + ":" + httpPort + "/api";
        logger.info("executing command via HTTP: url={} command={}", url, command);

        String body = "{\"action\":\"exec\",\"command\":\"" + escapeJson(command) + "\"}";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout + 15))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        String password = props.getPassword();
        if (password != null && !password.isEmpty()) {
            builder.header("Authorization", "Bearer " + password);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String respBody = response.body();
        logger.info("HTTP response status={} bodyLen={}", status, respBody != null ? respBody.length() : 0);

        if (status >= 400) {
            throw new RuntimeException("HTTP error " + status + ": " + respBody);
        }

        // parse arthas /api response
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(respBody);
        String state = node.has("state") ? node.get("state").asText() : "";
        if ("ERROR".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)) {
            String msg = node.has("message") ? node.get("message").asText() : "unknown error";
            throw new RuntimeException("arthas command error: " + msg);
        }
        com.fasterxml.jackson.databind.JsonNode bodyNode = node.has("body") ? node.get("body") : null;
        com.fasterxml.jackson.databind.JsonNode results = null;
        if (bodyNode != null && bodyNode.has("results")) {
            results = bodyNode.get("results");
        } else {
            results = node.get("results");
        }

        StringBuilder out = new StringBuilder();
        if (results != null && results.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode r : results) {
                String type = r.has("type") ? r.get("type").asText() : "";
                if (r.isTextual()) {
                    out.append(r.asText());
                } else if (r.has("output")) {
                    out.append(r.get("output").asText());
                } else if ("status".equals(type)) {
                    continue;
                } else if ("profiler".equals(type)) {
                    String result = r.has("executeResult") ? r.get("executeResult").asText() : "";
                    String outputFile = r.has("outputFile") ? r.get("outputFile").asText() : "";
                    out.append(result.trim());
                    if (!outputFile.isEmpty()) {
                        out.append("\n文件已生成: ").append(outputFile);
                    }
                } else {
                    out.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
                }
                out.append("\n");
            }
        } else if (results != null && results.isTextual()) {
            out.append(results.asText());
        } else if (results != null) {
            out.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        }
        String text = out.toString().trim();
        logger.info("HTTP result: len={}", text.length());
        return text.isEmpty() ? ANSI.matcher(respBody).replaceAll("") : text;
    }

    public String readRemoteFile(String agentId, String filePath) throws Exception {
        String remoteHost = tunnelHandler != null ? tunnelHandler.getAgentRemoteHost(agentId) : null;
        if (remoteHost == null || "127.0.0.1".equals(remoteHost) || "localhost".equals(remoteHost)) {
            return execute(agentId, "cat " + filePath);
        }
        int httpPort = props.getHttpPort() > 0 ? props.getHttpPort() : 8563;
        int timeout = props.getIdleSeconds() > 0 ? props.getIdleSeconds() : 30;
        String url = "http://" + remoteHost + ":" + httpPort + "/api";
        String body = "{\"action\":\"exec\",\"command\":\"cat " + escapeJson(filePath) + "\"}";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout + 15))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        String password = props.getPassword();
        if (password != null && !password.isEmpty()) {
            builder.header("Authorization", "Bearer " + password);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String respBody = response.body();

        if (status >= 400) {
            throw new RuntimeException("HTTP error " + status + " when reading file");
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(respBody);
        String state = node.has("state") ? node.get("state").asText() : "";
        if ("ERROR".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)) {
            throw new RuntimeException("remote error: " + (node.has("message") ? node.get("message").asText() : state));
        }

        com.fasterxml.jackson.databind.JsonNode bodyNode = node.has("body") ? node.get("body") : null;
        com.fasterxml.jackson.databind.JsonNode results = null;
        if (bodyNode != null && bodyNode.has("results")) {
            results = bodyNode.get("results");
        } else {
            results = node.get("results");
        }

        StringBuilder out = new StringBuilder();
        if (results != null && results.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode r : results) {
                String type = r.has("type") ? r.get("type").asText() : "";
                if (r.isTextual()) {
                    out.append(r.asText());
                } else if (r.has("output")) {
                    out.append(r.get("output").asText());
                } else if ("status".equals(type)) {
                    continue;
                } else {
                    out.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
                }
                out.append("\n");
            }
        } else if (results != null && results.isTextual()) {
            out.append(results.asText());
        }
        return out.toString().trim();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private String executeViaTelnet(String agentId, String command) throws Exception {
        int telnetPort = resolveAgentTelnetPort(agentId);
        if (telnetPort <= 0) {
            return "无法连接到 agent: 未知 telnet 端口";
        }
        int timeout = props.getIdleSeconds() > 0 ? props.getIdleSeconds() : 25;

        CompletableFuture<String> future = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", telnetPort)) {
                socket.setSoTimeout((timeout + 5) * 1000);
                OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                InputStreamReader in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
                StringBuilder buffer = new StringBuilder();
                char[] cbuf = new char[4096];

                long deadline = System.currentTimeMillis() + (timeout + 10) * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (in.ready()) {
                        int n = in.read(cbuf);
                        if (n < 0) break;
                        buffer.append(cbuf, 0, n);
                        if (PROMPT.matcher(clean(buffer.toString())).find()) break;
                    } else {
                        Thread.sleep(100);
                    }
                }

                out.write(command + "\r\n");
                out.flush();

                while (System.currentTimeMillis() < deadline) {
                    if (in.ready()) {
                        int n = in.read(cbuf);
                        if (n < 0) break;
                        buffer.append(cbuf, 0, n);
                        String cleaned = clean(buffer.toString());
                        if (cleaned.contains(command.trim())
                                && PROMPT.matcher(cleaned).find()) {
                            break;
                        }
                    } else {
                        Thread.sleep(100);
                    }
                }
                future.complete(clean(buffer.toString()));
            } catch (Exception e) {
                logger.warn("telnet execute failed for agent={}, cmd={}", agentId, command, e);
                future.complete("执行出错: " + e.getMessage());
            }
        }, "arthas-exec-" + agentId);
        t.setDaemon(true);
        t.start();
        return future.get(timeout + 15, TimeUnit.SECONDS);
    }

    private String executeViaTunnel(String agentId, String command) throws Exception {
        int timeout = props.getIdleSeconds() > 0 ? props.getIdleSeconds() : 25;
        String wsUrl = "ws://127.0.0.1:" + serverPort + "/ws?method=connectArthas&id=" + agentId;
        logger.info("connecting tunnel ws for agent={} url={}", agentId, wsUrl);

        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<Void> tunnelReady = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws;
        try {
            ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        private boolean initialDone = false;

                        @Override
                        public void onOpen(WebSocket ws) {
                            logger.info("tunnel ws opened for agent={}", agentId);
                            ws.request(Long.MAX_VALUE);
                        }

                        private void appendData(WebSocket ws, String text) {
                            if (!initialDone) {
                                output.append(text);
                                String cleaned = clean(output.toString());
                                if (PROMPT.matcher(cleaned).find()) {
                                    initialDone = true;
                                    logger.info("tunnel initial prompt received, sending command immediately: {}", command);
                                    try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    ws.sendText(command + "\n", true);
                                    logger.info("command sent for agent={}", agentId);
                                    tunnelReady.complete(null);
                                    output.setLength(0);
                                }
                            } else {
                                output.append(text);
                                String cleaned = clean(output.toString());
                                logger.debug("tunnel partial output len={} for agent={}", cleaned.length(), agentId);
                                if (PROMPT.matcher(cleaned).find()) {
                                    result.complete(cleaned);
                                    logger.info("tunnel command output complete for agent={}", agentId);
                                }
                            }
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            String text = data.toString();
                            logger.info("tunnel ws text for agent={}: len={} data=[{}]",
                                    agentId, text.length(),
                                    text.length() > 300 ? text.substring(0, 300) + "..." : text);
                            appendData(ws, text);
                            return WebSocket.Listener.super.onText(ws, data, last);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            logger.info("tunnel ws binary for agent={}: len={} data=[{}]",
                                    agentId, bytes.length,
                                    text.length() > 300 ? text.substring(0, 300) + "..." : text);
                            appendData(ws, text);
                            return WebSocket.Listener.super.onBinary(ws, data, last);
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            logger.warn("tunnel ws error for agent={}: {}", agentId, error.toString());
                            if (!tunnelReady.isDone()) {
                                tunnelReady.completeExceptionally(error);
                            } else if (!result.isDone()) {
                                result.complete("执行出错: " + error.getMessage());
                            }
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            logger.info("tunnel ws closed for agent={}: status={} reason={}", agentId, statusCode, reason);
                            if (!result.isDone()) {
                                String partial = clean(output.toString());
                                if (tunnelReady.isDone() && initialDone && !partial.isEmpty()) {
                                    // 命令已发送，tunnel 提前关闭但有部分输出
                                    logger.info("tunnel closed early, returning partial output for agent={}", agentId);
                                    result.complete(partial);
                                } else if (!partial.isEmpty()) {
                                    result.complete(partial);
                                } else {
                                    result.completeExceptionally(
                                            new RuntimeException("connection closed before command completed: " + reason));
                                }
                            }
                            if (!tunnelReady.isDone()) {
                                tunnelReady.completeExceptionally(
                                        new RuntimeException("tunnel closed before ready: " + reason));
                            }
                            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("tunnel ws connect failed for agent={}: {}", agentId, e.toString());
            throw e;
        }

        try {
            tunnelReady.get(timeout + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("tunnel ready timeout for agent={}: {}", agentId, e.toString());
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, ""); } catch (Exception ex) {}
            throw e;
        }

        try {
            String r = result.get(timeout + 10, TimeUnit.SECONDS);
            return r != null ? r : "";
        } catch (Exception e) {
            // 超时但有部分输出时，返回部分输出而不是抛异常
            String partial = clean(output.toString());
            if (!partial.isEmpty() && e instanceof java.util.concurrent.TimeoutException) {
                logger.warn("tunnel result timeout but has partial output for agent={}, returning partial", agentId);
                return partial;
            }
            logger.warn("tunnel result failed for agent={}: {}", agentId, e.toString());
            throw e;
        } finally {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, ""); } catch (Exception e) {}
        }
    }

    private int resolveAgentTelnetPort(String agentId) {
        String portStr = System.getProperty("arthas.agent." + agentId + ".telnetPort");
        if (portStr != null) {
            try { return Integer.parseInt(portStr); } catch (NumberFormatException e) {}
        }
        return props.getTelnetPort();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return ANSI.matcher(s).replaceAll("");
    }
}
