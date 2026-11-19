param(
    [string]$RiskApiToken = "local-thirdlevel-dev",
    [int]$Port = 3100
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
    $projectGradleProperties = Join-Path $PSScriptRoot "..\..\gradle.properties"
    if (Test-Path -LiteralPath $projectGradleProperties) {
        $line = Get-Content -LiteralPath $projectGradleProperties |
            Where-Object { $_ -match "^\s*AI_API_KEY\s*=" } |
            Select-Object -First 1
        if ($line) {
            $env:AI_API_KEY = ($line -split "=", 2)[1].Trim()
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
    $secureKey = Read-Host "请输入 AI_API_KEY（输入内容不会写入文件）" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $env:AI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
    throw "AI_API_KEY 未配置"
}

$env:RISK_API_TOKEN = $RiskApiToken
$env:PORT = [string]$Port

Write-Host ""
Write-Host "第三级风险服务即将启动。"
Write-Host "端口: $Port"
Write-Host "测试令牌: $RiskApiToken"
Write-Host "关闭此窗口或按 Ctrl+C 将停止服务。"
Write-Host ""

node server.js
