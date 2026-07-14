package com.example.arthasweb.server;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.arthasweb.config.ArthasProperties;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final ArthasProperties arthasProperties;

    public ServerController(ServerService serverService, ArthasProperties arthasProperties) {
        this.serverService = serverService;
        this.arthasProperties = arthasProperties;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(serverService.list().stream().map(this::toView).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        ServerConfig cfg = serverService.get(id);
        if (cfg == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(cfg));
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody ServerConfig cfg) {
        ServerConfig saved = serverService.save(cfg);
        return ResponseEntity.ok(toView(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ServerConfig cfg) {
        ServerConfig updated = serverService.update(id, cfg);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = serverService.delete(id);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /** Returns the command to attach arthas to a remote container. */
    @GetMapping("/{id}/attach-command")
    public ResponseEntity<?> attachCommand(@PathVariable String id, HttpServletRequest request,
            @RequestParam(required = false) String pid) {
        ServerConfig cfg = serverService.get(id);
        if (cfg == null) {
            return ResponseEntity.notFound().build();
        }
        String host = arthasProperties.getPublicHost();
        if (host == null || host.isEmpty()) {
            host = request.getServerName();
        }
        int port = request.getServerPort();
        String baseUrl = "http://" + host + ":" + port;
        String tunnelUrl = "ws://" + host + ":" + port + arthasProperties.getTunnelPath();
        String bootUrl = baseUrl + "/arthas-boot.jar";
        String mirrorUrl = baseUrl + "/arthas";
        String distUrl = baseUrl + "/arthas/download/" + arthasProperties.getVersion();
        String pidVal = (pid != null && !pid.isBlank()) ? pid : "<PID>";

        StringBuilder cmd = new StringBuilder();
        cmd.append("# === 离线部署方式（从本服务下载 arthas-boot.jar + 完整发行包）===\n");
        cmd.append("AS_SCRIPT=\"$HOME/.arthas/bin/as.sh\"\n");
        cmd.append("if [ ! -f \"$AS_SCRIPT\" ]; then\n");
        cmd.append("  curl -sL -o arthas-boot.jar ").append(bootUrl).append("\n");
        cmd.append("  VERSION=$(curl -sL ").append(mirrorUrl).append("/api/latest_version)\n");
        cmd.append("  curl -sL -o /tmp/arthas-packaging-$VERSION-bin.zip ").append(distUrl).append("\n");
        cmd.append("  mkdir -p $HOME/.arthas/lib/$VERSION/arthas\n");
        cmd.append("  unzip -o /tmp/arthas-packaging-$VERSION-bin.zip -d $HOME/.arthas/lib/$VERSION/arthas/\n");
        cmd.append("  chmod +x $HOME/.arthas/bin/as.sh 2>/dev/null || true\n");
        cmd.append("else\n");
        cmd.append("  echo \"arthas already installed at $AS_SCRIPT\"\n");
        cmd.append("fi\n");
        cmd.append("java -jar arthas-boot.jar")
                .append(" --tunnel-server ").append(tunnelUrl)
                .append(" --agent-id ").append(cfg.getAgentId())
                .append(" --attach-only ").append(pidVal)
                .append(" --telnet-port 3658 --http-port 8563\n");
        cmd.append("\n");
        cmd.append("# === 单行版 ===\n");
        cmd.append("AS_SCRIPT=\"$HOME/.arthas/bin/as.sh\"; ");
        cmd.append("if [ ! -f \"$AS_SCRIPT\" ]; then ");
        cmd.append("curl -sL -o arthas-boot.jar ").append(bootUrl).append(" && ");
        cmd.append("VERSION=$(curl -sL ").append(mirrorUrl).append("/api/latest_version) && ");
        cmd.append("curl -sL -o /tmp/arthas-packaging-$VERSION-bin.zip ").append(distUrl).append(" && ");
        cmd.append("mkdir -p $HOME/.arthas/lib/$VERSION/arthas && ");
        cmd.append("unzip -o /tmp/arthas-packaging-$VERSION-bin.zip -d $HOME/.arthas/lib/$VERSION/arthas/; ");
        cmd.append("fi; ");
        cmd.append("java -jar arthas-boot.jar --tunnel-server ").append(tunnelUrl)
                .append(" --agent-id ").append(cfg.getAgentId())
                .append(" --attach-only ").append(pidVal)
                .append(" --telnet-port 3658 --http-port 8563\n");
        cmd.append("\n");
        cmd.append("# === Windows PowerShell 方式 ===\n");
        cmd.append("$as = \"$env:USERPROFILE\\.arthas\\bin\\as.sh\"; ");
        cmd.append("if (-not (Test-Path $as)) { ");
        cmd.append("Invoke-WebRequest -Uri ").append(bootUrl).append(" -OutFile arthas-boot.jar; ");
        cmd.append("$v = (Invoke-RestMethod ").append(mirrorUrl).append("/api/latest_version); ");
        cmd.append("$zip = \"$env:TEMP\\arthas-packaging-$v-bin.zip\"; ");
        cmd.append("Invoke-WebRequest -Uri ").append(mirrorUrl).append("/download/$v -OutFile $zip; ");
        cmd.append("$dir = \"$env:USERPROFILE\\.arthas\\lib\\$v\\arthas\"; ");
        cmd.append("New-Item -ItemType Directory -Path $dir -Force; ");
        cmd.append("Expand-Archive -Path $zip -DestinationPath $dir -Force; ");
        cmd.append("}; ");
        cmd.append("java -jar arthas-boot.jar --tunnel-server ").append(tunnelUrl)
                .append(" --agent-id ").append(cfg.getAgentId())
                .append(" --attach-only ").append(pidVal)
                .append(" --telnet-port 3658 --http-port 8563\n");

        String full = cmd.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", cfg.getAgentId());
        result.put("tunnelUrl", tunnelUrl);
        result.put("command", full);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toView(ServerConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cfg.getId());
        m.put("name", cfg.getName());
        m.put("ip", cfg.getIp());
        m.put("demoPort", cfg.getDemoPort());
        m.put("agentId", cfg.getAgentId());
        m.put("note", cfg.getNote());
        m.put("createdAt", cfg.getCreatedAt());
        m.put("online", serverService.isOnline(cfg.getAgentId()));
        return m;
    }
}
