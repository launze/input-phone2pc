mod crypto;
mod db;
mod device_manager;
mod protocol;
mod updates;

use anyhow::Result;
use db::PairingDb;
use device_manager::DeviceManager;
use futures_util::{SinkExt, StreamExt};
use protocol::{DeviceInfo, ServerMessage};
use std::fs::File;
use std::io::BufReader;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio_tungstenite::{accept_async, tungstenite::Message};
use tracing::{error, info, warn};
use updates::UpdateService;
use uuid::Uuid;

use rustls::{Certificate, PrivateKey, ServerConfig};
use rustls_pemfile::{certs, pkcs8_private_keys, rsa_private_keys};
use tokio_rustls::{server::TlsStream, TlsAcceptor};

fn load_tls_config() -> Result<Arc<ServerConfig>> {
    let cert_file = File::open("cert.pem")?;
    let mut cert_reader = BufReader::new(cert_file);
    let cert_chain: Vec<Certificate> = certs(&mut cert_reader)?
        .into_iter()
        .map(Certificate)
        .collect();

    if cert_chain.is_empty() {
        return Err(anyhow::anyhow!("no certificates found in cert.pem"));
    }

    let keys = load_private_keys("key.pem")?;
    if keys.is_empty() {
        return Err(anyhow::anyhow!("no private key found in key.pem"));
    }

    let config = ServerConfig::builder()
        .with_safe_defaults()
        .with_no_client_auth()
        .with_single_cert(cert_chain, keys[0].clone())?;

    Ok(Arc::new(config))
}

fn load_private_keys(path: &str) -> Result<Vec<PrivateKey>> {
    let key_file = File::open(path)?;
    let mut key_reader = BufReader::new(key_file);

    let mut keys: Vec<PrivateKey> = pkcs8_private_keys(&mut key_reader)?
        .into_iter()
        .map(PrivateKey)
        .collect();
    if !keys.is_empty() {
        return Ok(keys);
    }

    let key_file = File::open(path)?;
    let mut key_reader = BufReader::new(key_file);
    keys = rsa_private_keys(&mut key_reader)?
        .into_iter()
        .map(PrivateKey)
        .collect();

    if !keys.is_empty() {
        return Ok(keys);
    }

    Err(anyhow::anyhow!("failed to parse private key"))
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let pairing_db = Arc::new(PairingDb::new("pairings.db")?);
    let update_service = Arc::new(UpdateService::new()?);

    let update_service_clone = update_service.clone();
    tokio::spawn(async move {
        if let Err(error) = updates::start_http_server(update_service_clone).await {
            error!("update http server error: {}", error);
        }
    });

    let tls_acceptor = match load_tls_config() {
        Ok(config) => {
            info!("TLS enabled (WSS)");
            Some(TlsAcceptor::from(config))
        }
        Err(e) => {
            warn!("TLS disabled, falling back to WS: {}", e);
            None
        }
    };

    let addr = "0.0.0.0:7070";
    let listener = TcpListener::bind(addr).await?;
    info!("relay server listening on {}", addr);

    let device_manager = Arc::new(DeviceManager::new());

    let manager_clone = device_manager.clone();
    let db_clone = pairing_db.clone();
    tokio::spawn(async move {
        heartbeat_checker(manager_clone, db_clone).await;
    });

    while let Ok((stream, addr)) = listener.accept().await {
        let manager = device_manager.clone();
        let acceptor = tls_acceptor.clone();
        let db = pairing_db.clone();

        tokio::spawn(async move {
            if let Some(acceptor) = acceptor {
                match acceptor.accept(stream).await {
                    Ok(tls_stream) => {
                        if let Err(e) = handle_tls_connection(tls_stream, addr, manager, db).await {
                            error!("TLS connection handler error: {}", e);
                        }
                    }
                    Err(e) => error!("TLS handshake failed: {}", e),
                }
            } else if let Err(e) = handle_connection(stream, addr, manager, db).await {
                error!("WS connection handler error: {}", e);
            }
        });
    }

    Ok(())
}

async fn handle_tls_connection(
    tls_stream: TlsStream<TcpStream>,
    addr: SocketAddr,
    device_manager: Arc<DeviceManager>,
    pairing_db: Arc<PairingDb>,
) -> Result<()> {
    info!("new TLS connection: {}", addr);
    let ws_stream = accept_async(tls_stream).await?;
    handle_ws_stream(ws_stream, addr, device_manager, pairing_db).await
}

