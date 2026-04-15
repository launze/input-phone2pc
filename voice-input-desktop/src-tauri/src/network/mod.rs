pub mod connection;
pub mod discovery;
pub mod protocol;
pub mod websocket;

use crate::storage::history::{self, NewHistoryRecord};
use base64::Engine;
use std::error::Error;
use tauri::{AppHandle, Emitter};
use uuid::Uuid;

pub async fn start_services(app_handle: AppHandle) -> Result<(), Box<dyn Error>> {
    let discovery_handle = app_handle.clone();
    tokio::spawn(async move {
        if let Err(e) = discovery::start_discovery_service(discovery_handle).await {
            eprintln!("UDP discovery service error: {}", e);
        }
    });

    let tcp_handle = app_handle.clone();
    tokio::spawn(async move {
        if let Err(e) = connection::start_tcp_server(tcp_handle).await {
            eprintln!("TCP server error: {}", e);
        }
    });

    // WSS connection is user-triggered via connect_server command.
    Ok(())
}

pub async fn connect_and_register(
    url: &str,
    device_id: &str,
    device_name: &str,
    app_handle: Option<AppHandle>,
) {
    let ws_client = websocket::get_ws_client();
    let mut client = ws_client.lock().await;

    match client.connect(url).await {
        Ok(rx) => {
            println!("WSS connected: {}", url);

            let register_msg = serde_json::json!({
                "type": "SERVER_REGISTER",
                "device_id": device_id,
                "device_name": device_name,
                "device_type": "desktop",
                "version": "1.2.0"
            });

            if let Err(e) = client.send(&register_msg.to_string()).await {
                eprintln!("Failed to send SERVER_REGISTER: {}", e);
                return;
            }

            drop(client);

            let dev_id = device_id.to_string();
            let handle_clone = app_handle.clone();
            tokio::spawn(async move {
                handle_server_messages(rx, dev_id, handle_clone).await;
            });

            tokio::spawn(async move {
                server_heartbeat().await;
            });
        }
        Err(e) => {
            eprintln!("WSS connection failed: {}", e);
        }
    }
}

