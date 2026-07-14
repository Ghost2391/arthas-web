# Arthas 远程诊断平台 (arthas-web)

基于 **Alibaba Arthas** 的网页端，用于集中管理多台远程（Docker）服务器、以
**终端**和**大模型对话**两种方式对 Java 进程做诊断与分析。

```
浏览器  ──►  Spring Boot (本项目)  ──►  Arthas Tunnel Server (内嵌)
                                      │  ws 隧道
                                      ▼
                          远程容器里的 Arthas Agent  ──►  目标 JVM
```

核心特性：

- **多服务器管理**：录入多个服务器 IP / 主机，分配唯一 `agentId`，界面实时显示在线状态。
- **终端**：基于 xterm.js，直接通过内嵌 Tunnel 连接远程 Arthas，可手动敲任意 Arthas 命令。
- **对话式分析（LLM）**：用自然语言提问，后端调用大模型把问题转成 Arthas 命令并执行，
  再把真实输出交回大模型解读，给出中文分析与下一步建议。
- **零侵入远程接入**：远程只需运行官方 Arthas（无需任何自定义 agent），通过
  `--tunnel-server` **主动向外** 连接本平台的 Tunnel，因此可穿透 Docker NAT / 防火墙。

---


## 1. 启动本平台

需要 JDK 17 + Maven。

```bash
cd arthas-web
mvn spring-boot:run
# 或
mvn package && java -jar target/arthas-web-1.0.0.jar
```

默认端口 `8080`，浏览器打开 http://localhost:8080 。

### 配置大模型 (LLM)

编辑 `src/main/resources/application.yml` 的 `llm` 段，或用环境变量：

```yaml
llm:
  base-url: https://api.openai.com/v1      # OpenAI 兼容接口，如通义千问/DeepSeek/Ollama
  api-key: ${LLM_API_KEY:}
  model: gpt-4o-mini
  temperature: 0.2
  command-timeout: 25                       # 单条 arthas 命令最长等待秒数
```

```bash
LLM_API_KEY=sk-xxx mvn spring-boot:run
```

> 通义千问示例：`base-url: https://dashscope.aliyun.com/compatible-mode/v1`，`model: qwen-plus`。
> Ollama 示例：`base-url: http://localhost:11434/v1`，`model: qwen2.5`。

### 配置对外可达地址（远程容器需要能访问到本平台）

如果平台运行在 `203.0.113.10:8080`，而容器内通过 `ws://203.0.113.10:8080/ws` 访问，
设置：

```yaml
arthas:
  tunnel-path: /ws
  public-host: 203.0.113.10     # 留空则自动取请求 Host
  password: your-secret-password  # MCP 认证密码，留空则禁用认证
```

### MCP 身份验证

如果设置了 `arthas.password`，所有 MCP 客户端请求必须提供正确的 Bearer Token：

**服务器端配置** (`application.yml`)：
```yaml
arthas:
  password: your-secret-password
```

**MCP 客户端配置** (JSON)：
```json
{
  "mcpServers": {
    "arthas": {
      "url": "http://localhost:8080/mcp",
      "http_headers": {
        "Authorization": "Bearer your-secret-password"
      }
    }
  }
}
```

**注意事项**：
- Header 字段名必须是 `http_headers`，而不是 `headers`
- Token 格式必须是 `Bearer <password>`，密码需与服务器端完全一致
- 密码为空时禁用认证（向后兼容），设置密码后所有请求必须认证

---

## 2. 在远程 Docker 容器中接入 Arthas

在「连接信息」标签页复制对应服务器的 attach 命令，或直接使用脚本：

```bash
# 在目标容器内执行（或具有相同 network/pid 的 sidecar）
./docker/arthas-attach.sh <AGENT_ID> ws://<平台HOST>:8080/ws <JAVA_PID>
```

- `<AGENT_ID>`：平台里该服务器的 agentId（界面可复制）。
- `<JAVA_PID>`：目标 Java 进程 PID，单进程容器通常是 `1`，也可用 `jps -l` 查看。

接入成功后，平台左侧该服务器状态会变为**在线（绿点）**，即可使用终端 / 对话。

### Docker 部署建议

- **同容器 exec**：`docker exec -it <container> /bin/sh` 后运行上面的脚本。
- **Sidecar 容器**：新建一个共享目标容器 `network` 与 `pid` 的容器来跑 Arthas，
  这样无需改动业务镜像。

---

## 3. 使用