async fn handle_connection(
    stream: TcpStream,
    addr: SocketAddr,
    device_manager: Arc<DeviceManager>,
    pairing_db: Arc<PairingDb>,
) -> Result<()> {
    info!("new WS connection: {}", addr);
    let ws_stream = accept_async(stream).await?;
    handle_ws_stream(ws_stream, addr, device_manager, pairing_db).await
}

async fn handle_ws_stream<S>(
    ws_stream: tokio_tungstenite::WebSocketStream<S>,
    addr: SocketAddr,
    device_manager: Arc<DeviceManager>,
    pairing_db: Arc<PairingDb>,
) -> Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut ws_sender, mut ws_receiver) = ws_stream.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();
    let mut device_id: Option<String> = None;

    let send_task = tokio::spawn(async move {
        while let Some(message) = rx.recv().await {
            if let Err(e) = ws_sender.send(message).await {
                error!("send message error: {}", e);
                break;
            }
        }
    });

    while let Some(msg) = ws_receiver.next().await {
        match msg {
            Ok(Message::Text(text)) => {
                if let Err(e) =
                    handle_message(&text, &mut device_id, &tx, &device_manager, &pairing_db).await
                {
                    error!("handle message error: {}", e);
                }
            }
            Ok(Message::Close(_)) => {
                info!("client closed connection: {}", addr);
                break;
            }
            Err(e) => {
                error!("receive message error: {}", e);
                break;
            }
            _ => {}
        }
    }

    if let Some(id) = device_id {
        notify_paired_devices_offline(&id, &device_manager, &pairing_db);
        info!("device disconnected: {}", id);
    }

    send_task.abort();
    Ok(())
}

