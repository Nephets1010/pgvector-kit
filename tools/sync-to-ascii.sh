#!/usr/bin/env bash
# 把中文路径下的仓库同步到纯 ASCII 副本，用于跑 Maven。
#
# ★ 为什么需要这个脚本：
#   Windows 上仓库路径含中文时，jacoco 的 -javaagent destfile 参数会被截断在第一个非 ASCII 字符处
#   （静默丢失覆盖率数据，report/check 因「无数据」假通过），maven-jar-plugin 也会报
#   "Error assembling JAR"。这两类报错都指不到真实原因。对策：始终在纯 ASCII 副本里构建。
#
# 用法：bash tools/sync-to-ascii.sh [源目录] [目标目录]
set -euo pipefail

SRC="${1:-D:/data/WorkBuddy/程序宇宙/repos/pgvector-kit}"
DST="${2:-/d/tmp/verify/pgvector-kit}"

# 先整个删掉副本：残留的旧文件会让构建「看起来通过」，比构建失败更危险
rm -rf "$DST"
mkdir -p "$DST"

# 排除 .git（副本只是编译用）与 target（增量产物不用搬）
tar -cf - -C "$SRC" --exclude=./.git --exclude=.git --exclude=target . | tar -xf - -C "$DST"

echo "已同步: $SRC -> $DST"
