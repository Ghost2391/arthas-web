#!/usr/bin/env bash
#
# download-arthas-offline.sh
# 在有网络的机器上执行，预下载 Arthas 完整发行包，供离线环境使用。
#
# Usage:
#   ./docker/download-arthas-offline.sh [version]
#
#   version  指定 Arthas 版本，默认自动从 aliyun 查询最新版。
#
# 执行后将 arthas-packaging-<version>-bin.zip 下载到
#   src/main/resources/static/arthas/
# 目录下，打包进 arthas-web JAR 后即可作为离线 mirror 源。
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$SCRIPT_DIR/src/main/resources/static/arthas"

# 1) 确定版本
LATEST_VERSION="${1:-}"
if [ -z "$LATEST_VERSION" ]; then
  echo ">> querying latest arthas version from aliyun..."
  LATEST_VERSION="$(curl -sL https://arthas.aliyun.com/api/latest_version | head -1 | tr -d '[:space:]')"
  if [ -z "$LATEST_VERSION" ]; then
    echo "ERROR: can not get latest version from aliyun, please specify version manually."
    echo "  Usage: $0 <version>"
    echo "  Example: $0 4.3.1"
    exit 1
  fi
fi

echo ">> arthas version: $LATEST_VERSION"

# 2) 下载完整发行包
ZIP_NAME="arthas-packaging-${LATEST_VERSION}-bin.zip"
ZIP_URL="https://arthas.aliyun.com/download/${LATEST_VERSION}?mirror=aliyun"

mkdir -p "$DEST_DIR"

if [ -f "$DEST_DIR/$ZIP_NAME" ]; then
  echo ">> already exists: $DEST_DIR/$ZIP_NAME"
else
  echo ">> downloading $ZIP_URL ..."
  curl -L -o "$DEST_DIR/$ZIP_NAME" "$ZIP_URL"
  echo ">> saved to $DEST_DIR/$ZIP_NAME"
fi

# 3) 写入 version 文件，供 ArthasMirrorController 使用
echo -n "$LATEST_VERSION" > "$DEST_DIR/version.txt"
echo ">> version written: $DEST_DIR/version.txt"

# 4) 同时确保 arthas-boot.jar 也在 static 下
BOOT_JAR="$SCRIPT_DIR/src/main/resources/static/arthas-boot.jar"
if [ ! -f "$BOOT_JAR" ]; then
  echo ">> downloading arthas-boot.jar ..."
  curl -L -o "$BOOT_JAR" "https://arthas.aliyun.com/arthas-boot.jar"
fi

echo ""
echo "========== 离线部署准备完成 =========="
echo "版本:     $LATEST_VERSION"
echo "发行包:   $DEST_DIR/$ZIP_NAME"
echo "arthas-boot.jar: $BOOT_JAR"
echo ""
echo "下一步:"
echo "  1) mvn clean package 打包 arthas-web"
echo "  2) 将 JAR 部署到离线服务器"
echo "  3) 在远程容器中执行生成的 attach 命令（使用 --arthas-mirror）"
echo "======================================"
