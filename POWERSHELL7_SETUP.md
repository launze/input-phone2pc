# PowerShell 7 设置指南

## 已完成的配置

✅ PowerShell 7 已安装在: `C:\Program Files\PowerShell\7`
✅ 已将 PowerShell 7 添加到用户PATH环境变量的最前面

## 使Kiro使用PowerShell 7

### 方法1：重启Kiro（推荐）
1. 完全关闭Kiro
2. 重新打开Kiro
3. Kiro会自动使用PATH中的第一个PowerShell（现在是PowerShell 7）

### 方法2：在Kiro设置中配置
1. 打开Kiro设置
2. 搜索 "terminal" 或 "shell"
3. 将shell路径设置为: `C:\Program Files\PowerShell\7\pwsh.exe`

### 方法3：在Windows Terminal中设置默认
1. 打开Windows Terminal设置
2. 在"启动"选项中，将"默认配置文件"设置为"PowerShell 7"

## 验证PowerShell版本

在新的终端窗口中运行：
```powershell
$PSVersionTable
```

应该看到：
- PSVersion: 7.x.x
- PSEdition: Core

## 其他配置

### 设置PowerShell 7为Windows Terminal默认
```powershell
# 查找PowerShell 7的GUID
Get-ChildItem "HKCU:\Console" | Where-Object { $_.GetValue("FaceName") -like "*PowerShell*" }
```

### 创建PowerShell 7的快捷方式
右键桌面 → 新建 → 快捷方式
目标: `C:\Program Files\PowerShell\7\pwsh.exe`

## 常用命令对比

| 功能 | PowerShell 5.1 | PowerShell 7 |
|------|----------------|--------------|
| 启动 | `powershell` | `pwsh` |
| 版本 | `$PSVersionTable` | `$PSVersionTable` |
| 跨平台 | ❌ 仅Windows | ✅ Windows/Linux/macOS |
| 性能 | 较慢 | 更快 |
| 新特性 | 有限 | 持续更新 |

## 故障排查

### 如果pwsh命令不可用
1. 重启计算机（让PATH环境变量生效）
2. 或者使用完整路径: `& "C:\Program Files\PowerShell\7\pwsh.exe"`

### 如果Kiro仍使用PowerShell 5.1
1. 检查Kiro的设置文件
2. 可能需要在Kiro配置中明确指定shell路径

## 参考链接

- [PowerShell 7 官方文档](https://learn.microsoft.com/zh-cn/powershell/scripting/install/install-powershell-on-windows)
- [PowerShell 7 新特性](https://learn.microsoft.com/zh-cn/powershell/scripting/whats-new/what-s-new-in-powershell-7)
