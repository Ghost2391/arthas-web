# Arthas 远程诊断平台 (arthas-web)

基于 **Alibaba Arthas** 的集中式远程诊断 Web 平台，通过**终端隧道**与**大模型对话**两种方式对多台远程服务器上的 Java 进程进行实时诊断分析。

## 架构

```
┌──────────┐    ┌────────────────────────────────────────────────┐
│ Browser  │    │           Spring Boot (arthas-web)              │
│ (UI)     │◄──►├────── Controller ──► ArthasCommandExecutor ──► │
└──────────┘    │         │                      │               │
                │    ┌─────┴──────┐      ┌────────┴────────┐     │
                │    │ LLM Service│      │ Tunnel WebSocket │     │
                │    │ (对话分析)  │      │ (终端隧道)       │     │
                │    └─────┬──────┘      └────────┬────────┘     │
                └──────────┼──────────────────────┼──────────────┘
                           │                      │
                    ┌──────▼──────────────────────▼──────┐
                    │       远程服务器 (Docker/物理机)      │
                    │                                     │
                    │  ┌─────────┐    ┌────────────────┐  │
                    │  │TcpProxy │───►│Arthas HTTP API │  │
                    │  │:8564    │    │:8563           │  │
                    │  └─────────┘    └────────┬───────┘  │
                    │                          │           │
                    │              ┌───────────▼────────┐  │
                    │              │  Arthas Agent      │  │
                    │              │  (attach → JVM)    │  │
                    │              └────────────────────┘  │
                    └─────────────────────────────────────┘
```

### 通信方式

| 模式 | 协议 | 端口 | 说明 |
|------|------|------|------|
| **Tunnel 隧道** | WebSocket → Tunnel Server | 8080 | 终端实时交互（xterm.js），命令经 tunnel 转发到远程 agent |
| **HTTP API** | HTTP POST → `/api` | 8564 (TcpProxy) | 快捷工具 / LLM 执行命令，通过远程 HTTP API 直接调用，**不依赖 tunnel** |
| **TcpProxy** | Java TCP 转发 | 8564 → 127.0.0.1:8563 | 解决 Arthas HTTP Server 只绑定 127.0.0.1 的问题，attach 时自动部署 |

## 核心特性

### 🔧 零侵入远程接入
- 远程容器只需运行标准 Arthas，通过 `--tunnel-server` 主动向外连接本平台
- 穿透 Docker NAT / 防火墙，无需开放入站端口
- 支持 Linux / Windows PowerShell 一键 attach 命令

### 🖥️ 终端模式
- 基于 WebSocket 隧道直连远程 Arthas，支持所有原生 Arthas 命令
- 实时流式输出，与本地终端体验一致

### 🤖 大模型对话分析
- 自然语言提问 → LLM 规划 Arthas 命令 → 自动执行 → 结果回传 LLM 解读
- 支持多轮上下文，LLM 可基于之前的结果提出下一步建议
- 适配 OpenAI 兼容接口（通义千问、DeepSeek、Ollama 等）

### 🔥 火焰图
- 一键采集 30s CPU profile，生成交互式火焰图
- 支持通过远程 HTTP 直连下载原始 HTML 文件

### 📦 离线部署
- 内置 Arthas 完整发行包镜像源
- 目标机器无需互联网访问，全部依赖从本平台下载
- TcpProxy.class 随 JAR 发布，attach 时自动部署

### 🎨 双主题 UI
- 明/暗色主题切换，代码块与结果输出区适配双色

## 快速开始

需要 JDK 17 + Maven。

```bash
git clone https://github.com/Ghost2391/arthas-web.git
cd arthas-web
mvn spring-boot:run
```

浏览器打开 http://localhost:8080。

### 配置 LLM

```yaml
llm:
  base-url: https://api.openai.com/v1
  api-key: ${LLM_API_KEY:}
  model: gpt-4o-mini
  temperature: 0.2
```

### 配置对外地址

```yaml
arthas:
  public-host: 203.0.113.10
  password: your-secret-password
```

详见 `application.yml`。

## 远程接入

在目标容器中执行平台生成的 attach 命令，等待服务器状态变绿后即可开始诊断。

## 离线部署

```bash
# 下载离线发行包
./docker/download-arthas-offline.sh

# 打包
mvn clean package
java -jar target/arthas-web-1.0.0.jar
```

详见 `application.yml` 中的离线部署章节。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/servers` | 服务器列表 |
| POST | `/api/servers` | 新增 |
| PUT | `/api/servers/{id}` | 修改 |
| DELETE | `/api/servers/{id}` | 删除 |
| GET | `/api/servers/{id}/attach-command` | 生成 attach 命令 |
| GET | `/api/servers/{id}/download-file` | 下载远程文件 |
| POST | `/mcp` | MCP 协议工具调用 |
| WS | `/ws` | Arthas 隧道 |
| WS | `/ws/chat/{serverId}` | 对话分析 |

---

*作者: [deepseek](https://deepseek.com) + [opencode](https://opencode.ai)*