async fn handle_message(
    text: &str,
    device_id: &mut Option<String>,
    tx: &mpsc::UnboundedSender<Message>,
    device_manager: &Arc<DeviceManager>,
    pairing_db: &Arc<PairingDb>,
) -> Result<()> {
    let message = ServerMessage::from_json(text)?;

    match message {
        ServerMessage::ServerRegister {
            device_id: dev_id,
            device_name,
            device_type,
            version: _,
        } => {
            let session_id = Uuid::new_v4().to_string();
            if let Err(error) = device_manager.register_device(
                dev_id.clone(),
                device_name.clone(),
                device_type.clone(),
                session_id.clone(),
                tx.clone(),
            ) {
                let response = ServerMessage::ServerRegisterResponse {
                    success: false,
                    session_id: String::new(),
                    message: error,
                };
                tx.send(Message::Text(response.to_json()?))?;
                return Ok(());
            }
            *device_id = Some(dev_id.clone());

            let response = ServerMessage::ServerRegisterResponse {
                success: true,
                session_id,
                message: "registered".to_string(),
            };
            tx.send(Message::Text(response.to_json()?))?;
            info!(
                "device registered: {} ({}) [{}]",
                device_name, dev_id, device_type
            );

            if let Ok(paired_list) = pairing_db.get_paired_devices(&dev_id) {
                for (paired_id, paired_name) in &paired_list {
                    if let Some(paired_device) = device_manager.get_device(paired_id) {
                        let notify_new = ServerMessage::PairedDeviceOnline {
                            device_id: paired_id.clone(),
                            device_name: paired_name.clone(),
                            device_type: paired_device.device_type.clone(),
                        };
                        let _ = tx.send(Message::Text(notify_new.to_json()?));

                        let notify_existing = ServerMessage::PairedDeviceOnline {
                            device_id: dev_id.clone(),
                            device_name: device_name.clone(),
                            device_type: device_type.clone(),
                        };
                        device_manager
                            .send_to_device(paired_id, Message::Text(notify_existing.to_json()?));
                    }
                }
            }

            if let Err(error) = deliver_pending_messages(&dev_id, &device_manager, &pairing_db) {
                error!("deliver pending messages failed for {}: {}", dev_id, error);
            }
        }

        ServerMessage::DeviceListRequest { device_type } => {
            let devices = device_manager.get_online_devices(device_type.as_deref());
            let device_infos: Vec<DeviceInfo> = devices
                .into_iter()
                .map(|d| DeviceInfo {
                    device_id: d.device_id,
                    device_name: d.device_name,
                    device_type: d.device_type,
                    online: true,
                })
                .collect();

            let response = ServerMessage::DeviceListResponse {
                devices: device_infos,
            };
            tx.send(Message::Text(response.to_json()?))?;
        }

        ServerMessage::ServerPairRequest {
            from_device_id,
            from_device_name,
            to_device_id,
            pin: _,
        } => {
            info!(
                "📋 收到配对请求：{} ({}) -> {} (PIN: 验证中)",
                from_device_name, from_device_id, to_device_id
            );

            if device_id.as_ref() != Some(&from_device_id) {
                warn!("❌ 配对请求验证失败：发送者 ID 不匹配");
                let response = ServerMessage::ServerPairResponse {
                    success: false,
                    from_device_id: from_device_id.clone(),
                    from_device_name: from_device_name.clone(),
                    to_device_id: to_device_id.clone(),
                    to_device_name: String::new(),
                    message: "Pair failed: invalid sender".to_string(),
                };
                tx.send(Message::Text(response.to_json()?))?;
                return Ok(());
            }

            if from_device_id == to_device_id {
                warn!("⚠️ 配对失败：设备不能与自己配对");
                let response = ServerMessage::ServerPairResponse {
                    success: false,
                    from_device_id: from_device_id.clone(),
                    from_device_name: from_device_name.clone(),
                    to_device_id: to_device_id.clone(),
                    to_device_name: from_device_name.clone(),
                    message: "Pair failed: cannot pair with self".to_string(),
                };
                tx.send(Message::Text(response.to_json()?))?;
                return Ok(());
            }

            let Some(to_device) = device_manager.get_device(&to_device_id) else {
                let response = ServerMessage::ServerPairResponse {
                    success: false,
                    from_device_id: from_device_id.clone(),
                    from_device_name: from_device_name.clone(),
                    to_device_id: to_device_id.clone(),
                    to_device_name: String::new(),
                    message: "Pair failed: target device offline".to_string(),
                };
                tx.send(Message::Text(response.to_json()?))?;
                return Ok(());
            };
            let to_device_name = to_device.device_name.clone();

            let from_device_name = device_manager
                .get_device(&from_device_id)
                .map(|d| d.device_name)
                .unwrap_or(from_device_name);

            if let Err(e) = pairing_db.add_pairing(
                &from_device_id,
                &from_device_name,
                &to_device_id,
                &to_device_name,
            ) {
                error!("❌ 配对持久化失败：{}", e);
                let response = ServerMessage::ServerPairResponse {
                    success: false,
                    from_device_id: from_device_id.clone(),
                    from_device_name: from_device_name.clone(),
                    to_device_id: to_device_id.clone(),
                    to_device_name: to_device_name.clone(),
                    message: format!("Pair failed: persist error: {}", e),
                };
                tx.send(Message::Text(response.to_json()?))?;
                return Ok(());
            }

            info!(
                "✅ 配对已保存到数据库：{} <-> {}",
                from_device_id, to_device_id
            );
            device_manager.add_pairing(from_device_id.clone(), to_device_id.clone());
            info!("✅ 配对已添加到内存管理器");

            let response = ServerMessage::ServerPairResponse {
                success: true,
                from_device_id: from_device_id.clone(),
                from_device_name: from_device_name.clone(),
                to_device_id: to_device_id.clone(),
                to_device_name: to_device_name.clone(),
                message: "paired".to_string(),
            };
            tx.send(Message::Text(response.to_json()?))?;
            info!("✅ 已向发起设备发送配对成功响应");

            let response_to_target = ServerMessage::ServerPairResponse {
                success: true,
                from_device_id: from_device_id.clone(),
                from_device_name: from_device_name.clone(),
                to_device_id: to_device_id.clone(),
                to_device_name: to_device_name.clone(),
                message: "paired".to_string(),
            };
            device_manager
                .send_to_device(&to_device_id, Message::Text(response_to_target.to_json()?));
            info!("✅ 已向目标设备发送配对成功响应");

            if let Some(from_device) = device_manager.get_device(&from_device_id) {
                let notify_target = ServerMessage::PairedDeviceOnline {
                    device_id: from_device_id.clone(),
                    device_name: from_device_name.clone(),
                    device_type: from_device.device_type.clone(),
                };
                device_manager
                    .send_to_device(&to_device_id, Message::Text(notify_target.to_json()?));
                info!("✅ 已向目标设备发送 PAIRED_DEVICE_ONLINE 通知");
            }

            let notify_from = ServerMessage::PairedDeviceOnline {
                device_id: to_device_id.clone(),
                device_name: to_device_name.clone(),
                device_type: to_device.device_type.clone(),
            };
            tx.send(Message::Text(notify_from.to_json()?))?;
            info!("✅ 已向发起设备发送 PAIRED_DEVICE_ONLINE 通知");
            info!(
                "🎉 配对流程完成：{} ({}) <-> {} ({})",
                from_device_name, from_device_id, to_device_name, to_device_id
            );
        }

        ServerMessage::RelayMessage {
            from_device_id,
            to_device_id,
            payload,
            ..
        } => {
            if device_id.as_ref() != Some(&from_device_id) {
                let error_response = ServerMessage::Error {
                    code: "INVALID_SENDER".to_string(),
                    message: "sender device mismatch".to_string(),
                };
                tx.send(Message::Text(error_response.to_json()?))?;
                return Ok(());
            }

            let payload_type = payload
                .get("type")
                .and_then(|value| value.as_str())
                .unwrap_or_default();

            if payload_type == "INPUT_ACK" {
                if let Some(server_message_id) = payload
                    .get("server_message_id")
                    .and_then(|value| value.as_str())
                {
                    let _ = pairing_db.mark_message_delivered(server_message_id);
                }

                let relay_msg = ServerMessage::RelayMessage {
                    from_device_id: from_device_id.clone(),
                    to_device_id: to_device_id.clone(),
                    payload,
                    message_id: None,
                    from_device_name: None,
                    stored_at: None,
                    delivery_mode: Some("ack".to_string()),
                };

                if !device_manager
                    .send_to_device(&to_device_id, Message::Text(relay_msg.to_json()?))
                {
                    warn!("ack target offline: {}", to_device_id);
                }

                return Ok(());
            }

            if let Err(message) = validate_relay_payload(&payload) {
                let error_response = ServerMessage::Error {
                    code: "INVALID_RELAY_PAYLOAD".to_string(),
                    message,
                };
                tx.send(Message::Text(error_response.to_json()?))?;
                return Ok(());
            }

            let sender_name = device_manager
                .get_device(&from_device_id)
                .map(|device| device.device_name)
                .unwrap_or_else(|| from_device_id.clone());
            let message_id = Uuid::new_v4().to_string();
            let sent_at = payload
                .get("timestamp")
                .and_then(|value| value.as_i64())
                .unwrap_or_else(|| chrono::Utc::now().timestamp_millis());
            let stored_at = pairing_db.queue_relay_message(
                &message_id,
                &from_device_id,
                &sender_name,
                &to_device_id,
                &payload,
                sent_at,
            )?;

            let relay_msg = ServerMessage::RelayMessage {
                from_device_id: from_device_id.clone(),
                to_device_id: to_device_id.clone(),
                payload,
                message_id: Some(message_id.clone()),
                from_device_name: Some(sender_name),
                stored_at: Some(stored_at),
                delivery_mode: Some("live".to_string()),
            };

            let queued =
                !device_manager.send_to_device(&to_device_id, Message::Text(relay_msg.to_json()?));
            if queued {
                warn!("relay target offline, queued message for {}", to_device_id);
            }

            let stored_response = ServerMessage::RelayStored {
                message_id,
                to_device_id,
                stored_at,
                queued,
            };
            tx.send(Message::Text(stored_response.to_json()?))?;
        }

        ServerMessage::NotificationForward {
            from_device_id,
            to_device_id,
            notification,
        } => {
            let notif_msg = ServerMessage::NotificationForward {
                from_device_id: from_device_id.clone(),
                to_device_id: to_device_id.clone(),
                notification,
            };

            if !device_manager.send_to_device(&to_device_id, Message::Text(notif_msg.to_json()?)) {
                warn!("notification target offline: {}", to_device_id);
            }
        }

        ServerMessage::UnpairRequest {
            from_device_id,
            to_device_id,
        } => {
            if let Err(e) = pairing_db.remove_pairing(&from_device_id, &to_device_id) {
                error!("remove pairing failed: {}", e);
            }

            device_manager.remove_pairing(&from_device_id, &to_device_id);

            let from_name = device_manager
                .get_device(&from_device_id)
                .map(|d| d.device_name.clone())
                .unwrap_or_else(|| "Unknown".to_string());

            let notify = ServerMessage::UnpairNotify {
                device_id: from_device_id.clone(),
                device_name: from_name,
            };
            device_manager.send_to_device(&to_device_id, Message::Text(notify.to_json()?));
        }

        ServerMessage::Heartbeat { timestamp: _ } => {
            if let Some(id) = device_id {
                device_manager.update_last_seen(id);
            }
            let response = ServerMessage::Heartbeat {
                timestamp: chrono::Utc::now().timestamp(),
            };
            tx.send(Message::Text(response.to_json()?))?;
        }

        ServerMessage::EncryptionKeyExchange {
            device_id: dev_id,
            public_key,
        } => {
            device_manager.set_encryption_key(&dev_id, public_key);
        }

        ServerMessage::EncryptedMessage {
            from_device_id,
            to_device_id,
            ciphertext,
            nonce,
        } => {
            let encrypted_msg = ServerMessage::EncryptedMessage {
                from_device_id: from_device_id.clone(),
                to_device_id: to_device_id.clone(),
                ciphertext,
                nonce,
            };

            if !device_manager
                .send_to_device(&to_device_id, Message::Text(encrypted_msg.to_json()?))
            {
                warn!("encrypted target offline: {}", to_device_id);
            }
        }

        _ => {
            warn!("unhandled server message");
        }
    }

    Ok(())
}

