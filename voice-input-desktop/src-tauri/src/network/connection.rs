use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::OwnedWriteHalf;
use tokio::net::{TcpListener, TcpStream};

use crate::crypto::{decrypt_string, EncryptionKey};
use crate::{input, storage::config::AppConfig};
use crate::storage::history::{self, NewHistoryRecord};
use base64::Engine;
use std::error::Error;
use std::sync::Arc;
use tokio::sync::Mutex;
use uuid::Uuid;

lazy_static::lazy_static! {
    static ref ENCRYPTION_KEY: Arc<Mutex<Option<EncryptionKey>>> = Arc::new(Mutex::new(None));
}

pub async fn get_encryption_key() -> Option<EncryptionKey> {
    ENCRYPTION_KEY.lock().await.clone()
}

pub async fn set_encryption_key(key: Option<EncryptionKey>) {
    *ENCRYPTION_KEY.lock().await = key;
}

pub async fn start_tcp_server(app_handle: AppHandle) -> Result<(), Box<dyn Error>> {
    let listener = TcpListener::bind("0.0.0.0:58889").await?;
    println!("TCP server started on 0.0.0.0:58889");

    loop {
        let (socket, addr) = listener.accept().await?;
        println!("New TCP connection: {}", addr);

        let app = app_handle.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_client(socket, app).await {
                eprintln!("TCP client error: {}", e);
            }
        });
    }
}

