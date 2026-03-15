# 自动编译脚本
Write-Host "========================================"
Write-Host "开始编译跨平台语音输入系统"
Write-Host "========================================"
Write-Host ""

# 检查 Rust 环境
Write-Host "[1/4] 检查 Rust 环境..."
rustup --version
if ($LASTEXITCODE -ne 0) {
    Write-Host "错误: 未安装 rustup"
    exit 1
}

# 安装 nightly 工具链
Write-Host "[2/4] 安装 Rust nightly 工具链..."
rustup install nightly
rustup update nightly

# 编译服务器
Write-Host ""
Write-Host "[3/4] 编译中转服务器..."
Set-Location "E:\Work\Code\voiceinput\voice-input-server"
Write-Host "Rust 版本: $(rustc --version)"

cargo clean
cargo build --release

if ($LASTEXITCODE -eq 0) {
    Write-Host "服务器编译成功!"
} else {
    Write-Host "服务器编译失败"
}

# 编译桌面端
Write-Host ""
Write-Host "[4/4] 编译桌面客户端..."
Set-Location "E:\Work\Code\voiceinput\voice-input-desktop"

$nodeVersion = node --version 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "警告: 未安装 Node.js，跳过桌面端编译"
} else {
    Write-Host "Node.js 版本: $nodeVersion"
    npm install
    npm run build
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "桌面端编译成功!"
    } else {
        Write-Host "桌面端编译失败"
    }
}

Write-Host ""
Write-Host "========================================"
Write-Host "编译完成!"
Write-Host "========================================"

Set-Location "E:\Work\Code\voiceinput"
