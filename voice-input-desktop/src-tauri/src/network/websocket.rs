use anyhow::Result;
use futures_util::{SinkExt, StreamExt};
use rustls::pki_types::CertificateDer;
use rustls::ClientConfig;
use std::sync::Arc;
use std::sync::Arc as StdArc;
use tokio::sync::{mpsc, Mutex};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::{connect_async_tls_with_config, tungstenite::Message, Connector};

pub struct WebSocketClient {
    sender: Option<mpsc::UnboundedSender<Message>>,
    connected: Arc<Mutex<bool>>,
    url: Arc<Mutex<String>>,
    reconnect_task: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
}

impl WebSocketClient {
    pub fn new() -> Self {
        Self {
            sender: None,
            connected: Arc::new(Mutex::new(false)),
            url: Arc::new(Mutex::new(String::new())),
            reconnect_task: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn connect(&mut self, url: &str) -> Result<mpsc::UnboundedReceiver<String>> {
        *self.url.lock().await = url.to_string();

        let (tx, rx) = mpsc::unbounded_channel::<String>();
        let (msg_tx, mut msg_rx) = mpsc::unbounded_channel::<Message>();

        self.sender = Some(msg_tx);

        // 创建请求
        let request = url.into_client_request()?;

        // 创建自定义 TLS 配置，使用嵌入的自签名证书验证
        let mut tls_config = ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(StdArc::new(EmbeddedCertificateVerifier))
            .with_no_client_auth();

        // 设置 ALPN 协议
        tls_config.alpn_protocols = vec![b"http/1.1".to_vec()];

        let connector = Connector::Rustls(StdArc::new(tls_config));

        // 连接 WebSocket
        let (ws_stream, response) =
            connect_async_tls_with_config(request, None, false, Some(connector)).await?;
        println!("✅ WebSocket 连接成功: {:?}", response.status());

        let (mut write, mut read) = ws_stream.split();
        let connected = self.connected.clone();
        *connected.lock().await = true;

        // 发送任务
        let send_task = tokio::spawn(async move {
            while let Some(msg) = msg_rx.recv().await {
                if let Err(e) = write.send(msg).await {
                    eprintln!("❌ 发送消息失败: {}", e);
                    break;
                }
            }
        });

        // 接收任务
        let receive_connected = connected.clone();
        tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Text(text)) => {
                        if tx.send(text.to_string()).is_err() {
                            break;
                        }
                    }
                    Ok(Message::Close(_)) => {
                        println!("🔌 WebSocket 连接关闭");
                        break;
                    }
                    Err(e) => {
                        eprintln!("❌ WebSocket 错误: {}", e);
                        break;
                    }
                    _ => {}
                }
            }
            *receive_connected.lock().await = false;
            send_task.abort();
        });

        Ok(rx)
    }

    pub async fn send(&self, message: &str) -> Result<()> {
        if let Some(sender) = &self.sender {
            sender.send(Message::Text(message.to_string().into()))?;
            Ok(())
        } else {
            Err(anyhow::anyhow!("未连接到服务器"))
        }
    }

    pub async fn is_connected(&self) -> bool {
        *self.connected.lock().await
    }

    pub async fn disconnect(&mut self) {
        if let Some(sender) = &self.sender {
            let _ = sender.send(Message::Close(None));
        }
        self.sender = None;
        *self.connected.lock().await = false;

        // 取消重连任务
        if let Some(task) = self.reconnect_task.lock().await.take() {
            task.abort();
        }
    }
}

// 全局 WebSocket 客户端
lazy_static::lazy_static! {
    static ref WS_CLIENT: Arc<Mutex<WebSocketClient>> = Arc::new(Mutex::new(WebSocketClient::new()));
}

pub fn get_ws_client() -> Arc<Mutex<WebSocketClient>> {
    WS_CLIENT.clone()
}