async fn handle_client(socket: TcpStream, app_handle: AppHandle) -> Result<(), Box<dyn Error>> {
    let mut paired_peer: Option<(String, String)> = None;

    let (reader, mut writer) = socket.into_split();
    let mut reader = BufReader::new(reader);
    let mut line = String::new();

    loop {
        line.clear();
        let n = reader.read_line(&mut line).await?;

        if n == 0 {
            if let Some((device_id, device_name)) = &paired_peer {
                let disc_payload = serde_json::json!({
                    "device_id": device_id,
                    "device_name": device_name,
                    "reason": "offline",
                    "disconnected_at": chrono::Utc::now().to_rfc3339()
                });
                app_handle
                    .emit("device_disconnected", &disc_payload)
                    .unwrap_or(());
                println!("Device disconnected: {} ({})", device_name, device_id);
            }
            break;
        }

        let message: serde_json::Value = serde_json::from_str(&line)?;

        if let Some(msg_type) = message.get("type").and_then(|v| v.as_str()) {
            match msg_type {
                "PAIR_REQUEST" => {
                    let device_id = message
                        .get("device_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("");
                    let device_name = message
                        .get("device_name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Android 设备");

                    if device_id.is_empty() {
                        let response = serde_json::json!({
                            "type": "PAIR_RESPONSE",
                            "success": false,
                            "message": "invalid device_id"
                        });
                        writer.write_all(response.to_string().as_bytes()).await?;
                        writer.write_all(b"\n").await?;
                        continue;
                    }

                    paired_peer = Some((device_id.to_string(), device_name.to_string()));

                    let conn_payload = serde_json::json!({
                        "device_id": device_id,
                        "device_name": device_name,
                        "connected_at": chrono::Utc::now().to_rfc3339(),
                        "via": "lan"
                    });
                    app_handle
                        .emit("device_connected", &conn_payload)
                        .unwrap_or(());
                    println!("Pair succeeded over LAN: {} ({})", device_name, device_id);

                    let response = serde_json::json!({
                        "type": "PAIR_RESPONSE",
                        "success": true,
                        "message": "paired"
                    });
                    writer.write_all(response.to_string().as_bytes()).await?;
                    writer.write_all(b"\n").await?;
                }
                "ENCRYPTION_KEY_EXCHANGE" => {
                    if let Some(public_key) = message.get("public_key").and_then(|v| v.as_str()) {
                        if let Ok(key) = EncryptionKey::from_hex(public_key) {
                            set_encryption_key(Some(key)).await;
                            println!("Encryption key set from client");
                        }
                    }
                }
                "ENCRYPTED_MESSAGE" => {
                    if let Some(ciphertext) = message.get("ciphertext").and_then(|v| v.as_str()) {
                        if let Some(nonce) = message.get("nonce").and_then(|v| v.as_str()) {
                            if let Some(key) = get_encryption_key().await {
                                if let Ok(decrypted_json) = decrypt_string(ciphertext, nonce, &key)
                                {
                                    if let Ok(decrypted_message) =
                                        serde_json::from_str::<serde_json::Value>(&decrypted_json)
                                    {
                                        handle_input_message(
                                            &decrypted_message,
                                            &mut writer,
                                            &app_handle,
                                            "lan_encrypted",
                                            paired_peer.as_ref(),
                                        )
                                        .await?;
                                    }
                                }
                            }
                        }
                    }
                }
                "TEXT_INPUT" | "IMAGE_INPUT" | "NOTIFICATION_INPUT" => {
                    handle_input_message(
                        &message,
                        &mut writer,
                        &app_handle,
                        "lan",
                        paired_peer.as_ref(),
                    )
                    .await?;
                }
                "HEARTBEAT" => {
                    let response = serde_json::json!({
                        "type": "HEARTBEAT",
                        "timestamp": chrono::Utc::now().timestamp_millis()
                    });
                    writer.write_all(response.to_string().as_bytes()).await?;
                    writer.write_all(b"\n").await?;
                }
                _ => {}
            }
        }
    }

    Ok(())
}

async fn handle_input_message(
    message: &serde_json::Value,
    writer: &mut OwnedWriteHalf,
    app_handle: &AppHandle,
    via: &str,
    peer: Option<&(String, String)>,
) -> Result<(), Box<dyn Error>> {
    let Some(msg_type) = message.get("type").and_then(|v| v.as_str()) else {
        return Ok(());
    };

    let received_at = chrono::Utc::now().timestamp_millis();
    let sent_at = message
        .get("timestamp")
        .and_then(|v| v.as_i64())
        .unwrap_or(received_at);
    let (from_device_id, from_device_name) = peer
        .map(|(device_id, device_name)| (device_id.clone(), device_name.clone()))
        .unwrap_or_else(|| ("未知设备".to_string(), "Android 设备".to_string()));

    match msg_type {
        "TEXT_INPUT" => {
            if let Some(text) = message.get("text").and_then(|v| v.as_str()) {
                let press_enter = message
                    .get("press_enter")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let input_result = handle_text_input_by_mode(text, press_enter, false);
                let history_delivery_mode = history_delivery_mode_for_text_input("live", &input_result.mode);

                let record = NewHistoryRecord {
                    id: Uuid::new_v4().to_string(),
                    from_device_id: from_device_id.clone(),
                    from_device_name: from_device_name.clone(),
                    content_type: "text".to_string(),
                    content: text.to_string(),
                    sent_at,
                    received_at,
                    via: via.to_string(),
                    delivery_mode: history_delivery_mode.clone(),
                    metadata: None,
                };
                if let Ok((stored, inserted)) = history::record_message(record) {
                    if inserted {
                        app_handle.emit("history_recorded", &stored).unwrap_or(());
                    }
                }

                let text_payload = serde_json::json!({
                    "from_device_id": from_device_id,
                    "from_device_name": from_device_name,
                    "text": text,
                    "via": via,
                    "timestamp": received_at,
                    "sent_at": sent_at,
                    "delivery_mode": history_delivery_mode
                });
                app_handle
                    .emit("text_received", &text_payload)
                    .unwrap_or(());
                app_handle
                    .emit("input_result", &input_result)
                    .unwrap_or(());

                send_input_ack(writer, "text", input_result.success).await?;
            }
        }
        "IMAGE_INPUT" => {
            let image_data = message.get("image_data").and_then(|v| v.as_str());
            let image_bytes = image_data
                .and_then(|data| base64::engine::general_purpose::STANDARD.decode(data).ok());
            let success = image_bytes
                .as_deref()
                .map(|bytes| input::simulate_image_input(bytes).is_ok())
                .unwrap_or(false);

            let file_name = message
                .get("file_name")
                .and_then(|v| v.as_str())
                .unwrap_or("clipboard-image");
            let metadata = image_data.map(|data| {
                serde_json::json!({
                    "mime_type": message.get("mime_type").and_then(|v| v.as_str()).unwrap_or(""),
                    "file_name": file_name,
                    "data": data,
                    "width": message.get("width").and_then(|v| v.as_i64()),
                    "height": message.get("height").and_then(|v| v.as_i64()),
                    "size": message.get("size").and_then(|v| v.as_i64())
                })
                .to_string()
            });
            let record = NewHistoryRecord {
                id: Uuid::new_v4().to_string(),
                from_device_id,
                from_device_name,
                content_type: "image".to_string(),
                content: format!("[图片] {}", file_name),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: "live".to_string(),
                metadata,
            };
            if let Ok((stored, inserted)) = history::record_message(record) {
                if inserted {
                    app_handle.emit("history_recorded", &stored).unwrap_or(());
                }
            }

            let image_payload = serde_json::json!({
                "via": via,
                "timestamp": received_at,
                "sent_at": sent_at,
                "delivery_mode": "live"
            });
            app_handle
                .emit("image_received", &image_payload)
                .unwrap_or(());

            send_input_ack(writer, "image", success).await?;
        }
        "NOTIFICATION_INPUT" => {
            let app_name = message
                .get("app_name")
                .and_then(|v| v.as_str())
                .unwrap_or("未知应用");
            let app_package = message
                .get("app_package")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let title = message.get("title").and_then(|v| v.as_str()).unwrap_or("");
            let text = message.get("text").and_then(|v| v.as_str()).unwrap_or("");
            let content = format_notification_content(app_name, title, text);
            let copy_to_clipboard = message
                .get("copy_to_clipboard")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let metadata = serde_json::json!({
                "app_name": app_name,
                "app_package": app_package,
                "title": title,
                "text": text,
                "forward_mode": message.get("forward_mode").and_then(|v| v.as_str()),
                "silent": message.get("silent").and_then(|v| v.as_bool()),
                "copy_to_clipboard": copy_to_clipboard,
                "notification_key": message.get("notification_key").and_then(|v| v.as_str()),
                "channel_id": message.get("channel_id").and_then(|v| v.as_str()),
                "group_key": message.get("group_key").and_then(|v| v.as_str()),
                "category": message.get("category").and_then(|v| v.as_str()),
                "sub_text": message.get("sub_text").and_then(|v| v.as_str()),
                "big_text": message.get("big_text").and_then(|v| v.as_str()),
                "conversation_title": message.get("conversation_title").and_then(|v| v.as_str()),
                "post_time": message.get("post_time").and_then(|v| v.as_i64()),
                "is_ongoing": message.get("is_ongoing").and_then(|v| v.as_bool()),
                "is_clearable": message.get("is_clearable").and_then(|v| v.as_bool()),
                "importance": message.get("importance").and_then(|v| v.as_i64()),
                "icon": message.get("icon").and_then(|v| v.as_str())
            })
            .to_string();
            let record = NewHistoryRecord {
                id: Uuid::new_v4().to_string(),
                from_device_id,
                from_device_name,
                content_type: "notification".to_string(),
                content: content.clone(),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: "live".to_string(),
                metadata: Some(metadata),
            };
            if let Ok((stored, inserted)) = history::record_message(record) {
                if inserted {
                    app_handle.emit("history_recorded", &stored).unwrap_or(());
                }
            }
            if copy_to_clipboard {
                let _ = input::copy_text_to_clipboard(&content);
            }

            let notification_payload = serde_json::json!({
                "via": via,
                "timestamp": received_at,
                "sent_at": sent_at,
                "delivery_mode": "live",
                "app_name": app_name,
                "app_package": app_package,
                "title": title,
                "text": text,
                "content": content,
                "forward_mode": message.get("forward_mode").and_then(|v| v.as_str()),
                "silent": message.get("silent").and_then(|v| v.as_bool()).unwrap_or(false),
                "copy_to_clipboard": copy_to_clipboard
            });
            app_handle
                .emit("notification_received", &notification_payload)
                .unwrap_or(());

            send_input_ack(writer, "notification", true).await?;
        }
        _ => {}
    }

    Ok(())
}

fn format_notification_content(app_name: &str, title: &str, text: &str) -> String {
    let mut content = format!("[通知] {}", app_name);
    if !title.trim().is_empty() {
        content.push_str(" - ");
        content.push_str(title.trim());
    }
    if !text.trim().is_empty() {
        content.push('\n');
        content.push_str(text.trim());
    }
    content
}

#[derive(serde::Serialize)]
struct TextInputResult {
    success: bool,
    mode: String,
    message: String,
    timestamp: i64,
}

fn handle_text_input_by_mode(text: &str, press_enter: bool, skip_injection: bool) -> TextInputResult {
    let now = chrono::Utc::now().timestamp_millis();
    if skip_injection {
        return TextInputResult {
            success: true,
            mode: "history_only".to_string(),
            message: "离线补发已保存到历史，未自动插入。".to_string(),
            timestamp: now,
        };
    }

    let mode = AppConfig::load().input_mode;
    match mode.as_str() {
        "clipboard" => match input::copy_text_to_clipboard(text) {
            Ok(_) => TextInputResult {
                success: true,
                mode,
                message: "已复制到剪贴板。".to_string(),
                timestamp: now,
            },
            Err(error) => TextInputResult {
                success: false,
                mode,
                message: error,
                timestamp: now,
            },
        },
        "confirm" => TextInputResult {
            success: true,
            mode,
            message: "已保存到历史，等待手动插入。".to_string(),
            timestamp: now,
        },
        _ => {
            input::simulate_text_input(text);
            if press_enter {
                let _ = input::simulate_enter_key();
            }
            TextInputResult {
                success: true,
                mode: "direct".to_string(),
                message: "已直接插入到当前光标位置。".to_string(),
                timestamp: now,
            }
        }
    }
}

fn history_delivery_mode_for_text_input(delivery_mode: &str, input_mode: &str) -> String {
    if delivery_mode == "offline_sync" || input_mode == "history_only" {
        "offline_sync".to_string()
    } else if input_mode == "confirm" {
        "manual".to_string()
    } else {
        delivery_mode.to_string()
    }
}

async fn send_input_ack(
    writer: &mut OwnedWriteHalf,
    content_type: &str,
    success: bool,
) -> Result<(), Box<dyn Error>> {
    let response = serde_json::json!({
        "type": "INPUT_ACK",
        "success": success,
        "content_type": content_type,
        "timestamp": chrono::Utc::now().timestamp_millis()
    });
    writer.write_all(response.to_string().as_bytes()).await?;
    writer.write_all(b"\n").await?;
    Ok(())
}