1. 左侧「+ 添加」录入服务器（名称 / IP / agentId）。
2. 在远程容器执行 attach 命令，等待状态变绿。
3. **终端**标签页：像本地 Arthas 一样操作（`dashboard`、`thread -n 3`、`trace` …）。
4. **对话分析**标签页：用中文提问，例如：

   > 这个服务 CPU 为什么这么高？
   > 最近哪些接口最慢？帮我 trace 一下下单接口。
   > 堆内存一直涨，是不是有泄漏？

   后端会：大模型规划命令 → 执行 → 回传真实输出 → 大模型解读，全程流式展示。

---

## 4. API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/servers` | 服务器列表（含在线状态） |
| POST | `/api/servers` | 新增 |
| PUT | `/api/servers/{id}` | 修改 |
| DELETE | `/api/servers/{id}` | 删除 |
| GET | `/api/servers/{id}/attach-command` | 生成远程 attach 命令 |
| WS | `/ws?method=connectArthas&id={agentId}` | Arthas 隧道（终端用） |
| WS | `/ws/chat/{serverId}` | 对话分析 |

---

## 5. 说明 / 限制

- 服务器配置默认保存在**内存**中（重启丢失）；如需持久化可替换为数据库。
- 对话上下文按 `serverId` 保存在内存（最多 40 条），重连网页会保留。
- 命令输出的结束判定基于 Arthas 提示符，极端情况下以 `command-timeout` 兜底。
- 大模型仅被允许执行只读/诊断类命令，破坏性命令请在终端中由人工显式执行。

---

## 6. 离线部署（目标机器无互联网访问）

当远程容器无法访问互联网时，可利用本平台作为 Arthas 的离线镜像源。

### 6.1 准备离线发行包

在有网络的机器上执行下载脚本：

```bash
# 下载最新版（会自动查询 aliyun 上的最新版本）
./docker/download-arthas-offline.sh

# 或指定版本
./docker/download-arthas-offline.sh 4.3.1
```

脚本会将：
- `arthas-packaging-<version>-bin.zip` 下载到 `src/main/resources/static/arthas/`
- `arthas-boot.jar` 下载到 `src/main/resources/static/`
- 版本号写入 `src/main/resources/static/arthas/version.txt`

这些文件会随 `mvn package` 打包进 JAR。

### 6.2 打包部署

```bash
mvn clean package
java -jar target/arthas-web-1.0.0.jar
```

### 6.3 远程容器接入

在平台 UI 中复制连接命令，会自动包含 `--repo-mirror` 参数指向本平台：

```bash
# 1) 从本服务下载 arthas-boot.jar
curl -o arthas-boot.jar http://<平台IP>:8080/arthas-boot.jar

# 2) Attach（arthas-boot.jar 会从本服务下载完整发行包）
java -jar arthas-boot.jar \
  --repo-mirror http://<平台IP>:8080/arthas \
  --tunnel-server ws://<平台IP>:8080/ws \
  --agent-id <AGENT_ID> \
  --attach-only <PID> \
  --telnet-port 3658 --http-port 8563
```

### 6.4 目标机器也无法访问本平台（完全物理隔离）

如果目标机器也无法访问 arthas-web 服务器，则需手动拷贝发行包：

```bash
# 在有网络的机器上
./docker/download-arthas-offline.sh

# 解压发行包到目标机器的 arthas 本地路径
# 将 arthas-packaging-<version>-bin.zip 传输到目标机器后：
mkdir -p ~/.arthas/lib/<version>/arthas/
unzip arthas-packaging-<version>-bin.zip -d ~/.arthas/lib/<version>/arthas/
# 确认 as.sh 存在
ls -la ~/.arthas/bin/as.sh
# 如果没有，将解压后的 as.sh 链接或复制到 ~/.arthas/bin/

# 在目标机器上执行 attach
java -jar arthas-boot.jar \
  --tunnel-server ws://<平台IP>:8080/ws \
  --agent-id <AGENT_ID> \
  --attach-only <PID> \
  --telnet-port 3658 --http-port 8563
```

### 6.5 docker/arthas-attach.sh 离线使用

环境变量指定离线源：

```bash
ARTHAS_BOOT_URL=http://<平台IP>:8080/arthas-boot.jar \
ARTHAS_REPO_MIRROR=http://<平台IP>:8080/arthas \
./docker/arthas-attach.sh <AGENT_ID> ws://<平台IP>:8080/ws <PID>
```
