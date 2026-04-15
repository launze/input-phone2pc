#![cfg_attr(
    all(target_os = "windows", not(debug_assertions)),
    windows_subsystem = "windows"
)]

mod crypto;
mod input;
mod network;
mod pairing;
mod reporting;
mod storage;

use std::sync::Arc;
use std::{fs, path::PathBuf};
use storage::config::{AppConfig, OpenAiReportConfig};
use storage::history::{self, HistoryPage, HistoryQuery, HistoryRecord};
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
    network::connect_and_register(
        &url,
        &config.device_id,
        &config.device_name,
        Some(app_handle),
    )
    .await;

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

#[derive(serde::Serialize)]
struct HistoryExportPayload {
    filename: String,
    saved_path: String,
}

#[derive(serde::Serialize)]
struct OpenAiConfigSavePayload {
    success: bool,
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
fn get_message_history(
    start_at: Option<i64>,
    end_at: Option<i64>,
    limit: Option<usize>,
) -> Result<Vec<HistoryRecord>, String> {
    history::list_records(HistoryQuery {
        start_at,
        end_at,
        limit,
        before_received_at: None,
        before_id: None,
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn get_message_history_page(
    start_at: Option<i64>,
    end_at: Option<i64>,
    limit: Option<usize>,
    before_received_at: Option<i64>,
    before_id: Option<String>,
) -> Result<HistoryPage, String> {
    history::list_record_page(HistoryQuery {
        start_at,
        end_at,
        limit,
        before_received_at,
        before_id,
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn clear_message_history() -> Result<(), String> {
    history::clear_records().map_err(|e| e.to_string())
}

#[tauri::command]
fn export_message_history(
    start_at: Option<i64>,
    end_at: Option<i64>,
    label: Option<String>,
) -> Result<HistoryExportPayload, String> {
    let csv = history::export_csv(HistoryQuery {
        start_at,
        end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
    })
    .map_err(|e| e.to_string())?;

    let suffix = label
        .unwrap_or_else(|| "custom".to_string())
        .replace(' ', "_")
        .replace(':', "-");
    let filename = format!(
        "voice-input-history-{}-{}.csv",
        suffix,
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    );
    let saved_path = save_history_export(&filename, &csv)?;

    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

#[tauri::command]
fn export_openai_report_word(
    period: String,
    title: String,
    start_at: i64,
    end_at: i64,
    content: String,
) -> Result<HistoryExportPayload, String> {
    let bytes = reporting::export_report_to_word(&period, &title, start_at, end_at, &content)?;
    let suffix = match period.as_str() {
        "week" => "week-report",
        "month" => "month-report",
        _ => "report",
    };
    let filename = format!(
        "voice-input-{}-{}.docx",
        suffix,
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    );
    let saved_path = save_export_bytes(&filename, &bytes)?;

    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

#[tauri::command]
fn save_openai_report_config(openai: OpenAiReportConfig) -> Result<OpenAiConfigSavePayload, String> {
    let mut config = AppConfig::load();
    config.set_openai_config(openai);
    config.save().map_err(|e| e.to_string())?;
    Ok(OpenAiConfigSavePayload { success: true })
}

#[tauri::command]
async fn generate_openai_report(
    period: String,
    start_at: i64,
    end_at: i64,
    request_id: Option<String>,
    app_handle: tauri::AppHandle,
) -> Result<reporting::GeneratedReport, String> {
    let config = AppConfig::load();
    let stream_handle = request_id.map(|request_id| reporting::ReportStreamHandle {
        app_handle,
        request_id,
    });
    reporting::generate_openai_report(config, &period, start_at, end_at, stream_handle).await
}

fn save_history_export(filename: &str, csv: &str) -> Result<String, String> {
    let content = format!("\u{feff}{csv}");
    save_export_bytes(filename, content.as_bytes())
}

fn save_export_bytes(filename: &str, bytes: &[u8]) -> Result<String, String> {
    let export_dir = resolve_history_export_dir()?;
    fs::create_dir_all(&export_dir).map_err(|e| e.to_string())?;

    let output_path = export_dir.join(filename);
    fs::write(&output_path, bytes).map_err(|e| e.to_string())?;

    Ok(output_path.display().to_string())
}

fn resolve_history_export_dir() -> Result<PathBuf, String> {
    dirs::download_dir()
        .or_else(dirs::document_dir)
        .or_else(dirs::desktop_dir)
        .or_else(|| std::env::current_dir().ok())
        .ok_or_else(|| "无法确定导出目录".to_string())
}

#[tauri::command]
async fn generate_encryption_key() -> Result<String, String> {
    let key = crate::crypto::EncryptionKey::generate();
    Ok(key.to_hex())
}

#[tauri::command]
async fn set_encryption_key(key: String) -> Result<(), String> {
    use crate::crypto::EncryptionKey;
    let encryption_key = EncryptionKey::from_hex(&key).map_err(|e| e.to_string())?;

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
        client
            .send(&msg.to_string())
            .await
            .map_err(|e| e.to_string())?;
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
    let qr_json = build_pairing_payload()?;

    // Generate QR code
    use image::Luma;
    use qrcode::QrCode;
    let code = QrCode::new(qr_json.as_bytes()).map_err(|e| e.to_string())?;
    let img = code
        .render::<Luma<u8>>()
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

#[tauri::command]
fn get_pairing_payload() -> Result<String, String> {
    build_pairing_payload()
}

fn build_pairing_payload() -> Result<String, String> {
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
    serde_json::to_string(&qr_data).map_err(|e| e.to_string())
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
            get_message_history,
            get_message_history_page,
            clear_message_history,
            export_message_history,
            export_openai_report_word,
            save_openai_report_config,
            generate_openai_report,
            generate_encryption_key,
            set_encryption_key,
            send_encrypted_message,
            generate_pairing_qr,
            get_pairing_payload,
            unpair_device
        ])
        .setup(|app| {
            let app_handle = app.handle().clone();

            if let Err(error) = history::init() {
                eprintln!("历史数据库初始化失败: {}", error);
            }

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