async fn handle_server_messages(
    mut rx: tokio::sync::mpsc::UnboundedReceiver<String>,
    my_device_id: String,
    app_handle: Option<AppHandle>,
) {
    while let Some(text) = rx.recv().await {
        let msg: serde_json::Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("Failed to parse server message: {}", e);
                continue;
            }
        };

        let msg_type = msg.get("type").and_then(|v| v.as_str()).unwrap_or("");
        match msg_type {
            "SERVER_REGISTER_RESPONSE" => {
                let success = msg
                    .get("success")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                if success {
                    let session_id = msg.get("session_id").and_then(|v| v.as_str()).unwrap_or("");
                    println!("Server register success: {}", session_id);
                } else {
                    let message = msg
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("unknown");
                    eprintln!("Server register failed: {}", message);
                }
            }
            "RELAY_MESSAGE" => {
                let from_id = msg
                    .get("from_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("unknown");
                let from_name = msg
                    .get("from_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or(from_id);
                let delivery_mode = msg
                    .get("delivery_mode")
                    .and_then(|v| v.as_str())
                    .unwrap_or("live");
                let message_id = msg
                    .get("message_id")
                    .and_then(|v| v.as_str())
                    .map(|value| value.to_string())
                    .unwrap_or_else(|| Uuid::new_v4().to_string());
                let received_at = chrono::Utc::now().timestamp_millis();
                let should_inject = delivery_mode != "offline_sync";

                if let Some(payload) = msg.get("payload") {
                    let payload_type = payload.get("type").and_then(|v| v.as_str()).unwrap_or("");
                    match payload_type {
                        "TEXT_INPUT" => {
                            if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
                                if should_inject {
                                    crate::input::simulate_text_input(text);
                                }

                                if let Some(ref handle) = app_handle {
                                    if let Some(record) = build_history_record(
                                        message_id.clone(),
                                        from_id,
                                        from_name,
                                        payload,
                                        "server",
                                        delivery_mode,
                                        received_at,
                                    ) {
                                        emit_history_record(handle, record);
                                    }

                                    let text_payload = serde_json::json!({
                                        "record_id": message_id,
                                        "text": text,
                                        "from_device_id": from_id,
                                        "from_device_name": from_name,
                                        "via": "server",
                                        "timestamp": received_at,
                                        "sent_at": payload.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(received_at),
                                        "delivery_mode": delivery_mode
                                    });
                                    handle.emit("text_received", &text_payload).unwrap_or(());
                                }

                                send_relay_ack(
                                    &my_device_id,
                                    from_id,
                                    "text",
                                    true,
                                    Some(message_id.as_str()),
                                )
                                .await;
                            }
                        }
                        "CLIPBOARD_IMAGE" => {
                            let success = if should_inject {
                                payload
                                    .get("data")
                                    .and_then(|v| v.as_str())
                                    .and_then(|data| {
                                        base64::engine::general_purpose::STANDARD.decode(data).ok()
                                    })
                                    .map(|bytes| crate::input::simulate_image_input(&bytes).is_ok())
                                    .unwrap_or(false)
                            } else {
                                true
                            };

                            if let Some(ref handle) = app_handle {
                                if let Some(record) = build_history_record(
                                    message_id.clone(),
                                    from_id,
                                    from_name,
                                    payload,
                                    "server",
                                    delivery_mode,
                                    received_at,
                                ) {
                                    emit_history_record(handle, record);
                                }

                                let image_payload = serde_json::json!({
                                    "record_id": message_id,
                                    "from_device_id": from_id,
                                    "from_device_name": from_name,
                                    "via": "server",
                                    "timestamp": received_at,
                                    "sent_at": payload.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(received_at),
                                    "delivery_mode": delivery_mode
                                });
                                handle.emit("image_received", &image_payload).unwrap_or(());
                            }

                            send_relay_ack(
                                &my_device_id,
                                from_id,
                                "image",
                                success,
                                Some(message_id.as_str()),
                            )
                            .await;
                        }
                        _ => {}
                    }
                }
            }
            "RELAY_STORED" => {
                if let Some(ref handle) = app_handle {
                    handle.emit("relay_stored", &msg).unwrap_or(());
                }
            }
            "NOTIFICATION_FORWARD" => {
                let from_id = msg
                    .get("from_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("unknown");
                let from_name = msg
                    .get("from_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or(from_id);
                let received_at = chrono::Utc::now().timestamp_millis();
                let message_id = format!("notification-{}", Uuid::new_v4());
                let notification = msg.get("notification").cloned().unwrap_or_else(|| serde_json::json!({}));
                let history_payload = serde_json::json!({
                    "type": "NOTIFICATION_FORWARD",
                    "app_name": notification.get("app_name").and_then(|v| v.as_str()).unwrap_or("通知"),
                    "title": notification.get("title").and_then(|v| v.as_str()).unwrap_or(""),
                    "text": notification.get("text").and_then(|v| v.as_str()).unwrap_or(""),
                    "timestamp": notification.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(received_at)
                });

                if let Some(ref handle) = app_handle {
                    if let Some(record) = build_history_record(
                        message_id,
                        from_id,
                        from_name,
                        &history_payload,
                        "server",
                        "live",
                        received_at,
                    ) {
                        emit_history_record(handle, record);
                    }
                    handle
                        .emit(
                            "notification_received",
                            &serde_json::json!({
                                "from_device_id": from_id,
                                "from_device_name": from_name,
                                "notification": notification,
                                "received_at": received_at
                            }),
                        )
                        .unwrap_or(());
                }
            }
            "HEARTBEAT" => {
                // Ignore server heartbeat response
            }
            "DEVICE_LIST_RESPONSE" => {
                if let Some(devices) = msg.get("devices").and_then(|v| v.as_array()) {
                    println!("Online devices from server: {}", devices.len());
                }
            }
            "SERVER_PAIR_RESPONSE" => {
                let success = msg
                    .get("success")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let from_id = msg
                    .get("from_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let from_name = msg
                    .get("from_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown");
                let to_id = msg
                    .get("to_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let to_name = msg
                    .get("to_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown");

                println!(
                    "📨 [收到] SERVER_PAIR_RESPONSE: success={}, from={}({}) -> to={}({})",
                    success, from_name, from_id, to_name, to_id
                );

                if success {
                    let mut config = crate::storage::config::AppConfig::load();
                    let (other_id, other_name) = if from_id == my_device_id {
                        (to_id, to_name)
                    } else {
                        (from_id, from_name)
                    };

                    if !other_id.is_empty() {
                        config.add_paired_device(other_id.to_string(), other_name.to_string());
                        let _ = config.save();

                        println!("🎉 [配对成功] 设备：{} ({})", other_name, other_id);
                        println!(
                            "   配对详情：from={}({}) -> to={}({})",
                            from_name, from_id, to_name, to_id
                        );

                        // 配对成功后，向服务器发送设备列表请求，间接表明自己在线
                        // 这样服务器会将我们标记为在线，并通知配对的 App 端
                        let device_list_req = serde_json::json!({
                            "type": "DEVICE_LIST_REQUEST",
                            "device_type": "android"
                        });

                        let ws_client = websocket::get_ws_client();
                        let client = ws_client.lock().await;
                        if let Err(e) = client.send(&device_list_req.to_string()).await {
                            eprintln!("❌ [配对后] 发送 DEVICE_LIST_REQUEST 失败：{}", e);
                        } else {
                            println!("✅ [配对后] 已发送 DEVICE_LIST_REQUEST 请求设备列表");
                        }

                        // 触发前端更新 UI
                        if let Some(ref handle) = app_handle {
                            let payload = serde_json::json!({
                                "device_id": other_id,
                                "device_name": other_name,
                                "device_type": "android",
                                "paired_at": chrono::Utc::now().to_rfc3339()
                            });
                            handle.emit("device_paired", &payload).unwrap_or(());
                            println!("✅ [配对成功] 已向前端发送 device_paired 事件");
                        }
                    }
                } else {
                    let message = msg
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("unknown");
                    eprintln!("❌ [配对失败] {}", message);
                    eprintln!(
                        "   配对详情：from={}({}) -> to={}({})",
                        from_name, from_id, to_name, to_id
                    );

                    // 配对失败也通知前端
                    if let Some(ref handle) = app_handle {
                        let payload = serde_json::json!({
                            "success": false,
                            "message": message,
                            "from_device_id": from_id,
                            "from_device_name": from_name,
                            "to_device_id": to_id,
                            "to_device_name": to_name
                        });
                        handle.emit("pair_failed", &payload).unwrap_or(());
                        println!("✅ [配对失败] 已向前端发送 pair_failed 事件");
                    }
                }
            }
            "PAIRED_DEVICE_ONLINE" => {
                let paired_id = msg.get("device_id").and_then(|v| v.as_str()).unwrap_or("?");
                let paired_name = msg
                    .get("device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");
                let paired_type = msg
                    .get("device_type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");

                // Persist from authoritative server push.
                let mut config = crate::storage::config::AppConfig::load();
                config.add_paired_device(paired_id.to_string(), paired_name.to_string());
                let _ = config.save();

                if let Some(ref handle) = app_handle {
                    let payload = serde_json::json!({
                        "device_name": paired_name,
                        "device_id": paired_id,
                        "device_type": paired_type,
                        "connected_at": chrono::Utc::now().to_rfc3339(),
                        "via": "server"
                    });
                    handle.emit("device_connected", &payload).unwrap_or(());
                }
            }
            "PAIRED_DEVICE_OFFLINE" => {
                let offline_id = msg.get("device_id").and_then(|v| v.as_str()).unwrap_or("?");
                let offline_name = msg
                    .get("device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");
                let offline_type = msg
                    .get("device_type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");

                if let Some(ref handle) = app_handle {
                    let payload = serde_json::json!({
                        "device_id": offline_id,
                        "device_name": offline_name,
                        "device_type": offline_type,
                        "reason": "offline",
                        "disconnected_at": chrono::Utc::now().to_rfc3339()
                    });
                    handle.emit("device_disconnected", &payload).unwrap_or(());
                }
            }
            "UNPAIR_NOTIFY" => {
                let unpaired_id = msg.get("device_id").and_then(|v| v.as_str()).unwrap_or("?");
                let unpaired_name = msg
                    .get("device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");

                let mut config = crate::storage::config::AppConfig::load();
                config.remove_paired_device(unpaired_id);
                let _ = config.save();

                if let Some(ref handle) = app_handle {
                    let disc_payload = serde_json::json!({
                        "device_id": unpaired_id,
                        "device_name": unpaired_name,
                        "reason": "unpaired",
                        "disconnected_at": chrono::Utc::now().to_rfc3339()
                    });
                    handle
                        .emit("device_disconnected", &disc_payload)
                        .unwrap_or(());
                }
            }
            "ERROR" => {
                let code = msg.get("code").and_then(|v| v.as_str()).unwrap_or("?");
                let message = msg.get("message").and_then(|v| v.as_str()).unwrap_or("?");
                eprintln!("Server error [{}] {}", code, message);
            }
            _ => {
                // Ignore unknown message types.
            }
        }
    }

    println!("Server message loop ended");
}

async fn server_heartbeat() {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(30));
    loop {
        interval.tick().await;
        let ws_client = websocket::get_ws_client();
        let client = ws_client.lock().await;
        if client.is_connected().await {
            let heartbeat = serde_json::json!({
                "type": "HEARTBEAT",
                "timestamp": chrono::Utc::now().timestamp_millis()
            });
            if let Err(e) = client.send(&heartbeat.to_string()).await {
                eprintln!("Failed to send heartbeat: {}", e);
                break;
            }
        } else {
            break;
        }
    }
}

fn build_history_record(
    id: String,
    from_device_id: &str,
    from_device_name: &str,
    payload: &serde_json::Value,
    via: &str,
    delivery_mode: &str,
    received_at: i64,
) -> Option<NewHistoryRecord> {
    let payload_type = payload
        .get("type")
        .and_then(|v| v.as_str())
        .unwrap_or_default();
    let sent_at = payload
        .get("timestamp")
        .and_then(|v| v.as_i64())
        .unwrap_or(received_at);

    match payload_type {
        "TEXT_INPUT" => payload
            .get("text")
            .and_then(|v| v.as_str())
            .map(|text| NewHistoryRecord {
                id,
                from_device_id: from_device_id.to_string(),
                from_device_name: from_device_name.to_string(),
                content_type: "text".to_string(),
                content: text.to_string(),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: delivery_mode.to_string(),
            }),
        "CLIPBOARD_IMAGE" => {
            let file_name = payload
                .get("file_name")
                .and_then(|v| v.as_str())
                .unwrap_or("clipboard-image");
            Some(NewHistoryRecord {
                id,
                from_device_id: from_device_id.to_string(),
                from_device_name: from_device_name.to_string(),
                content_type: "image".to_string(),
                content: format!("[图片] {}", file_name),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: delivery_mode.to_string(),
            })
        }
        "NOTIFICATION_FORWARD" => {
            let app_name = payload
                .get("app_name")
                .and_then(|v| v.as_str())
                .unwrap_or("通知");
            let title = payload
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let text = payload
                .get("text")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let summary = [app_name, title, text]
                .into_iter()
                .filter(|value| !value.is_empty())
                .collect::<Vec<_>>()
                .join(" | ");

            Some(NewHistoryRecord {
                id,
                from_device_id: from_device_id.to_string(),
                from_device_name: from_device_name.to_string(),
                content_type: "notification".to_string(),
                content: format!("[通知] {}", summary),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: delivery_mode.to_string(),
            })
        }
        _ => None,
    }
}

fn emit_history_record(app_handle: &AppHandle, record: NewHistoryRecord) {
    match history::record_message(record) {
        Ok((stored, inserted)) => {
            if inserted {
                app_handle.emit("history_recorded", &stored).unwrap_or(());
            }
        }
        Err(error) => {
            eprintln!("failed to persist history record: {}", error);
        }
    }
}

async fn send_relay_ack(
    my_device_id: &str,
    target_device_id: &str,
    content_type: &str,
    success: bool,
    server_message_id: Option<&str>,
) {
    let ack_payload = serde_json::json!({
        "type": "INPUT_ACK",
        "success": success,
        "content_type": content_type,
        "timestamp": chrono::Utc::now().timestamp_millis(),
        "server_message_id": server_message_id
    });
    let relay_ack = serde_json::json!({
        "type": "RELAY_MESSAGE",
        "from_device_id": my_device_id,
        "to_device_id": target_device_id,
        "payload": ack_payload
    });

    let ws_client = websocket::get_ws_client();
    let client = ws_client.lock().await;
    if let Err(e) = client.send(&relay_ack.to_string()).await {
        eprintln!("Failed to send INPUT_ACK relay: {}", e);
    }
}
