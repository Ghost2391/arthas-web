package com.example.arthasweb.llm;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
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
        // 优先使用 tunnel 方式（适用于远程和本地）
        if (tunnelHandler != null && tunnelHandler.isAgentOnline(agentId)) {
            try {
                return executeViaTunnel(agentId, command);
            } catch (Exception e) {
                logger.warn("tunnel execute failed for agent={}: {}", agentId, e.toString());
                // 对于远程场景，tunnel 失败后不应回退到 telnet（telnet 只能连本机）
                // 只有当明确是本地 agent 时才尝试 telnet
                String isLocal = System.getProperty("arthas.agent." + agentId + ".local");
                if ("true".equals(isLocal)) {
                    return executeViaTelnet(agentId, command);
                }
                throw e;
            }
        }
        // 如果没有 tunnel handler 或 agent 不在线，尝试 telnet（仅适用于本地）
        return executeViaTelnet(agentId, command);
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
                                    ws.sendText(command + "\r\n", true);
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
                            logger.debug("tunnel ws text for agent={}: {}",
                                    agentId, text.length() > 200 ? text.substring(0, 200) + "..." : text);
                            appendData(ws, text);
                            return WebSocket.Listener.super.onText(ws, data, last);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            logger.debug("tunnel ws binary for agent={}: {}",
                                    agentId, text.length() > 200 ? text.substring(0, 200) + "..." : text);
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
