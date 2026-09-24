#!/bin/bash
# ============================================================================
# ClickCoordinateProvider ADB 实时获取点击坐标工具
# ============================================================================
# 功能: 通过 adb 实时获取屏幕点击的绝对坐标
# 包名: com.example.chapter07
# Authority: com.example.chapter07.clickprovider
# ============================================================================

PKG="com.example.chapter07"
AUTHORITY="${PKG}.clickprovider"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# ==================== 核心函数 ====================

# 实时等待下一次点击（阻塞模式）
wait_for_click() {
    local timeout=${1:-30000}  # 默认 30 秒超时
    local label=${2:-""}

    echo -e "${CYAN}⏳ 等待点击... (超时 ${timeout}ms)${NC} ${label}"

    local result=$(adb shell content query \
        --uri "content://${AUTHORITY}/click?wait=${timeout}" 2>&1)

    local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
    local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
    local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)

    if [ "$valid" = "1" ]; then
        echo -e "${GREEN}🎯 点击坐标: (${x}, ${y})${NC}"
        echo "${x},${y}"
        return 0
    else
        echo -e "${RED}❌ 超时或无效坐标${NC}"
        return 1
    fi
}

# 实时监控模式（持续轮询）
monitor_clicks() {
    echo -e "${CYAN}📡 实时监控模式 (Ctrl+C 退出)${NC}"

    local last_ts=""
    while true; do
        local result=$(adb shell content query \
            --uri "content://${AUTHORITY}/click" 2>&1)

        local ts=$(echo "$result" | grep -oP 'timestamp=\K[0-9]+' | head -1)
        local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
        local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
        local source=$(echo "$result" | grep -oP 'source=\K[^,]+' | head -1)
        local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)
        local counter=$(echo "$result" | grep -oP 'click_counter=\K[0-9]+' | head -1)

        if [ "$ts" != "$last_ts" ] && [ -n "$ts" ] && [ "$ts" != "0" ]; then
            local time_str=$(date '+%H:%M:%S')
            if [ "$valid" = "1" ]; then
                echo -e "[${time_str}] ${GREEN}✅ (#${counter}) (${x}, ${y}) ← ${source}${NC}"
            else
                echo -e "[${time_str}] ${YELLOW}⚠️  (#${counter}) 无有效坐标${NC}"
            fi
            last_ts=$ts
        fi

        sleep 0.5
    done
}

# 轮询等待模式（content query 配合短间隔）
poll_wait() {
    local timeout=${1:-30000}
    local interval=${2:-100}  # 100ms 轮询间隔

    echo -e "${CYAN}🔄 轮询等待模式 (timeout=${timeout}ms, interval=${interval}ms)${NC}"

    local elapsed=0
    local last_ts=""

    while [ $elapsed -lt $timeout ]; do
        local result=$(adb shell content query \
            --uri "content://${AUTHORITY}/click" 2>&1)

        local ts=$(echo "$result" | grep -oP 'timestamp=\K[0-9]+' | head -1)
        local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
        local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
        local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)

        if [ "$ts" != "$last_ts" ] && [ "$ts" != "" ] && [ "$ts" != "0" ]; then
            if [ "$valid" = "1" ]; then
                echo -e "${GREEN}🎯 捕获点击: (${x}, ${y}) 耗时 ${elapsed}ms${NC}"
                echo "${x},${y}"
                return 0
            fi
            last_ts=$ts
        fi

        sleep $((interval / 1000))
        elapsed=$((elapsed + interval))
    done

    echo -e "${RED}❌ 等待超时 (${timeout}ms)${NC}"
    return 1
}

# ==================== 辅助函数 ====================

get_info() {
    echo -e "${CYAN}📊 状态信息${NC}"
    adb shell content query --uri "content://${AUTHORITY}/info"
}

get_last() {
    echo -e "${CYAN}📍 最近一次点击${NC}"
    adb shell content query --uri "content://${AUTHORITY}/click"
}

get_history() {
    echo -e "${CYAN}📜 历史记录${NC}"
    adb shell content query --uri "content://${AUTHORITY}/history"
}

insert_test() {
    local x=${1:-500}
    local y=${2:-800}
    local src=${3:-adb}
    echo -e "${CYAN}📝 写入测试数据: (${x}, ${y})${NC}"
    adb shell content insert --uri "content://${AUTHORITY}/click" \
        --bind x:i:${x} --bind y:i:${y} --bind source:s:${src}
}

clear_all() {
    echo -e "${CYAN}🗑️ 清空历史${NC}"
    adb shell content delete --uri "content://${AUTHORITY}/history"
}

# 提取 x,y 纯数值（便于脚本使用）
get_xy() {
    local result=$(adb shell content query --uri "content://${AUTHORITY}/click" 2>&1)
    local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
    local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
    local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)

    if [ "$valid" = "1" ]; then
        echo "${x},${y}"
    else
        echo "-1,-1"
    fi
}

# ==================== 帮助信息 ====================

show_help() {
    cat << 'EOF'
═══════════════════════════════════════════════════════════════════
   ClickCoordinateProvider 实时获取点击坐标工具
═══════════════════════════════════════════════════════════════════

📌 核心功能:
   wait      [timeout_ms]     阻塞等待下一次点击（推荐）
   poll      [timeout_ms] [interval]  轮询等待模式
   monitor                    实时监控所有点击

📊 查询功能:
   info                       状态信息
   last                       最近一次点击
   history                    全部历史记录

🔧 测试功能:
   insert  [x] [y] [source]  写入测试坐标
   clear                       清空历史
   xy                          仅返回 x,y（脚本友好）

═══════════════════════════════════════════════════════════════════

💡 推荐用法:

   # 1. 阻塞等待（等待用户在屏幕上点击）
   ./adb_click_realtime.sh wait 30000

   # 2. 实时监控（持续显示所有点击）
   ./adb_click_realtime.sh monitor

   # 3. 提取坐标供脚本使用
   xy=$(./adb_click_realtime.sh xy)
   x=${xy%,*}
   y=${xy#*,}

   # 4. 轮询模式（可自定义间隔）
   ./adb_click_realtime.sh poll 15000 50

═══════════════════════════════════════════════════════════════════

⚙️ 前置条件:
   1. 安装 APK: adb install -r app/build/outputs/apk/debug/app-debug.apk
   2. 启动服务: 进入 "全局触摸记录" 并点击开始
   3. 授予悬浮窗权限
   4. 开始测试

EOF
}

# ==================== 主入口 ====================

case "${1:-help}" in
    wait)
        wait_for_click "${2:-30000}" ""
        ;;
    poll)
        poll_wait "${2:-30000}" "${3:-100}"
        ;;
    monitor|mon)
        monitor_clicks
        ;;
    info)
        get_info
        ;;
    last)
        get_last
        ;;
    history|hist)
        get_history
        ;;
    insert)
        insert_test "${2:-500}" "${3:-800}" "${4:-adb}"
        ;;
    clear)
        clear_all
        ;;
    xy)
        get_xy
        ;;
    help|--help|-h|"")
        show_help
        ;;
    *)
        echo -e "${RED}未知命令: $1${NC}"
        echo "使用: $0 help 查看帮助"
        exit 1
        ;;
esac