pub mod connection;
pub mod discovery;
pub mod protocol;
pub mod websocket;

use crate::storage::{
    config::AppConfig,
    history::{self, NewHistoryRecord},
};
use base64::Engine;
use std::error::Error;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;
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
                "version": env!("CARGO_PKG_VERSION")
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
                    println!("服务器注册成功: {}", session_id);
                } else {
                    let message = msg
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("未知错误");
                    eprintln!("服务器注册失败: {}", message);
                }
            }
            "RELAY_MESSAGE" => {
                let from_id = msg
                    .get("from_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("未知设备");
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
                                let input_result = handle_text_input_by_mode(
                                    text,
                                    payload
                                        .get("press_enter")
                                        .and_then(|v| v.as_bool())
                                        .unwrap_or(false),
                                    !should_inject,
                                );
                                let history_delivery_mode =
                                    history_delivery_mode_for_text_input(delivery_mode, &input_result.mode);

                                if let Some(ref handle) = app_handle {
                                    if let Some(record) = build_history_record(
                                        message_id.clone(),
                                        from_id,
                                        from_name,
                                        payload,
                                        "server",
                                        &history_delivery_mode,
                                        received_at,
                                        None,
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
                                        "delivery_mode": history_delivery_mode
                                    });
                                    handle.emit("text_received", &text_payload).unwrap_or(());
                                    handle.emit("input_result", &input_result).unwrap_or(());
                                }

                                send_relay_ack(
                                    &my_device_id,
                                    from_id,
                                    "text",
                                    input_result.success,
                                    Some(message_id.as_str()),
                                    payload.get("client_message_id").and_then(|value| value.as_str()),
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
                                    None,
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
                                payload.get("client_message_id").and_then(|value| value.as_str()),
                            )
                            .await;
                        }
                        "CLIPBOARD_FILE" => {
                            let saved_file = save_clipboard_file(payload);
                            let success = saved_file.is_ok();

                            if let Some(ref handle) = app_handle {
                                if let Some(record) = build_history_record(
                                    message_id.clone(),
                                    from_id,
                                    from_name,
                                    payload,
                                    "server",
                                    delivery_mode,
                                    received_at,
                                    saved_file.as_ref().ok(),
                                ) {
                                    emit_history_record(handle, record);
                                }

                                let file_payload = serde_json::json!({
                                    "record_id": message_id,
                                    "from_device_id": from_id,
                                    "from_device_name": from_name,
                                    "via": "server",
                                    "timestamp": received_at,
                                    "sent_at": payload.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(received_at),
                                    "delivery_mode": delivery_mode,
                                    "saved_path": saved_file.as_ref().ok().map(|path| path.display().to_string())
                                });
                                handle.emit("file_received", &file_payload).unwrap_or(());
                            }

                            if let Ok(path) = &saved_file {
                                if let Some(parent) = path.parent() {
                                    if let Err(error) = open_folder(parent) {
                                        eprintln!("failed to open received file folder: {}", error);
                                    }
                                }
                            } else if let Err(error) = &saved_file {
                                eprintln!("failed to save received file: {}", error);
                            }

                            send_relay_ack(
                                &my_device_id,
                                from_id,
                                "file",
                                success,
                                Some(message_id.as_str()),
                                payload.get("client_message_id").and_then(|value| value.as_str()),
                            )
                            .await;
                        }
                        "NOTIFICATION_INPUT" => {
                            if payload
                                .get("copy_to_clipboard")
                                .and_then(|v| v.as_bool())
                                .unwrap_or(false)
                            {
                                let app_name = payload
                                    .get("app_name")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("未知应用");
                                let title = payload.get("title").and_then(|v| v.as_str()).unwrap_or("");
                                let text = payload.get("text").and_then(|v| v.as_str()).unwrap_or("");
                                let content = format_notification_content(app_name, title, text);
                                let _ = crate::input::copy_text_to_clipboard(&content);
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
                                    None,
                                ) {
                                    emit_history_record(handle, record);
                                }

                                let notification_payload = serde_json::json!({
                                    "record_id": message_id,
                                    "from_device_id": from_id,
                                    "from_device_name": from_name,
                                    "via": "server",
                                    "timestamp": received_at,
                                    "sent_at": payload.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(received_at),
                                    "delivery_mode": delivery_mode,
                                    "app_name": payload.get("app_name").and_then(|v| v.as_str()).unwrap_or(""),
                                    "app_package": payload.get("app_package").and_then(|v| v.as_str()).unwrap_or(""),
                                    "title": payload.get("title").and_then(|v| v.as_str()).unwrap_or(""),
                                    "text": payload.get("text").and_then(|v| v.as_str()).unwrap_or(""),
                                    "content": format_notification_content(
                                        payload.get("app_name").and_then(|v| v.as_str()).unwrap_or(""),
                                        payload.get("title").and_then(|v| v.as_str()).unwrap_or(""),
                                        payload.get("text").and_then(|v| v.as_str()).unwrap_or("")
                                    ),
                                    "forward_mode": payload.get("forward_mode").and_then(|v| v.as_str()),
                                    "silent": payload.get("silent").and_then(|v| v.as_bool()).unwrap_or(false),
                                    "copy_to_clipboard": payload.get("copy_to_clipboard").and_then(|v| v.as_bool()).unwrap_or(false)
                                });
                                handle
                                    .emit("notification_received", &notification_payload)
                                    .unwrap_or(());
                            }

                            send_relay_ack(
                                &my_device_id,
                                from_id,
                                "notification",
                                true,
                                Some(message_id.as_str()),
                                payload.get("client_message_id").and_then(|value| value.as_str()),
                            )
                            .await;
                        }
                        "AI_ASSISTANT_REQUEST" => {
                            if let Some(ref handle) = app_handle {
                                let request_payload = serde_json::json!({
                                    "request_id": payload.get("request_id").and_then(|v| v.as_str()).unwrap_or(message_id.as_str()),
                                    "question": payload.get("question").and_then(|v| v.as_str()).unwrap_or(""),
                                    "session_id": payload.get("session_id").and_then(|v| v.as_str()),
                                    "filters": payload.get("filters").cloned(),
                                    "from_device_id": from_id,
                                    "from_device_name": from_name,
                                    "via": "server",
                                    "timestamp": received_at
                                });
                                handle
                                    .emit("remote_ai_assistant_request", &request_payload)
                                    .unwrap_or(());
                            }
                        }
                        "AI_ASSISTANT_CANCEL" => {
                            if let Some(ref handle) = app_handle {
                                let cancel_payload = serde_json::json!({
                                    "request_id": payload.get("request_id").and_then(|v| v.as_str()).unwrap_or(message_id.as_str()),
                                    "from_device_id": from_id,
                                    "from_device_name": from_name,
                                    "via": "server",
                                    "timestamp": received_at
                                });
                                handle
                                    .emit("remote_ai_assistant_cancel", &cancel_payload)
                                    .unwrap_or(());
                            }
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
            "HEARTBEAT" => {
                // Ignore server heartbeat response
            }
            "DEVICE_LIST_RESPONSE" => {
                if let Some(devices) = msg.get("devices").and_then(|v| v.as_array()) {
                    println!("服务器返回在线设备数量: {}", devices.len());
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
                    .unwrap_or("未知设备");
                let to_id = msg
                    .get("to_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let to_name = msg
                    .get("to_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("未知设备");

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
                        .unwrap_or("未知错误");
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

                // 以服务器推送为准，持久化配对设备信息。
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
                eprintln!("服务器错误 [{}] {}", code, message);
            }
            _ => {
                // 忽略未知消息类型。
            }
        }
    }

    println!("服务器消息循环已结束");
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
    saved_path: Option<&PathBuf>,
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
                metadata: None,
            }),
        "CLIPBOARD_IMAGE" => {
            let file_name = payload
                .get("file_name")
                .and_then(|v| v.as_str())
                .unwrap_or("clipboard-image");
            let metadata = payload.get("data").and_then(|v| v.as_str()).map(|data| {
                serde_json::json!({
                    "mime_type": payload.get("mime_type").and_then(|v| v.as_str()).unwrap_or(""),
                    "file_name": file_name,
                    "data": data,
                    "width": payload.get("width").and_then(|v| v.as_i64()),
                    "height": payload.get("height").and_then(|v| v.as_i64()),
                    "size": payload.get("size").and_then(|v| v.as_i64())
                })
                .to_string()
            });
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
                metadata,
            })
        }
        "CLIPBOARD_FILE" => {
            let file_name = payload
                .get("file_name")
                .and_then(|v| v.as_str())
                .unwrap_or("received-file");
            let mut meta = serde_json::json!({
                "mime_type": payload.get("mime_type").and_then(|v| v.as_str()).unwrap_or(""),
                "file_name": file_name,
                "size": payload.get("size").and_then(|v| v.as_i64())
            });
            if let Some(path) = saved_path {
                meta.as_object_mut().unwrap().insert(
                    "saved_path".to_string(),
                    serde_json::Value::String(path.display().to_string()),
                );
            }
            let metadata = meta.to_string();
            Some(NewHistoryRecord {
                id,
                from_device_id: from_device_id.to_string(),
                from_device_name: from_device_name.to_string(),
                content_type: "file".to_string(),
                content: format!("[文件] {}", file_name),
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: delivery_mode.to_string(),
                metadata: Some(metadata),
            })
        }
        "NOTIFICATION_INPUT" => {
            let app_name = payload
                .get("app_name")
                .and_then(|v| v.as_str())
                .unwrap_or("未知应用");
            let app_package = payload
                .get("app_package")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let title = payload.get("title").and_then(|v| v.as_str()).unwrap_or("");
            let text = payload.get("text").and_then(|v| v.as_str()).unwrap_or("");
            let content = format_notification_content(app_name, title, text);
            let metadata = serde_json::json!({
                "app_name": app_name,
                "app_package": app_package,
                "title": title,
                "text": text,
                "forward_mode": payload.get("forward_mode").and_then(|v| v.as_str()),
                "silent": payload.get("silent").and_then(|v| v.as_bool()),
                "copy_to_clipboard": payload.get("copy_to_clipboard").and_then(|v| v.as_bool()),
                "notification_key": payload.get("notification_key").and_then(|v| v.as_str()),
                "channel_id": payload.get("channel_id").and_then(|v| v.as_str()),
                "group_key": payload.get("group_key").and_then(|v| v.as_str()),
                "category": payload.get("category").and_then(|v| v.as_str()),
                "sub_text": payload.get("sub_text").and_then(|v| v.as_str()),
                "big_text": payload.get("big_text").and_then(|v| v.as_str()),
                "conversation_title": payload.get("conversation_title").and_then(|v| v.as_str()),
                "post_time": payload.get("post_time").and_then(|v| v.as_i64()),
                "is_ongoing": payload.get("is_ongoing").and_then(|v| v.as_bool()),
                "is_clearable": payload.get("is_clearable").and_then(|v| v.as_bool()),
                "importance": payload.get("importance").and_then(|v| v.as_i64()),
                "icon": payload.get("icon").and_then(|v| v.as_str())
            })
            .to_string();
            Some(NewHistoryRecord {
                id,
                from_device_id: from_device_id.to_string(),
                from_device_name: from_device_name.to_string(),
                content_type: "notification".to_string(),
                content,
                sent_at,
                received_at,
                via: via.to_string(),
                delivery_mode: delivery_mode.to_string(),
                metadata: Some(metadata),
            })
        }
        _ => None,
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

