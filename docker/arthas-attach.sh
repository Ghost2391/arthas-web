#!/usr/bin/env bash
#
# arthas-attach.sh - Attach Arthas to a JVM inside a Docker container and
# register it to the Arthas-Web tunnel server.
#
# Usage:
#   ./arthas-attach.sh <AGENT_ID> <TUNNEL_URL> [JAVA_PID]
#
#   <AGENT_ID>    must match the agentId shown in the Arthas-Web UI
#   <TUNNEL_URL>  e.g. ws://arthas-web-host:8080/ws
#   JAVA_PID      target java process id (default: 1)
#
# Environment:
#   ARTHAS_BOOT_URL  自定义 arthas-boot.jar 下载地址，可用于离线环境
#                    默认从 aliyun 下载，离线时设为 http://arthas-web-host:8080/arthas-boot.jar
#   ARTHAS_REPO_MIRROR  自定义 repo mirror 地址，离线时设为 http://arthas-web-host:8080/arthas
#
# Example:
#   ./arthas-attach.sh arthas-a1b2c3 ws://203.0.113.10:8080/ws 1
#
#   # 离线环境：
#   ARTHAS_BOOT_URL=http://203.0.113.10:8080/arthas-boot.jar \
#   ARTHAS_REPO_MIRROR=http://203.0.113.10:8080/arthas \
#   ./arthas-attach.sh arthas-a1b2c3 ws://203.0.113.10:8080/ws 1
#
set -e

AGENT_ID="${1:?agentId required}"
TUNNEL="${2:?tunnel url required}"
PID="${3:-1}"

# ---- 本地安装 Arthas（如已存在则跳过）----
AS_SCRIPT="$HOME/.arthas/bin/as.sh"

install_arthas() {
  echo ">> installing arthas (if needed) ..."

  # 使用环境变量中的 boot jar 下载地址，默认从 aliyun
  BOOT_URL="${ARTHAS_BOOT_URL:-https://arthas.aliyun.com/arthas-boot.jar}"
  REPO_MIRROR="${ARTHAS_REPO_MIRROR:-}"

  BOOT_JAR="/tmp/arthas-boot-$$.jar"

  echo ">> downloading arthas-boot.jar from $BOOT_URL ..."
  curl -sL -o "$BOOT_JAR" "$BOOT_URL"

  ARGS=""
  if [ -n "$REPO_MIRROR" ]; then
    ARGS="$ARGS --repo-mirror $REPO_MIRROR"
    echo ">> using repo mirror: $REPO_MIRROR"
  fi

  echo ">> extracting arthas via arthas-boot.jar ..."
  java -jar "$BOOT_JAR" $ARGS --help > /dev/null 2>&1 || true

  rm -f "$BOOT_JAR"
}

if [ ! -x "$AS_SCRIPT" ]; then
  install_arthas
else
  echo ">> arthas already installed at $AS_SCRIPT"
fi

# ---- Attach ----
echo ">> attaching arthas to pid=$PID with agentId=$AGENT_ID tunnel=$TUNNEL"
exec "$AS_SCRIPT" \
  --tunnel-server "$TUNNEL" \
  --agent-id "$AGENT_ID" \
  -p "$PID" \
  --telnet-port 3658 \
  --http-port 8563
