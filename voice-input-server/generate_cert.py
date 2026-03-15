#!/usr/bin/env python3
"""
生成自签名证书用于 WSS (WebSocket Secure)
"""

from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import datetime
import os
import ipaddress

def generate_self_signed_cert(hostname="nas.smarthome2020.top", output_dir="."):
    """生成自签名证书"""
    
    # 生成私钥
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
    
    # 保存私钥 (PKCS8 格式，用于 Rust)
    key_path = os.path.join(output_dir, "key.pem")
    with open(key_path, "wb") as f:
        f.write(private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,  # 使用 PKCS8 格式
            encryption_algorithm=serialization.NoEncryption()
        ))
    print(f"私钥已保存到: {key_path} (PKCS8 格式)")
    
    # 同时保存一份 Traditional OpenSSL 格式（备用）
    key_path_traditional = os.path.join(output_dir, "key_traditional.pem")
    with open(key_path_traditional, "wb") as f:
        f.write(private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption()
        ))
    print(f"私钥(传统格式)已保存到: {key_path_traditional}")
    
    # 保存证书
    cert_path = os.path.join(output_dir, "cert.pem")
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    print(f"证书已保存到: {cert_path}")
    
    # 同时生成 PKCS12 格式（用于导入到 Android）
    try:
        from cryptography.hazmat.primitives.serialization import pkcs12
        p12_data = pkcs12.serialize_key_and_certificates(
            name=b"voice-input-server",
            key=private_key,
            cert=cert,
            cas=None,
            encryption_algorithm=serialization.NoEncryption()
        )
        p12_path = os.path.join(output_dir, "server.p12")
        with open(p12_path, "wb") as f:
            f.write(p12_data)
        print(f"PKCS12 已保存到: {p12_path}")
    except Exception as e:
        print(f"PKCS12 生成失败: {e}")
    
    return cert_path, key_path

if __name__ == "__main__":
    print("正在生成自签名证书...")
    cert_path, key_path = generate_self_signed_cert()
    print(f"\n证书生成完成!")
    print(f"服务端使用 {cert_path} 和 {key_path} 启动 WSS 服务")
    print(f"请将 {cert_path} 复制到客户端目录")