fn deliver_pending_messages(
    device_id: &str,
    device_manager: &DeviceManager,
    pairing_db: &PairingDb,
) -> Result<usize> {
    let pending_messages = pairing_db.get_pending_messages(device_id)?;
    let mut delivered = 0;

    for pending in pending_messages {
        let relay_msg = ServerMessage::RelayMessage {
            from_device_id: pending.from_device_id,
            to_device_id: pending.to_device_id,
            payload: pending.payload,
            message_id: Some(pending.message_id),
            from_device_name: Some(pending.from_device_name),
            stored_at: Some(pending.stored_at),
            delivery_mode: Some("offline_sync".to_string()),
        };

        if !device_manager.send_to_device(device_id, Message::Text(relay_msg.to_json()?)) {
            break;
        }

        delivered += 1;
    }

    if delivered > 0 {
        info!("delivered {} pending messages to {}", delivered, device_id);
    }

    Ok(delivered)
}

fn validate_relay_payload(payload: &serde_json::Value) -> std::result::Result<(), String> {
    let payload_type = payload
        .get("type")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if payload_type != "CLIPBOARD_IMAGE" {
        return Ok(());
    }

    let image_data = payload
        .get("data")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing image data".to_string())?;
    let mime_type = payload
        .get("mime_type")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if !matches!(mime_type, "image/jpeg" | "image/png") {
        return Err("unsupported image mime type".to_string());
    }

    if image_data.len() > 6_000_000 {
        return Err("image payload too large".to_string());
    }

    Ok(())
}

async fn heartbeat_checker(device_manager: Arc<DeviceManager>, pairing_db: Arc<PairingDb>) {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(30));

    loop {
        interval.tick().await;
        let devices = device_manager.get_online_devices(None);
        let now = chrono::Utc::now().timestamp();
        for device in devices {
            if now - device.last_seen > 120 {
                notify_paired_devices_offline(&device.device_id, &device_manager, &pairing_db);
                info!("device heartbeat timeout: {}", device.device_id);
            }
        }
    }
}

fn notify_paired_devices_offline(
    device_id: &str,
    device_manager: &DeviceManager,
    pairing_db: &PairingDb,
) {
    let paired_ids = pairing_db.get_paired_devices(device_id).unwrap_or_default();
    let device_info = device_manager.unregister_device(device_id);

    if let Some(device) = device_info {
        for (paired_id, _) in &paired_ids {
            let offline_msg = ServerMessage::PairedDeviceOffline {
                device_id: device.device_id.clone(),
                device_name: device.device_name.clone(),
                device_type: device.device_type.clone(),
            };
            if let Ok(json) = offline_msg.to_json() {
                let _ = device_manager.send_to_device(paired_id, Message::Text(json));
            }
        }
    }
}