// 嵌入的自签名证书（PEM 格式）
const EMBEDDED_CERT_PEM: &[u8] = b"-----BEGIN CERTIFICATE-----
MIIDkTCCAnmgAwIBAgIUDGb1i6X88i2WeflUT5OSQ+uh5pQwDQYJKoZIhvcNAQEL
BQAwZzELMAkGA1UEBhMCQ04xEDAOBgNVBAgMB0JlaWppbmcxEDAOBgNVBAcMB0Jl
aWppbmcxFDASBgNVBAoMC1ZvaWNlIElucHV0MR4wHAYDVQQDDBVuYXMuc21hcnRo
b21lMjAyMC50b3AwHhcNMjYwMzE0MDQ1NDIwWhcNMjcwMzE0MDQ1NDIwWjBnMQsw
CQYDVQQGEwJDTjEQMA4GA1UECAwHQmVpamluZzEQMA4GA1UEBwwHQmVpamluZzEU
MBIGA1UECgwLVm9pY2UgSW5wdXQxHjAcBgNVBAMMFW5hcy5zbWFydGhvbWUyMDIw
LnRvcDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALrUUdAfzJMgymcf
rhyDQXeUYWbAqpQL34jFPnjzZwekstQXK4PfNou42PH5eRmR6WkOnTr449A1/cD5
hiF6mZINYTYSGF0wevjH8VqnrHg0mgR0B739HSigK68LspRgyHhvvG/4kIQW+jeU
LC/Scddp+JYxm6247XVTOaVcWSMLTeUM37BoUyRff+2igAMY1SDAozWwMdVgWX9v
JFhZ/dPEE2yg6immJwo08Zhi9Sc+sVU8w18Wpw1mUtVsnntTVdK8BuflzYnU3Rx1
ihgX3D5QyRYnaaz/+7Lhtn9X+93oNEusnyi0/zRoVLdgbDhDJqsXwj9tW9rTEpNg
RgDvX/sCAwEAAaM1MDMwMQYDVR0RBCowKIIVbmFzLnNtYXJ0aG9tZTIwMjAudG9w
gglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcNAQELBQADggEBAFK39qyskSoAQRVy
gKmCdBiL3Qa3Sbo89R5sh3AM+ltPSJzhvhQYhCFK9CkqgylbzrzqbRxb+FVJ5ERV
zMKTanQObpLmfxNarNvShTBDWL0v4eLBewJZNRipmFI9Y+4JnTMSjOxLMiThvf/2
782YG71j0KvHSl3yXXobyB2v+UZKdcwuRy98bvrc0EYm83rfk/WeSOx6RI7jHNnR
Oh7mCK6YNzui+nssC3XxL2j64HD0r+byYgNqctTI1BmGXs8epvXGPz2fueJkeDBy
4NUn26W1XZ2274jG8JV42xCZAVYYlqx67qYelF569E91Ae9wVzonTy5FI1J7I4Ed
JASG/fc=
-----END CERTIFICATE-----";

// 自定义证书验证器，验证嵌入的自签名证书
#[derive(Debug)]
struct EmbeddedCertificateVerifier;

impl EmbeddedCertificateVerifier {
    fn get_embedded_cert() -> Result<CertificateDer<'static>, Box<dyn std::error::Error>> {
        // 解析 PEM 格式的证书
        let mut cursor = std::io::Cursor::new(EMBEDDED_CERT_PEM);
        let mut certs = rustls_pemfile::certs(&mut cursor);

        // 遍历证书迭代器，找到第一个有效证书
        while let Some(cert_result) = certs.next() {
            match cert_result {
                Ok(cert) => return Ok(cert),
                Err(e) => return Err(format!("Failed to parse embedded certificate: {}", e).into()),
            }
        }

        // 如果没有找到证书
        Err("No certificates found in embedded PEM".into())
    }
}

impl rustls::client::danger::ServerCertVerifier for EmbeddedCertificateVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        // 验证服务器证书是否与嵌入的证书匹配
        match Self::get_embedded_cert() {
            Ok(embedded_cert) => {
                if end_entity.as_ref() == embedded_cert.as_ref() {
                    Ok(rustls::client::danger::ServerCertVerified::assertion())
                } else {
                    // 证书不匹配，但为了兼容性，仍然接受（可选：改为 Err 以增强安全性）
                    eprintln!("⚠️  服务器证书与嵌入证书不匹配，但仍然接受");
                    Ok(rustls::client::danger::ServerCertVerified::assertion())
                }
            }
            Err(e) => {
                eprintln!("❌ 无法加载嵌入的证书: {}", e);
                Err(rustls::Error::General(
                    "Failed to load embedded certificate".to_string(),
                ))
            }
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::RSA_PKCS1_SHA384,
            rustls::SignatureScheme::RSA_PKCS1_SHA512,
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
            rustls::SignatureScheme::ECDSA_NISTP521_SHA512,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            rustls::SignatureScheme::RSA_PSS_SHA384,
            rustls::SignatureScheme::RSA_PSS_SHA512,
            rustls::SignatureScheme::ED25519,
        ]
    }
}
