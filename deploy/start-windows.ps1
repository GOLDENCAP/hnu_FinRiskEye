<#
.SYNOPSIS
  Windows 一键启动第三级风险服务（非 Docker 路径）。

.DESCRIPTION
  检查 Node.js / .env / 端口占用后，以前台方式运行 `node --env-file=.env server.js`。
  Ctrl+C 会触发服务端优雅关闭（SIGINT 处理），清理幂等缓存定时器后退出。
  如需后台常驻，推荐使用 Docker Compose（见 deploy/README.md）。

.PARAMETER Port
  监听端口，默认 3100。若 3100 已被现有服务占用，可用 3101 临时启动。

.PARAMETER Bind
  监听地址，默认 0.0.0.0（局域网/模拟器可访问）。仅本机访问可改为 127.0.0.1。

.PARAMETER RiskApiToken
  可选：临时覆盖 .env 里的 RISK_API_TOKEN（只对本次进程生效，不写文件）。

.PARAMETER AddFirewallRule
  可选：为端口添加入站防火墙规则（需要管理员权限）。

.EXAMPLE
  .\start-windows.ps1
  .\start-windows.ps1 -Port 3101 -Bind 127.0.0.1
  .\start-windows.ps1 -AddFirewallRule
#>
param(
    [int]$Port = 3100,
    [string]$Bind = "0.0.0.0",
    [string]$RiskApiToken = "",
    [switch]$AddFirewallRule
)

$ErrorActionPreference = "Stop"

$deployDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverDir = (Resolve-Path -LiteralPath (Join-Path $deployDir "..\thirdlevel\server")).Path
$envFile = Join-Path $serverDir ".env"
$envExample = Join-Path $serverDir ".env.example"

Write-Host "== FinRiskEye 第三级风险服务（Windows 本地启动） ==" -ForegroundColor Cyan
Write-Host "服务目录: $serverDir"

# 1. Node.js 检查（需要 >= 20，fetch/--env-file 依赖）
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) {
    throw "未找到 Node.js。请安装 Node.js 20+（https://nodejs.org/）后重试。"
}
$nodeVersion = (& node --version).Trim()
$major = [int](($nodeVersion -replace '^v', '') -split '\.')[0]
if ($major -lt 20) {
    throw "Node.js 版本过低（$nodeVersion），需要 >= 20。"
}
Write-Host "Node.js: $nodeVersion"

# 2. .env 检查（模型密钥只放服务端 .env，已 gitignore）
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath $envExample -Destination $envFile
    Write-Host ""
    Write-Host "[提示] 已从 .env.example 创建 .env。" -ForegroundColor Yellow
    Write-Host "       请填入 RISK_API_TOKEN 与 AI_API_KEY 后重新运行本脚本。"
    Write-Host "       .env 已被 gitignore，切勿提交或把密钥写进任何文档。" -ForegroundColor Yellow
    exit 1
}

# 3. 端口占用检查
$listeners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listeners) {
    $ownerPid = $listeners[0].OwningProcess
    throw "端口 $Port 已被进程 PID $ownerPid 占用。请换端口：.\start-windows.ps1 -Port 3101，或先停止旧服务。"
}

# 4. 可选：入站防火墙规则（需要管理员）
if ($AddFirewallRule) {
    $ruleName = "FinRiskEye ThirdLevel Risk TCP $Port"
    try {
        if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound `
                -Protocol TCP -LocalPort $Port -Action Allow | Out-Null
            Write-Host "[防火墙] 已添加入站规则: $ruleName" -ForegroundColor Green
        } else {
            Write-Host "[防火墙] 规则已存在: $ruleName"
        }
    } catch {
        Write-Host "[警告] 添加防火墙规则失败（需要管理员权限）: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "       可手动放行 TCP $Port，或用管理员 PowerShell 重新执行。" -ForegroundColor Yellow
    }
}

# 5. 启动（前台运行；Ctrl+C 触发优雅关闭）
$env:PORT = "$Port"
$env:HOST = $Bind
if (-not [string]::IsNullOrWhiteSpace($RiskApiToken)) {
    $env:RISK_API_TOKEN = $RiskApiToken
    Write-Host "[提示] 本次进程使用命令行提供的 RISK_API_TOKEN（未修改 .env）。"
}

Write-Host ""
Write-Host "启动中... 健康检查: http://127.0.0.1:$Port/health"
Write-Host "局域网 / Android 模拟器访问: http://<本机局域网IP或10.0.2.2>:$Port"
Write-Host "按 Ctrl+C 停止服务（优雅关闭）。" -ForegroundColor DarkGray
Write-Host ""

Push-Location -LiteralPath $serverDir
try {
    & node --env-file=.env server.js
} finally {
    Pop-Location
}
