package com.example.arthasweb.tunnel;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.arthasweb.config.ArthasProperties;

@Component
/**
 * An Arthas-compatible tunnel server.
 *
 * <p>Protocol (see alibaba/arthas tunnel-server):
 * <pre>
 *   agent  -> ws?method=agentRegister                (control connection)
 *   server -> response:/?method=agentRegister&id=XXX
 *   browser-> ws?method=connectArthas&id=XXX
 *   server -> (to agent) response:/?method=startTunnel&id=XXX&clientConnectionId=YYY
 *   agent  -> ws?method=openTunnel&clientConnectionId=YYY  (tunnel connection)
 *   server bridges browser(connectArthas) <-> agent(openTunnel)
 * </pre>
 */
public class TunnelWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TunnelWebSocketHandler.class);

    private final ArthasProperties props;
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingConnection> pendingConnections = new ConcurrentHashMap<>();

    public TunnelWebSocketHandler(ArthasProperties props) {
        this.props = props;
    }

    public boolean isAgentOnline(String agentId) {
        WebSocketSession s = agentSessions.get(agentId);
        return s != null && s.isOpen();
    }

    public boolean isAnyAgentOnline() {
        return agentSessions.values().stream().anyMatch(WebSocketSession::isOpen);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String uri = session.getUri() == null ? "" : session.getUri().toString();
        Map<String, List<String>> params = splitQuery(session.getUri() == null ? "" : session.getUri().getQuery());

        String method = first(params, "method");
        logger.info("tunnel handshake from {} method={}", session.getRemoteAddress(), method);

        if ("agentRegister".equals(method)) {
            handleAgentRegister(session, params);
        } else if ("connectArthas".equals(method)) {
            handleConnectArthas(session, params);
        } else if ("openTunnel".equals(method)) {
            handleOpenTunnel(session, params);
        } else {
            // arthas 4.x may send register via text frame instead of url param;
            // keep the connection open and wait for handleTextMessage.
            logger.info("no method in url, waiting for text frame from {}", session.getRemoteAddress());
        }
    }

    private void handleAgentRegister(WebSocketSession session, Map<String, List<String>> params) throws Exception {
        String id = first(params, "id");
        if (id == null || id.isEmpty()) {
            id = randomId(20);
        }
        agentSessions.put(id, session);
        session.getAttributes().put("agentId", id);
        
        // 标记 agent 是否为本地（通过检查远程地址是否为 localhost/127.0.0.1）
        boolean isLocal = isLocalAddress(session.getRemoteAddress());
        System.setProperty("arthas.agent." + id + ".local", String.valueOf(isLocal));
        logger.info("agent registered, id={}, local={}", id, isLocal);

        String response = "/?method=agentRegister&id=" + id;
        session.sendMessage(new TextMessage(response));
    }

    private boolean isLocalAddress(java.net.SocketAddress address) {
        if (address instanceof java.net.InetSocketAddress) {
            java.net.InetAddress inetAddress = ((java.net.InetSocketAddress) address).getAddress();
            return inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress() 
                || "127.0.0.1".equals(inetAddress.getHostAddress()) 
                || "0:0:0:0:0:0:0:1".equals(inetAddress.getHostAddress());
        }
        return false;
    }

    private void handleConnectArthas(WebSocketSession browserSession, Map<String, List<String>> params) throws Exception {
        String id = first(params, "id");
        if (id == null || id.isEmpty()) {
            browserSession.close(CloseStatus.NORMAL.withReason("agent id required"));
            return;
        }
        WebSocketSession agentSession = agentSessions.get(id);
        if (agentSession == null || !agentSession.isOpen()) {
            browserSession.close(CloseStatus.NORMAL.withReason("can not find arthas agent by id: " + id));
            return;
        }

        String clientConnectionId = randomId(20);
        CompletableFuture<WebSocketSession> future = new CompletableFuture<>();
        PendingConnection pending = new PendingConnection(browserSession, future);
        pendingConnections.put(clientConnectionId, pending);

        browserSession.getAttributes().put("clientConnectionId", clientConnectionId);

        String startTunnel = "/?method=startTunnel&id=" + id + "&clientConnectionId=" + clientConnectionId;
        agentSession.sendMessage(new TextMessage(startTunnel));
        logger.info("sent startTunnel to agent {}, clientConnectionId={}", id, clientConnectionId);

        future.orTimeout(20, TimeUnit.SECONDS)
                .thenAccept(agentTunnel -> link(browserSession, agentTunnel))
                .exceptionally(e -> {
                    logger.error("wait for agent open tunnel timeout, id={}", id, e);
                    try {
                        browserSession.close(CloseStatus.NORMAL.withReason("wait for agent tunnel timeout"));
                    } catch (Exception ex) { /* ignore */ }
                    pendingConnections.remove(clientConnectionId);
                    return null;
                });
    }

    private void handleOpenTunnel(WebSocketSession agentTunnelSession, Map<String, List<String>> params) {
        String clientConnectionId = first(params, "clientConnectionId");
        if (clientConnectionId == null) {
            logger.warn("openTunnel without clientConnectionId");
            return;
        }
        PendingConnection pending = pendingConnections.remove(clientConnectionId);
        if (pending == null) {
            logger.warn("no pending connection for clientConnectionId={}", clientConnectionId);
            return;
        }
        logger.info("agent opened tunnel, clientConnectionId={}", clientConnectionId);
        pending.future.complete(agentTunnelSession);
    }

    /** Bridge two sessions: forward every frame both ways and close together. */
    private void link(WebSocketSession a, WebSocketSession b) {
        a.getAttributes().put("peer", b);
        b.getAttributes().put("peer", a);
        a.getAttributes().put("linked", Boolean.TRUE);
        b.getAttributes().put("linked", Boolean.TRUE);
        logger.info("tunnel linked: browser={} <-> agent={}", a.getId(), b.getId());
    }

    private void forward(WebSocketSession from, org.springframework.web.socket.WebSocketMessage<?> message) throws Exception {
        WebSocketSession peer = (WebSocketSession) from.getAttributes().get("peer");
        logger.info("forward: from={} peer={} peerOpen={} type={} payloadLen={}",
                from.getId(),
                peer != null ? peer.getId() : null,
                peer != null && peer.isOpen(),
                message.getClass().getSimpleName(),
                message instanceof org.springframework.web.socket.TextMessage
                        ? ((org.springframework.web.socket.TextMessage) message).getPayload().length()
                        : message instanceof org.springframework.web.socket.BinaryMessage
                                ? ((org.springframework.web.socket.BinaryMessage) message).getPayloadLength()
                                : -1);
        if (peer != null && peer.isOpen()) {
            if (message instanceof org.springframework.web.socket.TextMessage) {
                String payload = ((org.springframework.web.socket.TextMessage) message).getPayload();
                peer.sendMessage(new org.springframework.web.socket.TextMessage(payload));
            } else if (message instanceof org.springframework.web.socket.BinaryMessage) {
                org.springframework.web.socket.BinaryMessage bin = (org.springframework.web.socket.BinaryMessage) message;
                java.nio.ByteBuffer payload = bin.getPayload();
                byte[] copy = new byte[payload.remaining()];
                payload.duplicate().get(copy);
                peer.sendMessage(new org.springframework.web.socket.BinaryMessage(copy));
            } else {
                peer.sendMessage(message);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        boolean linked = Boolean.TRUE.equals(session.getAttributes().get("linked"));
        logger.info("handleTextMessage: session={} linked={} payloadLen={} from={}",
                session.getId(), linked, message.getPayload().length(), session.getRemoteAddress());
        if (linked) {
            forward(session, message);
            return;
        }

        String payload = message.getPayload();
        String queryStr = extractQueryFromText(payload);
        if (queryStr != null) {
            Map<String, List<String>> params = splitQuery(queryStr);
            String method = first(params, "method");
            logger.info("tunnel text frame from {} method={}", session.getRemoteAddress(), method);

            if ("agentRegister".equals(method)) {
                handleAgentRegister(session, params);
            } else if ("openTunnel".equals(method)) {
                handleOpenTunnel(session, params);
            }
        }
    }

    private String extractQueryFromText(String payload) {
        String firstLine = payload.split("[\r\n]+")[0].trim();
        if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ")) {
            firstLine = firstLine.substring(firstLine.indexOf(' ')).trim();
        }
        int qIdx = firstLine.indexOf('?');
        if (qIdx < 0) return null;
        int spIdx = firstLine.indexOf(' ', qIdx);
        if (spIdx > qIdx) {
            return firstLine.substring(qIdx + 1, spIdx);
        }
        return firstLine.substring(qIdx + 1);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, org.springframework.web.socket.BinaryMessage message) {
        boolean linked = Boolean.TRUE.equals(session.getAttributes().get("linked"));
        logger.info("handleBinaryMessage: session={} linked={} payloadLen={} from={}",
                session.getId(), linked, message.getPayloadLength(), session.getRemoteAddress());
        if (linked) {
            try {
                forward(session, message);
            } catch (Exception e) {
                logger.warn("forward binary failed", e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String agentId = (String) session.getAttributes().get("agentId");
        if (agentId != null) {
            agentSessions.remove(agentId);
            logger.info("agent disconnected, id={}", agentId);
        }
        WebSocketSession peer = (WebSocketSession) session.getAttributes().get("peer");
        if (peer != null && peer.isOpen()) {
            peer.close(status);
        }
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> v = params.get(key);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static Map<String, List<String>> splitQuery(String query) {
        Map<String, List<String>> map = new ConcurrentHashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String k = idx > 0 ? pair.substring(0, idx) : pair;
            String val = idx > 0 && pair.length() > idx + 1 ? pair.substring(idx + 1) : "";
            map.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(val);
        }
        return map;
    }

    private static String randomId(int len) {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static class PendingConnection {
        final WebSocketSession browserSession;
        final CompletableFuture<WebSocketSession> future;

        PendingConnection(WebSocketSession browserSession, CompletableFuture<WebSocketSession> future) {
            this.browserSession = browserSession;
            this.future = future;
        }
    }
}
