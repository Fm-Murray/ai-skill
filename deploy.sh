#!/bin/bash
# ============================================================
# AI Skill 编排系统 — Docker 一键部署脚本
# 用法：
#   ./deploy.sh          # 构建并启动
#   ./deploy.sh start    # 启动（已构建）
#   ./deploy.sh stop     # 停止
#   ./deploy.sh restart  # 重启
#   ./deploy.sh logs     # 查看日志
#   ./deploy.sh down     # 停止并删除容器
#   ./deploy.sh clean    # 停止、删除容器和镜像
# ============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${GREEN}[Deploy]${NC} $1"; }
warn() { echo -e "${YELLOW}[Warn]${NC} $1"; }
err() { echo -e "${RED}[Error]${NC} $1"; }
info() { echo -e "${CYAN}[Info]${NC} $1"; }

# 检查 Docker 环境
check_env() {
    if ! command -v docker &> /dev/null; then
        err "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    if ! docker info &> /dev/null; then
        err "Docker 未运行，请先启动 Docker"
        exit 1
    fi
    if ! docker compose version &> /dev/null; then
        err "Docker Compose 未安装，请安装 Docker Compose V2"
        exit 1
    fi
    log "Docker 环境检查通过"
}

# 检查 .env 文件
check_env_file() {
    if [ ! -f .env ]; then
        warn "未找到 .env 文件，正在从 .env.example 创建..."
        cp .env.example .env
        warn "请编辑 .env 文件填入 AI_API_KEY 后重新运行"
        info "  vim .env  # 填入你的 API Key"
        exit 1
    fi

    # 检查 API Key 是否已配置
    if grep -q "your-api-key-here" .env 2>/dev/null; then
        err "请在 .env 文件中填入真实的 AI_API_KEY"
        info "  vim .env"
        exit 1
    fi
    log "环境变量配置检查通过"
}

# 构建并启动
cmd_build() {
    check_env
    check_env_file
    log "开始构建 Docker 镜像..."
    docker compose build
    log "启动服务..."
    docker compose up -d
    show_status
}

# 启动（已构建）
cmd_start() {
    check_env
    log "启动服务..."
    docker compose up -d
    show_status
}

# 停止
cmd_stop() {
    log "停止服务..."
    docker compose stop
}

# 重启
cmd_restart() {
    log "重启服务..."
    docker compose restart
    show_status
}

# 查看日志
cmd_logs() {
    log "查看日志（Ctrl+C 退出）..."
    docker compose logs -f --tail=100
}

# 停止并删除容器
cmd_down() {
    log "停止并删除容器..."
    docker compose down
    warn "数据卷保留，如需删除请运行: ./deploy.sh clean"
}

# 清理（包括镜像和数据卷）
cmd_clean() {
    warn "此操作将删除所有容器、镜像和数据卷！"
    read -p "确认清理？(y/N) " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        log "已取消"
        exit 0
    fi
    log "停止并删除容器和镜像..."
    docker compose down --rmi all -v
    log "清理完成"
}

# 显示状态
show_status() {
    echo ""
    echo "========================================"
    echo "  AI Skill 编排系统 — 运行状态"
    echo "========================================"
    docker compose ps
    echo ""
    info "访问地址: http://localhost:8080"
    info "查看日志: ./deploy.sh logs"
    info "停止服务: ./deploy.sh stop"
    echo "========================================"
    echo ""
}

# 主逻辑
case "${1:-build}" in
    build|up)     cmd_build ;;
    start)        cmd_start ;;
    stop)         cmd_stop ;;
    restart)      cmd_restart ;;
    logs)         cmd_logs ;;
    down)         cmd_down ;;
    clean)        cmd_clean ;;
    status|ps)    show_status ;;
    *)
        echo "用法: $0 {build|start|stop|restart|logs|down|clean|status}"
        echo ""
        echo "命令说明:"
        echo "  build    构建镜像并启动（默认）"
        echo "  start    启动已构建的服务"
        echo "  stop     停止服务"
        echo "  restart  重启服务"
        echo "  logs     查看实时日志"
        echo "  down     停止并删除容器（保留数据）"
        echo "  clean    删除所有容器、镜像和数据卷"
        echo "  status   查看运行状态"
        exit 1
        ;;
esac
