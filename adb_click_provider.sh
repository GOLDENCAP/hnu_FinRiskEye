#!/bin/bash
# ============================================================================
# ClickCoordinateProvider ADB 测试脚本
# ============================================================================
# 用途: 通过 adb 调用 ContentProvider 获取点击坐标
# 包名: com.example.chapter07
# Authority: com.example.chapter07.clickprovider
# ============================================================================

PKG="com.example.chapter07"
AUTHORITY="${PKG}.clickprovider"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 打印分隔线
print_separator() {
    echo -e "${CYAN}------------------------------------------------------------${NC}"
}

print_title() {
    echo -e "${GREEN}▶ $1${NC}"
}

# ----------------------------------------------------------------------------
# 1. 查询最近一次点击坐标
# ----------------------------------------------------------------------------
get_last_click() {
    print_title "查询最近一次点击坐标 (/click)"
    print_separator
    adb shell content query --uri "content://${AUTHORITY}/click"
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 2. 查询所有历史记录
# ----------------------------------------------------------------------------
get_history() {
    print_title "查询全部历史记录 (/history)"
    print_separator
    adb shell content query --uri "content://${AUTHORITY}/history"
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 3. 查询状态信息
# ----------------------------------------------------------------------------
get_info() {
    print_title "查询状态信息 (/info)"
    print_separator
    adb shell content query --uri "content://${AUTHORITY}/info"
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 4. 提取最近坐标（仅返回 x y）
# ----------------------------------------------------------------------------
get_xy_only() {
    print_title "提取 x, y 坐标（只取数字）"
    print_separator
    local result=$(adb shell content query --uri "content://${AUTHORITY}/click" 2>&1)
    local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
    local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
    local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)

    echo -e "x=${BLUE}${x}${NC}, y=${BLUE}${y}${NC}, is_valid=${YELLOW}${valid}${NC}"

    if [ "$valid" = "1" ]; then
        echo -e "${GREEN}✅ 坐标有效${NC}"
    else
        echo -e "${RED}❌ 坐标无效（还没有点击记录或服务未启动）${NC}"
    fi
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 5. 通过 adb 模拟一次点击（写入测试数据）
# ----------------------------------------------------------------------------
test_insert() {
    local x=${1:-500}
    local y=${2:-800}
    local source=${3:-"adb_test"}

    print_title "写入测试坐标 (${x}, ${y}), 来源: ${source}"
    print_separator
    adb shell content insert --uri "content://${AUTHORITY}/click" \
        --bind x:i:${x} \
        --bind y:i:${y} \
        --bind source:s:${source}
    echo -e "${GREEN}✅ 已写入${NC}"
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 6. 清空所有历史
# ----------------------------------------------------------------------------
clear_history() {
    print_title "清空所有历史记录"
    print_separator
    adb shell content delete --uri "content://${AUTHORITY}/history"
    echo -e "${GREEN}✅ 已清空${NC}"
    print_separator
    echo ""
}

# ----------------------------------------------------------------------------
# 7. 启动服务 + 等待 + 查询
# ----------------------------------------------------------------------------
start_and_query() {
    print_title "启动全局触摸服务并等待坐标"
    print_separator

    # 提示用户操作
    echo -e "${YELLOW}请在设备上进行点击操作（最长等待 ${1:-15} 秒）...${NC}"

    # 后台等待：每 2 秒轮询一次
    local timeout=${1:-15}
    local elapsed=0
    local interval=2

    while [ $elapsed -lt $timeout ]; do
        sleep $interval
        elapsed=$((elapsed + interval))

        local result=$(adb shell content query --uri "content://${AUTHORITY}/click" 2>&1)
        local valid=$(echo "$result" | grep -oP 'is_valid=\K[0-9]+' | head -1)

        if [ "$valid" = "1" ]; then
            local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
            local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
            local src=$(echo "$result" | grep -oP 'source=\K\S+' | head -1)
            echo -e "${GREEN}✅ 检测到新点击: (${x}, ${y}), 来源: ${src}, 用时 ${elapsed}s${NC}"
            print_separator
            return 0
        fi
        echo -n "."
    done

    echo ""
    echo -e "${RED}❌ 超时: 未检测到有效点击坐标${NC}"
    echo -e "${YELLOW}提示: 请确认服务已启动并已授权悬浮窗/无障碍权限${NC}"
    print_separator
    return 1
}

# ----------------------------------------------------------------------------
# 8. 实时监控模式
# ----------------------------------------------------------------------------
monitor_mode() {
    print_title "实时监控模式 (每 1 秒轮询一次，按 Ctrl+C 退出)"
    print_separator

    local last_x=""
    local last_y=""
    local last_ts=""

    while true; do
        local result=$(adb shell content query --uri "content://${AUTHORITY}/click" 2>&1)
        local x=$(echo "$result" | grep -oP 'x=\K-?[0-9]+' | head -1)
        local y=$(echo "$result" | grep -oP 'y=\K-?[0-9]+' | head -1)
        local ts=$(echo "$result" | grep -oP 'timestamp=\K[0-9]+' | head -1)

        if [ "$ts" != "$last_ts" ] && [ -n "$ts" ] && [ "$ts" != "0" ]; then
            echo -e "[$(date '+%H:%M:%S')] ${GREEN}新点击: x=${BLUE}${x}${NC}, y=${BLUE}${y}${NC}, ts=${ts}${NC}"
            last_x=$x
            last_y=$y
            last_ts=$ts
        fi

        sleep 1
    done
}

# ============================================================================
# 主菜单
# ============================================================================
show_menu() {
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║   ClickCoordinateProvider ADB 测试工具             ║${NC}"
    echo -e "${GREEN}║   Authority: ${AUTHORITY}${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "  1) 查询最近一次点击 (last)"
    echo "  2) 查询全部历史记录 (history)"
    echo "  3) 查询状态信息 (info)"
    echo "  4) 提取 x, y 坐标（脚本友好）"
    echo "  5) 写入测试坐标 (insert)"
    echo "  6) 清空历史记录 (delete)"
    echo "  7) 启动服务并等待新点击"
    echo "  8) 实时监控模式"
    echo "  9) 一次性跑完全部查询"
    echo "  0) 退出"
    echo ""
    echo -n "请选择 [0-9]: "
}

# 主循环
if [ $# -eq 0 ]; then
    while true; do
        show_menu
        read -r choice
        case $choice in
            1) get_last_click ;;
            2) get_history ;;
            3) get_info ;;
            4) get_xy_only ;;
            5)
                echo -n "输入 x [默认 500]: "
                read -r ix
                echo -n "输入 y [默认 800]: "
                read -r iy
                echo -n "输入 source [默认 adb_test]: "
                read -r is
                test_insert "${ix:-500}" "${iy:-800}" "${is:-adb_test}"
                ;;
            6) clear_history ;;
            7) start_and_query ;;
            8) monitor_mode ;;
            9)
                get_info
                get_last_click
                get_history
                ;;
            0) echo "Bye!"; exit 0 ;;
            *) echo -e "${RED}无效选项${NC}" ;;
        esac
    done
else
    # 命令行模式：./adb_click_provider.sh last
    case "$1" in
        last)    get_last_click ;;
        history) get_history ;;
        info)    get_info ;;
        xy)      get_xy_only ;;
        clear)   clear_history ;;
        monitor) monitor_mode ;;
        wait)    start_and_query "${2:-15}" ;;
        insert)
            x=${2:-500}; y=${3:-800}; src=${4:-adb_test}
            test_insert "$x" "$y" "$src"
            ;;
        all)
            get_info
            get_last_click
            get_history
            ;;
        *)
            echo "用法: $0 [last|history|info|xy|clear|monitor|wait [N]|insert X Y SOURCE|all]"
            exit 1
            ;;
    esac
fi