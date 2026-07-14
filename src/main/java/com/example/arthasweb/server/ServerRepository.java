package com.example.arthasweb.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerRepository extends JpaRepository<ServerConfig, String> {

    ServerConfig findByAgentId(String agentId);
}