fn save_clipboard_file(payload: &serde_json::Value) -> Result<PathBuf, Box<dyn Error + Send + Sync>> {
    let data = payload
        .get("data")
        .and_then(|v| v.as_str())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing file data"))?;
    let file_name = payload
        .get("file_name")
        .and_then(|v| v.as_str())
        .unwrap_or("received-file");
    let bytes = base64::engine::general_purpose::STANDARD.decode(data)?;

    let target_dir = resolve_received_files_dir()?;
    fs::create_dir_all(&target_dir)?;

    let target_path = unique_path(&target_dir, &sanitize_file_name(file_name));
    fs::write(&target_path, bytes)?;
    Ok(target_path)
}

fn resolve_received_files_dir() -> Result<PathBuf, Box<dyn Error + Send + Sync>> {
    let base = dirs::download_dir()
        .or_else(dirs::document_dir)
        .or_else(dirs::desktop_dir)
        .or_else(|| std::env::current_dir().ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "cannot resolve user file directory"))?;
    Ok(base.join("VoiceInput"))
}

fn sanitize_file_name(file_name: &str) -> String {
    let sanitized = file_name
        .chars()
        .map(|ch| match ch {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            ch if ch.is_control() => '_',
            ch => ch,
        })
        .collect::<String>()
        .trim()
        .trim_matches('.')
        .to_string();

    if sanitized.is_empty() {
        "received-file".to_string()
    } else {
        sanitized
    }
}

