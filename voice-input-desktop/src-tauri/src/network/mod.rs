pub mod connection;
pub mod discovery;
pub mod protocol;
pub mod websocket;

use std::error::Error;
use tauri::{AppHandle, Emitter};

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
                let success = msg.get("success").and_then(|v| v.as_bool()).unwrap_or(false);
                if success {
                    let session_id = msg.get("session_id").and_then(|v| v.as_str()).unwrap_or("");
                    println!("Server register success: {}", session_id);
                } else {
                    let message = msg.get("message").and_then(|v| v.as_str()).unwrap_or("unknown");
                    eprintln!("Server register failed: {}", message);
                }
            }
            "RELAY_MESSAGE" => {
                let from_id = msg
                    .get("from_device_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("unknown");
                if let Some(payload) = msg.get("payload") {
                    let payload_type = payload.get("type").and_then(|v| v.as_str()).unwrap_or("");
                    if payload_type == "TEXT_INPUT" {
                        if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
                            crate::input::simulate_text_input(text);

                            if let Some(ref handle) = app_handle {
                                let text_payload = serde_json::json!({
                                    "text": text,
                                    "from_device_id": from_id,
                                    "via": "server",
                                    "timestamp": chrono::Utc::now().timestamp_millis()
                                });
                                handle.emit("text_received", &text_payload).unwrap_or(());
                            }

                            let ack_payload = serde_json::json!({
                                "type": "INPUT_ACK",
                                "success": true,
                                "timestamp": chrono::Utc::now().timestamp_millis()
                            });
                            let relay_ack = serde_json::json!({
                                "type": "RELAY_MESSAGE",
                                "from_device_id": my_device_id,
                                "to_device_id": from_id,
                                "payload": ack_payload
                            });

                            let ws_client = websocket::get_ws_client();
                            let client = ws_client.lock().await;
                            if let Err(e) = client.send(&relay_ack.to_string()).await {
                                eprintln!("Failed to send INPUT_ACK relay: {}", e);
                            }
                        }
                    }
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
                let success = msg.get("success").and_then(|v| v.as_bool()).unwrap_or(false);
                let from_id = msg.get("from_device_id").and_then(|v| v.as_str()).unwrap_or("");
                let from_name = msg
                    .get("from_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown");
                let to_id = msg.get("to_device_id").and_then(|v| v.as_str()).unwrap_or("");
                let to_name = msg
                    .get("to_device_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown");

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
                    }
                } else {
                    let message = msg.get("message").and_then(|v| v.as_str()).unwrap_or("unknown");
                    eprintln!("SERVER_PAIR_RESPONSE failed: {}", message);
                }
            }
            "PAIRED_DEVICE_ONLINE" => {
                let paired_id = msg.get("device_id").and_then(|v| v.as_str()).unwrap_or("?");
                let paired_name = msg.get("device_name").and_then(|v| v.as_str()).unwrap_or("?");
                let paired_type = msg.get("device_type").and_then(|v| v.as_str()).unwrap_or("?");

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
                let offline_name = msg.get("device_name").and_then(|v| v.as_str()).unwrap_or("?");
                let offline_type = msg.get("device_type").and_then(|v| v.as_str()).unwrap_or("?");

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
                let unpaired_name = msg.get("device_name").and_then(|v| v.as_str()).unwrap_or("?");

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
                    handle.emit("device_disconnected", &disc_payload).unwrap_or(());
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
