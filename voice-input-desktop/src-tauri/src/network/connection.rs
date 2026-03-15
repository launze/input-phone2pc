use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tauri::{AppHandle, Emitter};

use crate::crypto::{decrypt_string, EncryptionKey};
use crate::input;
use std::error::Error;
use std::sync::Arc;
use tokio::sync::Mutex;

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
    // Only emit connected after PAIR_REQUEST succeeds.
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
                app_handle.emit("device_disconnected", &disc_payload).unwrap_or(());
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
                        .unwrap_or("Android");

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
                    app_handle.emit("device_connected", &conn_payload).unwrap_or(());
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
                                if let Ok(decrypted_json) = decrypt_string(ciphertext, nonce, &key) {
                                    if let Ok(decrypted_message) =
                                        serde_json::from_str::<serde_json::Value>(&decrypted_json)
                                    {
                                        if let Some(decrypted_type) =
                                            decrypted_message.get("type").and_then(|v| v.as_str())
                                        {
                                            if decrypted_type == "TEXT_INPUT" {
                                                if let Some(text) =
                                                    decrypted_message.get("text").and_then(|v| v.as_str())
                                                {
                                                    input::simulate_text_input(text);

                                                    let text_payload = serde_json::json!({
                                                        "text": text,
                                                        "via": "lan_encrypted",
                                                        "timestamp": chrono::Utc::now().timestamp_millis()
                                                    });
                                                    app_handle
                                                        .emit("text_received", &text_payload)
                                                        .unwrap_or(());

                                                    let response = serde_json::json!({
                                                        "type": "INPUT_ACK",
                                                        "success": true
                                                    });
                                                    writer.write_all(response.to_string().as_bytes()).await?;
                                                    writer.write_all(b"\n").await?;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "TEXT_INPUT" => {
                    if let Some(text) = message.get("text").and_then(|v| v.as_str()) {
                        input::simulate_text_input(text);

                        let text_payload = serde_json::json!({
                            "text": text,
                            "via": "lan",
                            "timestamp": chrono::Utc::now().timestamp_millis()
                        });
                        app_handle.emit("text_received", &text_payload).unwrap_or(());

                        let response = serde_json::json!({
                            "type": "INPUT_ACK",
                            "success": true
                        });
                        writer.write_all(response.to_string().as_bytes()).await?;
                        writer.write_all(b"\n").await?;
                    }
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
