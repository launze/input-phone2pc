#!/usr/bin/env python3
"""
生成自签名证书用于 WSS (WebSocket Secure) - Windows 兼容版本
"""

from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import datetime
import os
import ipaddress
import sys

def generate_self_signed_cert(hostname="nas.smarthome2020.top", output_dir="."):
    """生成自签名证书"""
    
    print(f"正在生成自签名证书，主机名: {hostname}")
    
    # 生成私钥
    print("生成 RSA 私钥...")
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
    )
    
    # 构建证书主题
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
        x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, "Beijing"),
        x509.NameAttribute(NameOID.LOCALITY_NAME, "Beijing"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Voice Input"),
        x509.NameAttribute(NameOID.COMMON_NAME, hostname),
    ])
    
    # 添加 Subject Alternative Name (SAN)
    san = x509.SubjectAlternativeName([
        x509.DNSName(hostname),
        x509.DNSName("localhost"),
        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
    ])
    
    # 构建证书
    print("构建 X.509 证书...")
    cert = x509.CertificateBuilder().subject_name(
        subject
    ).issuer_name(
        issuer
    ).public_key(
        private_key.public_key()
    ).serial_number(
        x509.random_serial_number()
    ).not_valid_before(
        datetime.datetime.now(datetime.timezone.utc)
    ).not_valid_after(
        datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365)
    ).add_extension(
        san, critical=False
    ).sign(private_key, hashes.SHA256())
    
    # 保存私钥 (PKCS8 格式) - 使用二进制模式写入
    key_path = os.path.join(output_dir, "key.pem")
    print(f"保存私钥到: {key_path}")
    
    key_data = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    
    # 确保使用正确的换行符（Windows 和 Linux 都兼容）
    with open(key_path, "wb") as f:
        f.write(key_data)
    
    print(f"✓ 私钥已保存 ({len(key_data)} bytes)")
    
    # 保存证书
    cert_path = os.path.join(output_dir, "cert.pem")
    print(f"保存证书到: {cert_path}")
    
    cert_data = cert.public_bytes(serialization.Encoding.PEM)
    
    with open(cert_path, "wb") as f:
        f.write(cert_data)
    
    print(f"✓ 证书已保存 ({len(cert_data)} bytes)")
    
    # 验证文件
    print("\n验证生成的文件...")
    with open(key_path, "rb") as f:
        key_content = f.read()
        if b"-----BEGIN PRIVATE KEY-----" in key_content and b"-----END PRIVATE KEY-----" in key_content:
            print("✓ 私钥格式正确")
        else:
            print("✗ 私钥格式错误!")
            return None, None
    
    with open(cert_path, "rb") as f:
        cert_content = f.read()
        if b"-----BEGIN CERTIFICATE-----" in cert_content and b"-----END CERTIFICATE-----" in cert_content:
            print("✓ 证书格式正确")
        else:
            print("✗ 证书格式错误!")
            return None, None
    
    print(f"\n✅ 证书生成成功!")
    print(f"  证书: {cert_path}")
    print(f"  私钥: {key_path}")
    
    return cert_path, key_path

if __name__ == "__main__":
    if len(sys.argv) > 1:
        hostname = sys.argv[1]
    else:
        hostname = "nas.smarthome2020.top"
    
    print("="*50)
    print("自签名证书生成工具")
    print("="*50)
    
    cert_path, key_path = generate_self_signed_cert(hostname)
    
    if cert_path and key_path:
        print("\n使用说明:")
        print("1. 将 cert.pem 和 key.pem 放在服务端可执行文件同目录")
        print("2. 启动服务端，会自动启用 WSS")
        print("3. 将 cert.pem 复制到客户端目录用于验证")
        sys.exit(0)
    else:
        print("\n❌ 证书生成失败!")
        sys.exit(1)
