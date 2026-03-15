use anyhow::Result;
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};
use tokio_tungstenite::{connect_async_tls_with_config, tungstenite::Message, Connector};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use rustls::ClientConfig;
use std::sync::Arc as StdArc;



pub struct WebSocketClient {
    sender: Option<mpsc::UnboundedSender<Message>>,
    connected: Arc<Mutex<bool>>,
}

impl WebSocketClient {
    pub fn new() -> Self {
        Self {
            sender: None,
            connected: Arc::new(Mutex::new(false)),
        }
    }

    pub async fn connect(&mut self, url: &str) -> Result<mpsc::UnboundedReceiver<String>> {
        let (tx, rx) = mpsc::unbounded_channel::<String>();
        let (msg_tx, mut msg_rx) = mpsc::unbounded_channel::<Message>();

        self.sender = Some(msg_tx);

        // 创建请求
        let request = url.into_client_request()?;

        // 创建自定义 TLS 配置，接受自签名证书
        let mut tls_config = ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(StdArc::new(NoCertificateVerification))
            .with_no_client_auth();

        // 设置 ALPN 协议
        tls_config.alpn_protocols = vec![b"http/1.1".to_vec()];

        let connector = Connector::Rustls(StdArc::new(tls_config));

        // 连接 WebSocket
        let (ws_stream, response) = connect_async_tls_with_config(
            request,
            None,
            false,
            Some(connector),
        ).await?;
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
    }
}

// 全局 WebSocket 客户端
lazy_static::lazy_static! {
    static ref WS_CLIENT: Arc<Mutex<WebSocketClient>> = Arc::new(Mutex::new(WebSocketClient::new()));
}

pub fn get_ws_client() -> Arc<Mutex<WebSocketClient>> {
    WS_CLIENT.clone()
}

// 自定义证书验证器，接受所有证书（包括自签名证书）
#[derive(Debug)]
struct NoCertificateVerification;

impl rustls::client::danger::ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        // 接受所有证书
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
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


