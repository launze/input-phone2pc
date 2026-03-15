# 使用 PowerShell 生成自签名证书
param(
    [string]$Hostname = "nas.smarthome2020.top",
    [string]$OutputDir = "."
)

# 生成自签名证书
$cert = New-SelfSignedCertificate `
    -DnsName $Hostname, "localhost" `
    -CertStoreLocation "cert:\LocalMachine\My" `
    -KeyAlgorithm RSA `
    -KeyLength 2048 `
    -KeyExportPolicy Exportable `
    -NotAfter (Get-Date).AddYears(1)

$certThumbprint = $cert.Thumbprint
Write-Host "证书指纹: $certThumbprint"

# 导出证书（带私钥）到 PFX
$pwd = ConvertTo-SecureString -String "password123" -Force -AsPlainText
$pfxPath = Join-Path $OutputDir "server.pfx"
Export-PfxCertificate `
    -Cert "cert:\LocalMachine\My\$certThumbprint" `
    -FilePath $pfxPath `
    -Password $pwd

Write-Host "PFX 已导出到: $pfxPath"

# 使用 OpenSSL 转换 PEM 格式（如果安装了 OpenSSL）
# 或者使用 .NET 直接导出

try {
    # 导出证书（不含私钥）
    $certPath = Join-Path $OutputDir "cert.pem"
    $certBytes = $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    [System.IO.File]::WriteAllBytes($certPath, $certBytes)
    Write-Host "证书已导出到: $certPath"

    # 导出私钥
    $keyPath = Join-Path $OutputDir "key.pem"
    
    # 获取私钥
    $rsa = $cert.PrivateKey
    if ($rsa -eq $null) {
        # 尝试从 PFX 加载
        $pfx = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($pfxPath, $pwd, [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable)
        $rsa = $pfx.PrivateKey
    }

    if ($rsa -ne $null) {
        # 导出私钥参数
        $rsaParams = $rsa.ExportParameters($true)
        
        # 构建 PKCS#8 格式的私钥
        # 使用 BouncyCastle 或者手动构建
        # 这里我们使用一种简单的方法：通过 PFX 提取
        
        # 使用 certutil 来转换
        $tempPfx = Join-Path $env:TEMP "temp_server.pfx"
        Copy-Item $pfxPath $tempPfx -Force
        
        # 使用 OpenSSL 如果可用
        $openssl = Get-Command openssl -ErrorAction SilentlyContinue
        if ($openssl) {
            & openssl pkcs12 -in $tempPfx -out $keyPath -nocerts -nodes -passin pass:password123 2>$null
            & openssl pkcs12 -in $tempPfx -out $certPath -nokeys -clcerts -passin pass:password123 2>$null
            Write-Host "使用 OpenSSL 导出 PEM 格式"
        } else {
            # 如果没有 OpenSSL，创建一个简单的私钥文件（仅用于测试）
            Write-Host "警告: 未找到 OpenSSL，私钥导出可能不完整" -ForegroundColor Yellow
            
            # 使用 .NET 的 PEM 编码
            $privateKeyBytes = $rsa.ExportPkcs8PrivateKey()
            $privateKeyPem = "-----BEGIN PRIVATE KEY-----`n"
            $base64 = [Convert]::ToBase64String($privateKeyBytes)
            for ($i = 0; $i -lt $base64.Length; $i += 64) {
                $line = $base64.Substring($i, [Math]::Min(64, $base64.Length - $i))
                $privateKeyPem += "$line`n"
            }
            $privateKeyPem += "-----END PRIVATE KEY-----`n"
            
            [System.IO.File]::WriteAllText($keyPath, $privateKeyPem)
            Write-Host "私钥已导出到: $keyPath"
        }
        
        Remove-Item $tempPfx -ErrorAction SilentlyContinue
    }
    
    Write-Host "`n证书生成完成!" -ForegroundColor Green
    Write-Host "证书: $certPath"
    Write-Host "私钥: $keyPath"
    Write-Host "PFX: $pfxPath (密码: password123)"
}
catch {
    Write-Host "错误: $_" -ForegroundColor Red
}
finally {
    # 清理证书存储
    Remove-Item "cert:\LocalMachine\My\$certThumbprint" -ErrorAction SilentlyContinue
}
