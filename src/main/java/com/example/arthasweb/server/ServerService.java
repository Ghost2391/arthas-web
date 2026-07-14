package com.example.arthasweb.server;

import java.security.SecureRandom;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.arthasweb.tunnel.TunnelWebSocketHandler;

@Service
public class ServerService {

    private final ServerRepository repo;
    private final TunnelWebSocketHandler tunnelWebSocketHandler;
    private final SecureRandom random = new SecureRandom();

    public ServerService(ServerRepository repo, TunnelWebSocketHandler tunnelWebSocketHandler) {
        this.repo = repo;
        this.tunnelWebSocketHandler = tunnelWebSocketHandler;
    }

    public List<ServerConfig> list() {
        return repo.findAll();
    }

    public ServerConfig get(String id) {
        return repo.findById(id).orElse(null);
    }

    @Transactional
    public ServerConfig save(ServerConfig cfg) {
        if (cfg.getAgentId() == null || cfg.getAgentId().isEmpty()) {
            cfg.setAgentId("arthas-" + randomId(10));
        }
        return repo.save(cfg);
    }

    @Transactional
    public ServerConfig update(String id, ServerConfig cfg) {
        ServerConfig existing = repo.findById(id).orElse(null);
        if (existing == null) {
            return null;
        }
        if (cfg.getName() != null) existing.setName(cfg.getName());
        if (cfg.getIp() != null) existing.setIp(cfg.getIp());
        if (cfg.getAgentId() != null && !cfg.getAgentId().isEmpty()) existing.setAgentId(cfg.getAgentId());
        if (cfg.getNote() != null) existing.setNote(cfg.getNote());
        if (cfg.getDemoPort() != null) existing.setDemoPort(cfg.getDemoPort());
        return repo.save(existing);
    }

    @Transactional
    public boolean delete(String id) {
        if (repo.existsById(id)) {
            repo.deleteById(id);
            return true;
        }
        return false;
    }

    public boolean isOnline(String agentId) {
        return tunnelWebSocketHandler.isAgentOnline(agentId);
    }

    private String randomId(int len) {
        final String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