fn unique_path(dir: &Path, file_name: &str) -> PathBuf {
    let mut candidate = dir.join(file_name);
    if !candidate.exists() {
        return candidate;
    }

    let path = Path::new(file_name);
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("received-file");
    let extension = path.extension().and_then(|value| value.to_str());

    for index in 1.. {
        let next_name = match extension {
            Some(extension) if !extension.is_empty() => format!("{}_{}.{}", stem, index, extension),
            _ => format!("{}_{}", stem, index),
        };
        candidate = dir.join(next_name);
        if !candidate.exists() {
            return candidate;
        }
    }

    unreachable!()
}

fn open_folder(path: &Path) -> Result<(), Box<dyn Error>> {
    #[cfg(target_os = "windows")]
    {
        Command::new("explorer").arg(path).spawn()?;
    }

    #[cfg(target_os = "macos")]
    {
        Command::new("open").arg(path).spawn()?;
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    {
        Command::new("xdg-open").arg(path).spawn()?;
    }

    Ok(())
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
        "clipboard" => match crate::input::copy_text_to_clipboard(text) {
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
            crate::input::simulate_text_input(text);
            if press_enter {
                let _ = crate::input::simulate_enter_key();
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

async fn send_relay_ack(
    my_device_id: &str,
    target_device_id: &str,
    content_type: &str,
    success: bool,
    server_message_id: Option<&str>,
    client_message_id: Option<&str>,
) {
    let ack_payload = serde_json::json!({
        "type": "INPUT_ACK",
        "success": success,
        "content_type": content_type,
        "timestamp": chrono::Utc::now().timestamp_millis(),
        "server_message_id": server_message_id,
        "client_message_id": client_message_id
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
