#!/bin/bash
# 一次性目录整理：把 vivo root 战线 / 番茄模块 / 抖音逆向 分开
set -euo pipefail
cd "$(dirname "$0")/../.."

mkdir -p root modules douyin

# --- vivo root 战线 ---
mv FINDINGS.md ROUTES.md README.md root/
mv pd2545-64560 xf6-exploit fdgraph probes upstream-analysis logs root/
mv tools root/tools
rm -rf root/tools/__pycache__

# --- 抖音逆向（与 root 无关的那批脚本/产物）---
mkdir -p douyin/tools douyin/apk
mv root/tools/dy_dex_strings.py root/tools/dy_rules_fingerprint.py douyin/tools/
mv douyin-41-2-0.apk Douyin-Unified-41.2-modern-slim.apk douyin/apk/
mv tmp_dy douyin/work
rmdir tmp_inspect 2>/dev/null || mv tmp_inspect douyin/inspect

# --- 番茄折叠屏模块 ---
mv fq-fold-pad modules/fq-fold-pad

echo "move done"
