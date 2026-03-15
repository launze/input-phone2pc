mod network;
mod input;
mod pairing;
mod storage;
mod crypto;

use storage::config::AppConfig;
use std::sync::Arc;
use tokio::sync::Mutex;

// 全局服务器连接状态
struct ServerState {
    connected: bool,
    error: Option<String>,
}

lazy_static::lazy_static! {
    static ref SERVER_STATE: Arc<Mutex<ServerState>> = Arc::new(Mutex::new(ServerState {
        connected: false,
        error: None,
    }));
}

#[tauri::command]
fn get_config() -> AppConfig {
    AppConfig::load()
}

#[tauri::command]
fn set_server_url(url: String) -> Result<(), String> {
    let mut config = AppConfig::load();
    config.set_server_url(url);
    config.save().map_err(|e| e.to_string())
}

#[tauri::command]
fn set_server_mode(enabled: bool) -> Result<(), String> {
    let mut config = AppConfig::load();
    config.set_server_mode(enabled);
    config.save().map_err(|e| e.to_string())
}

#[tauri::command]
async fn connect_server(url: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    println!("🔌 正在连接服务器: {}", url);

    // 断开之前的连接
    {
        let ws_client = network::websocket::get_ws_client();
        let mut client = ws_client.lock().await;
        client.disconnect().await;
    }

    // 加载设备配置
    let config = storage::config::AppConfig::load();

    // 连接并注册
    network::connect_and_register(&url, &config.device_id, &config.device_name, Some(app_handle)).await;

    // 检查连接状态
    let ws_client = network::websocket::get_ws_client();
    let client = ws_client.lock().await;
    if client.is_connected().await {
        let mut state = SERVER_STATE.lock().await;
        state.connected = true;
        state.error = None;
        Ok(())
    } else {
        let mut state = SERVER_STATE.lock().await;
        state.connected = false;
        state.error = Some("连接失败".to_string());
        Err("连接失败".to_string())
    }
}

#[tauri::command]
async fn disconnect_server() -> Result<(), String> {
    println!("🔌 断开服务器连接");
    
    // 断开 WebSocket 连接
    let ws_client = network::websocket::get_ws_client();
    let mut client = ws_client.lock().await;
    client.disconnect().await;
    
    let mut state = SERVER_STATE.lock().await;
    state.connected = false;
    state.error = None;
    
    Ok(())
}

#[derive(serde::Serialize)]
struct ServerStatus {
    connected: bool,
    error: Option<String>,
}

#[tauri::command]
async fn get_server_status() -> ServerStatus {
    // 检查 WebSocket 实际连接状态
    let ws_client = network::websocket::get_ws_client();
    let client = ws_client.lock().await;
    let ws_connected = client.is_connected().await;
    
    let mut state = SERVER_STATE.lock().await;
    
    // 同步状态
    if state.connected != ws_connected {
        state.connected = ws_connected;
        if !ws_connected {
            state.error = Some("连接已断开".to_string());
        }
    }
    
    ServerStatus {
        connected: state.connected,
        error: state.error.clone(),
    }
}

#[tauri::command]
async fn generate_encryption_key() -> Result<String, String> {
    let key = crate::crypto::EncryptionKey::generate();
    Ok(key.to_hex())
}

#[tauri::command]
async fn set_encryption_key(key: String) -> Result<(), String> {
    use crate::crypto::EncryptionKey;
    let encryption_key = EncryptionKey::from_hex(&key)
        .map_err(|e| e.to_string())?;
    
    // 设置到网络模块
    use crate::network::connection;
    connection::set_encryption_key(Some(encryption_key)).await;
    
    println!("设置加密密钥成功");
    Ok(())
}

#[tauri::command]
async fn send_encrypted_message(_message: String, to_device_id: String) -> Result<(), String> {
    println!("发送加密消息到设备: {}", to_device_id);
    Ok(())
}

#[tauri::command]
async fn unpair_device(device_id: String) -> Result<(), String> {
    let config = storage::config::AppConfig::load();
    let my_device_id = config.device_id.clone();

    // 发送 UNPAIR_REQUEST 到服务器
    let ws_client = network::websocket::get_ws_client();
    let client = ws_client.lock().await;
    if client.is_connected().await {
        let msg = serde_json::json!({
            "type": "UNPAIR_REQUEST",
            "from_device_id": my_device_id,
            "to_device_id": device_id
        });
        client.send(&msg.to_string()).await.map_err(|e| e.to_string())?;
    }

    // 从本地配置移除
    let mut config = storage::config::AppConfig::load();
    config.remove_paired_device(&device_id);
    config.save().map_err(|e| e.to_string())?;

    println!("📱 已取消配对: {}", device_id);
    Ok(())
}

#[tauri::command]
fn generate_pairing_qr() -> Result<String, String> {
    let config = AppConfig::load();

    let local_ip = network::discovery::get_local_ip().unwrap_or_default();
    let qr_data = serde_json::json!({
        "type": "VOICEINPUT_PAIR",
        "server_url": config.server_url,
        "device_id": config.device_id,
        "device_name": config.device_name,
        "local_ip": local_ip,
        "local_port": 58889
    });
    let qr_json = serde_json::to_string(&qr_data).map_err(|e| e.to_string())?;

    // Generate QR code
    use qrcode::QrCode;
    use image::Luma;
    let code = QrCode::new(qr_json.as_bytes()).map_err(|e| e.to_string())?;
    let img = code.render::<Luma<u8>>()
        .quiet_zone(true)
        .min_dimensions(256, 256)
        .build();

    // Encode to PNG bytes
    let mut png_bytes: Vec<u8> = Vec::new();
    let mut cursor = std::io::Cursor::new(&mut png_bytes);
    image::DynamicImage::ImageLuma8(img)
        .write_to(&mut cursor, image::ImageFormat::Png)
        .map_err(|e| e.to_string())?;

    // Base64 encode
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(&png_bytes);
    Ok(format!("data:image/png;base64,{}", b64))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            get_config,
            set_server_url,
            set_server_mode,
            connect_server,
            disconnect_server,
            get_server_status,
            generate_encryption_key,
            set_encryption_key,
            send_encrypted_message,
            generate_pairing_qr,
            unpair_device
        ])
        .setup(|app| {
            let app_handle = app.handle().clone();
            
            // 启动网络服务
            tauri::async_runtime::spawn(async move {
                if let Err(e) = network::start_services(app_handle).await {
                    eprintln!("网络服务启动失败: {}", e);
                }
            });
            
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn main() {
    // 初始化 Rustls 加密提供者
    use rustls::crypto::ring::default_provider;
    let _ = default_provider().install_default();
    
    run();
}
