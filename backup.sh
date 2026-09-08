#!/bin/bash
# IoT控制App 一键备份脚本
# 用法: ./backup.sh "提交说明"

cd /workspace/IoTControlApp || exit 1

MSG="${1:-更新代码}"

git add -A
git commit -m "$MSG"
git push origin master

echo ""
echo "✅ 备份完成: $MSG"
echo "📦 仓库: https://github.com/htubuad/sjkz_app"
