package com.example.arthasweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "arthas")
public class ArthasProperties {

    private String tunnelPath = "/ws";
    private int idleSeconds = 30;
    /** Public host/IP the remote docker container uses to reach this tunnel server. Empty = derived from request. */
    private String publicHost = "";
    /** Default telnet port for agents on this machine, used by executor to run commands directly. */
    private int telnetPort = 3658;
    private String version = "";
    /** Password for MCP authentication. When set, clients must provide 'Authorization: Bearer <password>' header. */
    private String password = "";

    public String getVersion() {
        if (version != null && !version.isBlank()) {
            return version;
        }
        try {
            var resource = new org.springframework.core.io.ClassPathResource("static/arthas/version.txt");
            if (resource.exists()) {
                try (var is = resource.getInputStream()) {
                    return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTunnelPath() {
        return tunnelPath;
    }

    public void setTunnelPath(String tunnelPath) {
        this.tunnelPath = tunnelPath;
    }

    public int getIdleSeconds() {
        return idleSeconds;
    }

    public void setIdleSeconds(int idleSeconds) {
        this.idleSeconds = idleSeconds;
    }

    public String getPublicHost() {
        return publicHost;
    }

    public void setPublicHost(String publicHost) {
        this.publicHost = publicHost;
    }

    public int getTelnetPort() {
        return telnetPort;
    }

    public void setTelnetPort(int telnetPort) {
        this.telnetPort = telnetPort;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
