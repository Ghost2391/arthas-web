package com.example.arthasweb.server;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonInclude;

@Entity
@Table(name = "servers")
public class ServerConfig {

    @Id
    @Column(length = 40)
    private String id;

    @Column(length = 100)
    private String name;

    @Column(length = 100)
    private String ip;

    @Column(name = "agent_id", length = 50, unique = true)
    private String agentId;

    @Column(length = 500)
    private String note;

    @Column(name = "demo_port")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer demoPort;

    @Column(name = "created_at")
    private Instant createdAt;

    public ServerConfig() {
    }

    public ServerConfig(String id, String name, String ip, String agentId, String note) {
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.agentId = agentId;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Integer getDemoPort() {
        return demoPort;
    }

    public void setDemoPort(Integer demoPort) {
        this.demoPort = demoPort;
    }

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
