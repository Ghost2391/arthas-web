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
    private static final Pattern PROMPT = Pattern.compile("\\[arthas@\\d+\\]\\$\\s*");
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

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            String text = data.toString();
                            logger.info("tunnel ws text[{}] for agent={}: [{}]", initialDone ? "data" : "banner", agentId,
                                    text.length() > 300 ? text.substring(0, 300) + "..." : text);

                            if (!initialDone) {
                                output.append(text);
                                String cleaned = clean(output.toString());
                                logger.info("tunnel cleaned output so far[{}]: [{}]", cleaned.length(),
                                        cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned);
                                if (PROMPT.matcher(cleaned).find()) {
                                    initialDone = true;
                                    tunnelReady.complete(null);
                                    output.setLength(0);
                                    logger.info("tunnel initial prompt matched for agent={}", agentId);
                                } else {
                                    logger.info("tunnel prompt not yet matched, continuing to wait");
                                }
                                return WebSocket.Listener.super.onText(ws, data, last);
                            }

                            output.append(text);
                            String cleaned = clean(output.toString());
                            logger.info("tunnel cleaned output: [{}]",
                                    cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned);
                            if (PROMPT.matcher(cleaned).find()) {
                                result.complete(cleaned);
                                logger.info("tunnel command result completed for agent={}", agentId);
                            }
                            return WebSocket.Listener.super.onText(ws, data, last);
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            logger.warn("tunnel ws error for agent={}: {} resultDone={} tunnelReadyDone={}",
                                    agentId, error.toString(), result.isDone(), tunnelReady.isDone());
                            if (!result.isDone()) {
                                result.complete("执行出错: " + error.getMessage());
                            }
                            if (!tunnelReady.isDone()) {
                                tunnelReady.completeExceptionally(error);
                            }
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            logger.info("tunnel ws closed for agent={}: status={} reason={}", agentId, statusCode, reason);
                            logger.info("tunnel ws close: resultDone={} tunnelReadyDone={} outputLen={} initialDone={}",
                                    result.isDone(), tunnelReady.isDone(), output.length(), initialDone);
                            if (!result.isDone()) {
                                String finalOutput = clean(output.toString());
                                logger.info("tunnel ws close: completing result with [{}]",
                                        finalOutput.length() > 200 ? finalOutput.substring(0, 200) + "..." : finalOutput);
                                result.complete(finalOutput);
                            } else {
                                logger.info("tunnel ws close: result already done, value=[{}]", result.getNow("N/A"));
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
            logger.info("waiting for tunnel ready, timeout={}s", timeout + 5);
            tunnelReady.get(timeout + 5, TimeUnit.SECONDS);
            logger.info("tunnel ready complete");
        } catch (Exception e) {
            logger.warn("tunnel ready timeout for agent={}: {}", agentId, e.toString());
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, ""); } catch (Exception ex) {}
            throw e;
        }

        logger.info("sending command via tunnel: {}", command);
        try {
            ws.sendText(command + "\r\n", true).get(5, TimeUnit.SECONDS);
            logger.info("command sent successfully");
        } catch (Exception e) {
            logger.warn("command send failed for agent={}: {}", agentId, e.toString());
            throw e;
        }
        logger.info("waiting for result, timeout={}s", timeout + 10);

        try {
            String r = result.get(timeout + 10, TimeUnit.SECONDS);
            logger.info("tunnel result received: len={}", r != null ? r.length() : -1);
            return r != null ? r : "";
        } catch (Exception e) {
            logger.warn("tunnel result failed for agent={}: {}", agentId, e.toString());
            throw e;
        } finally {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception e) {}
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
