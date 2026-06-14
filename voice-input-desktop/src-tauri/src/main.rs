#![cfg_attr(
    all(target_os = "windows", not(debug_assertions)),
    windows_subsystem = "windows"
)]

mod crypto;
mod input;
mod network;
mod reporting;
mod storage;
mod update;

use std::sync::Arc;
use std::{fs, path::PathBuf};
use storage::ai::{AiExportedFile, AiMessage, AiSession, AiSkill, AiToolCall};
use storage::config::{AppConfig, OpenAiConfig};
use storage::history::{self, HistoryPage, HistoryQuery, HistoryRecord, NewHistoryRecord};
use tauri::Emitter;
use tokio::sync::Mutex;
use uuid::Uuid;

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
fn set_input_mode(mode: String) -> Result<(), String> {
    let mut config = AppConfig::load();
    config.set_input_mode(mode);
    config.save().map_err(|e| e.to_string())
}

#[tauri::command]
fn get_app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
async fn check_app_update() -> Result<update::UpdateInfo, String> {
    update::check_update().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn download_and_open_app_update(info: update::UpdateInfo) -> Result<String, String> {
    update::download_and_open_update(info)
        .await
        .map_err(|e| e.to_string())
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

#[derive(Debug, Clone, serde::Serialize)]
struct HistoryExportPayload {
    filename: String,
    saved_path: String,
}

#[derive(serde::Serialize)]
struct AiModelConfigSavePayload {
    success: bool,
}

#[derive(Debug, Clone, serde::Deserialize)]
struct ImportAiSkillsPayload {
    skills: Vec<AiSkill>,
}

#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
struct AiAssistantFilters {
    start_at: Option<i64>,
    end_at: Option<i64>,
    search: Option<String>,
    record_ids: Option<Vec<String>>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
    limit: Option<usize>,
}

#[derive(Debug, serde::Serialize)]
struct AiAssistantRunResult {
    session_id: String,
    user_message_id: String,
    assistant_message_id: String,
    tool_call_id: Option<String>,
    record_count: usize,
    content: String,
    exported_file: Option<HistoryExportPayload>,
}

#[derive(Debug, serde::Deserialize)]
struct AiAssistantPlan {
    skill_id: Option<String>,
    tools: Vec<AiAssistantToolPlan>,
    #[serde(default)]
    final_answer_ready: bool,
}

#[derive(Debug, serde::Deserialize)]
struct AiAssistantToolPlan {
    name: String,
    #[serde(default)]
    arguments: AiAssistantToolArguments,
}

#[derive(Debug, Default, serde::Deserialize)]
struct AiAssistantToolArguments {
    record_id: Option<String>,
    record_ids: Option<Vec<String>>,
    title: Option<String>,
    format: Option<String>,
    selected_skill: Option<String>,
    source_filters: Option<serde_json::Value>,
    start_at: Option<i64>,
    end_at: Option<i64>,
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
    limit: Option<usize>,
}

struct AiToolExecutionState {
    records: Vec<HistoryRecord>,
    tool_call_ids: Vec<String>,
    tool_summaries: Vec<String>,
    observations: Vec<String>,
    export_answer_requested: bool,
    export_answer_title: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize)]
struct AiAssistantEventPayload {
    request_id: String,
    event: String,
    session_id: String,
    tool_call_id: Option<String>,
    tool_name: Option<String>,
    message: Option<String>,
    data: Option<serde_json::Value>,
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
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
) -> Result<Vec<HistoryRecord>, String> {
    history::list_records(HistoryQuery {
        ids: None,
        start_at,
        end_at,
        limit,
        before_received_at: None,
        before_id: None,
        search,
        content_type,
        via,
        from_device,
        source_app,
        delivery_status,
        favorite,
        pinned,
        tag,
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
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
) -> Result<HistoryPage, String> {
    history::list_record_page(HistoryQuery {
        ids: None,
        start_at,
        end_at,
        limit,
        before_received_at,
        before_id,
        search,
        content_type,
        via,
        from_device,
        source_app,
        delivery_status,
        favorite,
        pinned,
        tag,
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn clear_message_history() -> Result<(), String> {
    history::clear_records().map_err(|e| e.to_string())
}

#[tauri::command]
fn delete_message_history_records(
    start_at: Option<i64>,
    end_at: Option<i64>,
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
) -> Result<usize, String> {
    history::delete_records(HistoryQuery {
        ids: None,
        start_at,
        end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
        search,
        content_type,
        via,
        from_device,
        source_app,
        delivery_status,
        favorite,
        pinned,
        tag,
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn create_desktop_text_record(content: String) -> Result<HistoryRecord, String> {
    let normalized = content.trim();
    if normalized.is_empty() {
        return Err("文字内容不能为空".to_string());
    }

    let config = AppConfig::load();
    let now = chrono::Utc::now().timestamp_millis();
    let record = NewHistoryRecord {
        id: Uuid::new_v4().to_string(),
        from_device_id: config.device_id,
        from_device_name: config.device_name,
        content_type: "text".to_string(),
        content: normalized.to_string(),
        sent_at: now,
        received_at: now,
        via: "desktop".to_string(),
        delivery_mode: "manual".to_string(),
        metadata: None,
    };

    history::record_message(record)
        .map(|(stored, _)| stored)
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn update_message_history_record(id: String, content: String) -> Result<HistoryRecord, String> {
    history::update_record_content(&id, &content).map_err(|e| e.to_string())
}

#[tauri::command]
fn delete_message_history_record(id: String) -> Result<(), String> {
    history::delete_record(&id).map_err(|e| e.to_string())
}

#[tauri::command]
fn delete_message_history_records_by_ids(ids: Vec<String>) -> Result<usize, String> {
    history::delete_records_by_ids(&ids).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_message_history_favorite(id: String, favorite: bool) -> Result<HistoryRecord, String> {
    history::set_record_favorite(&id, favorite).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_message_history_favorite_by_ids(ids: Vec<String>, favorite: bool) -> Result<usize, String> {
    history::set_records_favorite_by_ids(&ids, favorite).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_message_history_pinned(id: String, pinned: bool) -> Result<HistoryRecord, String> {
    history::set_record_pinned(&id, pinned).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_message_history_pinned_by_ids(ids: Vec<String>, pinned: bool) -> Result<usize, String> {
    history::set_records_pinned_by_ids(&ids, pinned).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_message_history_tags(id: String, tags: String) -> Result<HistoryRecord, String> {
    history::set_record_tags(&id, &tags).map_err(|e| e.to_string())
}

#[tauri::command]
fn copy_message_history_image_record(id: String) -> Result<(), String> {
    use base64::Engine;

    let record = history::get_record(&id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "记录不存在".to_string())?;

    if record.content_type != "image" {
        return Err("这条记录不是图片记录".to_string());
    }

    let metadata = record
        .metadata
        .as_deref()
        .ok_or_else(|| "这条图片记录没有保存图片内容，无法复制图片".to_string())?;
    let metadata: serde_json::Value =
        serde_json::from_str(metadata).map_err(|_| "图片记录数据已损坏".to_string())?;
    let image_data = metadata
        .get("data")
        .or_else(|| metadata.get("image_data"))
        .and_then(|value| value.as_str())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| "这条图片记录没有保存图片内容，无法复制图片".to_string())?;
    let image_bytes = base64::engine::general_purpose::STANDARD
        .decode(image_data)
        .map_err(|_| "图片数据解码失败".to_string())?;

    input::copy_image_to_clipboard(&image_bytes).map_err(|e| format!("复制图片失败: {}", e))
}

#[tauri::command]
fn open_history_record_file(id: String) -> Result<(), String> {
    let record = history::get_record(&id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "记录不存在".to_string())?;

    if record.content_type != "file" {
        return Err("这条记录不是文件记录".to_string());
    }

    let metadata: serde_json::Value = record
        .metadata
        .as_deref()
        .ok_or_else(|| "文件记录没有元数据".to_string())?
        .parse()
        .map_err(|_| "文件记录元数据解析失败".to_string())?;

    let saved_path = metadata
        .get("saved_path")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "该文件未保存或路径缺失".to_string())?;

    if !std::path::Path::new(saved_path).exists() {
        return Err(format!("文件不存在：{}", saved_path));
    }

    open::that(saved_path).map_err(|e| format!("打开文件失败: {}", e))
}

#[tauri::command]
fn open_history_record_folder(id: String) -> Result<(), String> {
    let record = history::get_record(&id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "记录不存在".to_string())?;

    if record.content_type != "file" {
        return Err("这条记录不是文件记录".to_string());
    }

    let metadata: serde_json::Value = record
        .metadata
        .as_deref()
        .ok_or_else(|| "文件记录没有元数据".to_string())?
        .parse()
        .map_err(|_| "文件记录元数据解析失败".to_string())?;

    let saved_path = metadata
        .get("saved_path")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "该文件未保存或路径缺失".to_string())?;

    let path = std::path::Path::new(saved_path);
    let folder = if path.is_dir() {
        path.to_path_buf()
    } else {
        path.parent()
            .map(|p| p.to_path_buf())
            .ok_or_else(|| "无法确定文件所在目录".to_string())?
    };

    if !folder.exists() {
        return Err(format!("目录不存在：{}", folder.display()));
    }

    open::that(&folder).map_err(|e| format!("打开目录失败: {}", e))
}

#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    let path = PathBuf::from(path.trim());
    if !path.exists() {
        return Err(format!("路径不存在：{}", path.display()));
    }
    open::that(&path).map_err(|e| format!("打开失败: {}", e))
}

#[tauri::command]
fn open_parent_folder(path: String) -> Result<(), String> {
    let path = PathBuf::from(path.trim());
    let folder = if path.is_dir() {
        path
    } else {
        path.parent()
            .map(|parent| parent.to_path_buf())
            .ok_or_else(|| "无法确定所在目录".to_string())?
    };
    if !folder.exists() {
        return Err(format!("目录不存在：{}", folder.display()));
    }
    open::that(&folder).map_err(|e| format!("打开目录失败: {}", e))
}

#[tauri::command]
fn save_history_image_as(id: String) -> Result<String, String> {
    use base64::Engine;

    let record = history::get_record(&id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "记录不存在".to_string())?;

    if record.content_type != "image" {
        return Err("这条记录不是图片记录".to_string());
    }

    let metadata: serde_json::Value = record
        .metadata
        .as_deref()
        .ok_or_else(|| "图片记录没有元数据".to_string())?
        .parse()
        .map_err(|_| "图片记录元数据解析失败".to_string())?;

    let image_data = metadata
        .get("data")
        .or_else(|| metadata.get("image_data"))
        .and_then(|v| v.as_str())
        .filter(|v| !v.is_empty())
        .ok_or_else(|| "图片记录没有保存图片数据".to_string())?;

    let file_name = metadata
        .get("file_name")
        .and_then(|v| v.as_str())
        .unwrap_or("clipboard-image.png");

    let image_bytes = base64::engine::general_purpose::STANDARD
        .decode(image_data)
        .map_err(|_| "图片数据解码失败".to_string())?;

    // 保存到用户下载目录
    let target_dir = dirs::download_dir()
        .or_else(dirs::document_dir)
        .or_else(dirs::desktop_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    fs::create_dir_all(&target_dir).map_err(|e| e.to_string())?;

    let target_path = target_dir.join(file_name);
    fs::write(&target_path, &image_bytes).map_err(|e| e.to_string())?;

    // 打开所在文件夹
    if let Some(parent) = target_path.parent() {
        let _ = open::that(parent);
    }

    Ok(target_path.display().to_string())
}

#[tauri::command]
fn export_message_history(
    start_at: Option<i64>,
    end_at: Option<i64>,
    label: Option<String>,
    format: Option<String>,
    ids: Option<Vec<String>>,
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
) -> Result<HistoryExportPayload, String> {
    let query = HistoryQuery {
        ids,
        start_at,
        end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
        search,
        content_type,
        via,
        from_device,
        source_app,
        delivery_status,
        favorite,
        pinned,
        tag,
    };
    let format = format
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or("csv")
        .to_ascii_lowercase();
    let (extension, export_scope, bytes) = match format.as_str() {
        "csv" => {
            let bundle = history::export_csv(query).map_err(|e| e.to_string())?;
            (
                "csv",
                bundle.scope,
                format!("\u{feff}{}", bundle.content).into_bytes(),
            )
        }
        "txt" => {
            let bundle = history::export_text(query).map_err(|e| e.to_string())?;
            (
                "txt",
                bundle.scope,
                format!("\u{feff}{}", bundle.content).into_bytes(),
            )
        }
        "md" | "markdown" => {
            let bundle = history::export_markdown(query).map_err(|e| e.to_string())?;
            (
                "md",
                bundle.scope,
                format!("\u{feff}{}", bundle.content).into_bytes(),
            )
        }
        "word" | "docx" => {
            let bundle = history::export_markdown(query).map_err(|e| e.to_string())?;
            let now = chrono::Utc::now().timestamp_millis();
            let start = start_at.unwrap_or(now);
            let end = end_at.unwrap_or(now);
            (
                "docx",
                bundle.scope,
                reporting::export_report_to_word("history", bundle.title, start, end, &bundle.content)?,
            )
        }
        other => return Err(format!("不支持的历史导出格式: {other}")),
    };

    let suffix = label
        .unwrap_or_else(|| "custom".to_string())
        .replace(' ', "_")
        .replace(':', "-");
    let filename = format!(
        "voice-input-{}-{}-{}.{}",
        export_scope,
        suffix,
        chrono::Local::now().format("%Y%m%d-%H%M%S"),
        extension
    );
    let saved_path = save_export_bytes(&filename, &bytes)?;

    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

#[tauri::command]
fn insert_text_to_cursor(content: String) -> Result<(), String> {
    let normalized = content.trim();
    if normalized.is_empty() {
        return Err("文字内容不能为空".to_string());
    }

    input::simulate_text_input(normalized);
    Ok(())
}

#[tauri::command]
fn copy_text_to_clipboard(content: String) -> Result<(), String> {
    let normalized = content.trim();
    if normalized.is_empty() {
        return Err("文字内容不能为空".to_string());
    }

    input::copy_text_to_clipboard(normalized).map_err(|e| format!("复制失败: {}", e))
}

#[tauri::command]
fn export_ai_session_word(session_id: String) -> Result<HistoryExportPayload, String> {
    let session = storage::ai::get_session(&session_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "会话不存在".to_string())?;
    let messages = storage::ai::list_messages(&session_id).map_err(|e| e.to_string())?;
    let tool_calls = storage::ai::list_tool_calls(&session_id).map_err(|e| e.to_string())?;
    let content = format_ai_session_export_content(&session, &messages, &tool_calls);
    let start_at = messages
        .first()
        .map(|message| message.created_at)
        .unwrap_or(session.created_at);
    let end_at = messages
        .last()
        .map(|message| message.created_at)
        .unwrap_or(session.updated_at);
    let bytes = reporting::export_report_to_word("ai_session", &session.title, start_at, end_at, &content)?;
    let filename = format!(
        "voice-input-ai-session-{}.docx",
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    );
    let saved_path = save_export_bytes(&filename, &bytes)?;
    storage::ai::add_exported_file(
        session.id,
        None,
        "session_word".to_string(),
        filename.clone(),
        saved_path.clone(),
    )
    .map_err(|e| e.to_string())?;

    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

fn format_ai_session_export_content(
    session: &AiSession,
    messages: &[AiMessage],
    tool_calls: &[AiToolCall],
) -> String {
    let mut sections = Vec::new();
    sections.push(format!(
        "## 会话信息\n\n- 会话 ID: {}\n- Skill: {}\n- 数据范围: {}\n- 消息数: {}\n- 工具调用数: {}",
        session.id,
        session.selected_skill.as_deref().unwrap_or("由 LLM 自动选择"),
        session.source_filters.as_deref().unwrap_or("未记录"),
        messages.len(),
        tool_calls.len()
    ));

    let message_content = messages
        .iter()
        .map(|message| {
            let role = match message.role.as_str() {
                "user" => "用户",
                "assistant" => "助手",
                other => other,
            };
            let metadata = message
                .metadata
                .as_deref()
                .and_then(|value| serde_json::from_str::<serde_json::Value>(value).ok());
            let mut metadata_lines = Vec::new();
            if let Some(metadata) = metadata.as_ref() {
                if let Some(skill_id) = metadata.get("skill_id").and_then(|value| value.as_str()) {
                    metadata_lines.push(format!("- Skill: {skill_id}"));
                }
                if let Some(record_count) = metadata.get("record_count").and_then(|value| value.as_u64()) {
                    metadata_lines.push(format!("- 引用记录: {record_count} 条"));
                }
                if let Some(record_ids) = metadata.get("record_ids").and_then(|value| value.as_array()) {
                    let ids = record_ids
                        .iter()
                        .filter_map(|value| value.as_str())
                        .take(20)
                        .collect::<Vec<_>>()
                        .join(", ");
                    if !ids.is_empty() {
                        metadata_lines.push(format!("- 引用 ID: {ids}"));
                    }
                }
            }
            let metadata_text = if metadata_lines.is_empty() {
                String::new()
            } else {
                format!("\n\n{}", metadata_lines.join("\n"))
            };
            format!("## {role}\n\n{}{}", message.content, metadata_text)
        })
        .collect::<Vec<_>>()
        .join("\n\n");
    sections.push(message_content);

    if !tool_calls.is_empty() {
        let tool_content = tool_calls
            .iter()
            .map(format_ai_tool_call_for_export)
            .collect::<Vec<_>>()
            .join("\n\n");
        sections.push(format!("## 工具调用\n\n{tool_content}"));
    }

    sections.join("\n\n")
}

fn summarize_ai_tool_result_for_export(result: &serde_json::Value) -> String {
    let mut parts = Vec::new();
    if let Some(record_count) = result.get("record_count").and_then(|value| value.as_u64()) {
        parts.push(format!("记录 {record_count} 条"));
    }
    if let Some(records) = result.get("records").and_then(|value| value.as_array()) {
        parts.push(format!("返回 {} 条记录", records.len()));
        let metadata_samples = records
            .iter()
            .filter_map(|record| {
                let metadata = record.get("metadata")?;
                if metadata.is_null() {
                    return None;
                }
                let record_id = record
                    .get("id")
                    .and_then(|value| value.as_str())
                    .unwrap_or("unknown");
                serde_json::to_string(metadata)
                    .ok()
                    .map(|metadata| format!("{record_id}: {metadata}"))
            })
            .take(3)
            .collect::<Vec<_>>()
            .join("；");
        if !metadata_samples.is_empty() {
            parts.push(format!("metadata 样例: {metadata_samples}"));
        }
    }
    if let Some(apps) = result.get("apps").and_then(|value| value.as_array()) {
        parts.push(format!("通知 App {} 个", apps.len()));
    }
    if let Some(summary) = result.get("summary").and_then(|value| value.as_str()) {
        parts.push(format!("摘要: {}", sanitize_summary_line(summary)));
    }
    if result.get("scheduled").and_then(|value| value.as_bool()) == Some(true) {
        parts.push("已安排最终答案导出".to_string());
    }
    if result.get("saved").and_then(|value| value.as_bool()) == Some(true) {
        parts.push("会话已保存".to_string());
    }
    if let Some(path) = result
        .get("exported_file")
        .and_then(|value| value.get("saved_path"))
        .and_then(|value| value.as_str())
    {
        parts.push(format!("导出文件: {path}"));
    }
    parts.join("；")
}

fn compact_json_for_export(value: &str) -> String {
    serde_json::from_str::<serde_json::Value>(value)
        .ok()
        .and_then(|json| serde_json::to_string(&json).ok())
        .unwrap_or_else(|| value.to_string())
}

fn export_ai_answer_word(
    session_id: &str,
    title: &str,
    content: &str,
    assistant_message: &AiMessage,
    tool_call_ids: &[String],
    records: &[HistoryRecord],
) -> Result<HistoryExportPayload, String> {
    let session = storage::ai::get_session(session_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "会话不存在".to_string())?;
    let now = chrono::Utc::now().timestamp_millis();
    let title = title.trim();
    let title = if title.is_empty() { &session.title } else { title };
    let mut selected_tool_calls = Vec::new();
    if !tool_call_ids.is_empty() {
        let all_tool_calls = storage::ai::list_tool_calls(session_id).map_err(|e| e.to_string())?;
        let wanted = tool_call_ids.iter().collect::<std::collections::HashSet<_>>();
        selected_tool_calls = all_tool_calls
            .into_iter()
            .filter(|tool_call| wanted.contains(&tool_call.id))
            .collect::<Vec<_>>()
    }
    let export_content = format_ai_answer_export_content(
        &session,
        content,
        assistant_message,
        &selected_tool_calls,
        records,
    );
    let bytes = reporting::export_report_to_word("ai_answer", title, session.created_at, now, &export_content)?;
    let filename = format!(
        "voice-input-ai-answer-{}.docx",
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    );
    let saved_path = save_export_bytes(&filename, &bytes)?;
    storage::ai::add_exported_file(
        session.id,
        Some(assistant_message.id.clone()),
        "answer_word".to_string(),
        filename.clone(),
        saved_path.clone(),
    )
    .map_err(|e| e.to_string())?;
    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

fn format_ai_answer_export_content(
    session: &AiSession,
    content: &str,
    assistant_message: &AiMessage,
    tool_calls: &[AiToolCall],
    records: &[HistoryRecord],
) -> String {
    let mut sections = vec![format!(
        "## 回答\n\n{}\n\n## 回答元数据\n\n- 会话 ID: {}\n- 消息 ID: {}\n- Skill: {}\n- 引用记录: {} 条",
        content.trim(),
        session.id,
        assistant_message.id,
        assistant_message
            .metadata
            .as_deref()
            .and_then(|value| serde_json::from_str::<serde_json::Value>(value).ok())
            .and_then(|metadata| metadata.get("skill_id").and_then(|value| value.as_str()).map(str::to_string))
            .unwrap_or_else(|| "由 LLM 自动选择".to_string()),
        records.len()
    )];
    if !records.is_empty() {
        let references = records
            .iter()
            .take(50)
            .map(|record| {
                let metadata = compact_metadata_text(record);
                if metadata == "无" {
                    format!(
                        "- {} | {} | {} | {}",
                        record.id,
                        record.content_type,
                        record.from_device_name,
                        sanitize_summary_line(&record.content)
                    )
                } else {
                    format!(
                        "- {} | {} | {} | {} | metadata: {}",
                        record.id,
                        record.content_type,
                        record.from_device_name,
                        sanitize_summary_line(&record.content),
                        metadata
                    )
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        sections.push(format!("## 引用记录\n\n{references}"));
    }
    if !tool_calls.is_empty() {
        let tool_content = tool_calls
            .iter()
            .map(format_ai_tool_call_for_export)
            .collect::<Vec<_>>()
            .join("\n\n");
        if !tool_content.is_empty() {
            sections.push(format!("## 工具调用\n\n{tool_content}"));
        }
    }
    sections.join("\n\n")
}

fn format_ai_tool_call_for_export(tool_call: &AiToolCall) -> String {
    let result = tool_call
        .result_json
        .as_deref()
        .and_then(|value| serde_json::from_str::<serde_json::Value>(value).ok());
    let summary = result
        .as_ref()
        .map(summarize_ai_tool_result_for_export)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "无结果摘要".to_string());
    format!(
        "### {}\n\n- 状态: {}\n- 参数: {}\n- 结果: {}",
        tool_call.tool_name,
        tool_call.status,
        compact_json_for_export(&tool_call.arguments_json),
        summary
    )
}

#[tauri::command]
fn save_ai_model_config(
    openai: OpenAiConfig,
) -> Result<AiModelConfigSavePayload, String> {
    let mut config = AppConfig::load();
    config.set_openai_config(openai);
    config.save().map_err(|e| e.to_string())?;
    Ok(AiModelConfigSavePayload { success: true })
}

#[tauri::command]
fn list_ai_skills() -> Result<Vec<AiSkill>, String> {
    storage::ai::list_skills().map_err(|e| e.to_string())
}

#[tauri::command]
fn save_ai_skill(skill: AiSkill) -> Result<AiSkill, String> {
    storage::ai::upsert_skill(skill).map_err(|e| e.to_string())
}

#[tauri::command]
fn export_ai_skills_json() -> Result<HistoryExportPayload, String> {
    let skills = storage::ai::list_skills().map_err(|e| e.to_string())?;
    let json = serde_json::to_vec_pretty(&serde_json::json!({
        "version": 1,
        "exported_at": chrono::Utc::now().timestamp_millis(),
        "skills": skills
    }))
    .map_err(|e| e.to_string())?;
    let filename = format!(
        "voice-input-ai-skills-{}.json",
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    );
    let saved_path = save_export_bytes(&filename, &json)?;
    Ok(HistoryExportPayload {
        filename,
        saved_path,
    })
}

#[tauri::command]
fn import_ai_skills_json(json_text: String) -> Result<Vec<AiSkill>, String> {
    let trimmed = json_text.trim();
    if trimmed.is_empty() {
        return Err("导入内容不能为空".to_string());
    }
    let parsed_skills = serde_json::from_str::<ImportAiSkillsPayload>(trimmed)
        .map(|payload| payload.skills)
        .or_else(|_| serde_json::from_str::<Vec<AiSkill>>(trimmed))
        .map_err(|e| format!("Skill JSON 格式不正确: {e}"))?;
    if parsed_skills.is_empty() {
        return Err("没有可导入的 Skill".to_string());
    }

    let mut saved = Vec::new();
    for mut skill in parsed_skills {
        skill.id = skill.id.trim().to_string();
        skill.name = skill.name.trim().to_string();
        if skill.id.is_empty() || skill.name.is_empty() {
            return Err("Skill 的 id 和名称不能为空".to_string());
        }
        if skill.default_period.trim().is_empty() {
            skill.default_period = "custom".to_string();
        }
        if skill.input_schema.trim().is_empty() {
            skill.input_schema = "{}".to_string();
        }
        if skill.default_filters.trim().is_empty() {
            skill.default_filters = "{}".to_string();
        }
        if skill.output_format.trim().is_empty() {
            skill.output_format = "Markdown：直接回答用户问题，必要时列出依据记录。".to_string();
        }
        saved.push(storage::ai::upsert_skill(skill).map_err(|e| e.to_string())?);
    }
    Ok(saved)
}

#[tauri::command]
fn create_ai_session(
    title: String,
    selected_skill: Option<String>,
    source_filters: Option<String>,
) -> Result<AiSession, String> {
    storage::ai::create_session(title, selected_skill, source_filters).map_err(|e| e.to_string())
}

#[tauri::command]
fn list_ai_sessions(limit: Option<usize>) -> Result<Vec<AiSession>, String> {
    storage::ai::list_sessions(limit).map_err(|e| e.to_string())
}

#[tauri::command]
fn add_ai_message(
    session_id: String,
    role: String,
    content: String,
    metadata: Option<String>,
) -> Result<AiMessage, String> {
    storage::ai::add_message(session_id, role, content, metadata).map_err(|e| e.to_string())
}

#[tauri::command]
fn list_ai_messages(session_id: String) -> Result<Vec<AiMessage>, String> {
    storage::ai::list_messages(&session_id).map_err(|e| e.to_string())
}

#[tauri::command]
fn list_ai_tool_calls(session_id: String) -> Result<Vec<AiToolCall>, String> {
    storage::ai::list_tool_calls(&session_id).map_err(|e| e.to_string())
}

#[tauri::command]
fn list_ai_exported_files(session_id: String) -> Result<Vec<AiExportedFile>, String> {
    storage::ai::list_exported_files(&session_id).map_err(|e| e.to_string())
}

#[tauri::command]
fn record_ai_tool_call(
    session_id: String,
    message_id: Option<String>,
    tool_name: String,
    arguments_json: String,
    result_json: Option<String>,
    status: String,
) -> Result<AiToolCall, String> {
    storage::ai::add_tool_call(
        session_id,
        message_id,
        tool_name,
        arguments_json,
        result_json,
        status,
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn search_history_records_tool(
    start_at: Option<i64>,
    end_at: Option<i64>,
    limit: Option<usize>,
    search: Option<String>,
    content_type: Option<String>,
    via: Option<String>,
    from_device: Option<String>,
    source_app: Option<String>,
    delivery_status: Option<String>,
    favorite: Option<bool>,
    pinned: Option<bool>,
    tag: Option<String>,
) -> Result<Vec<HistoryRecord>, String> {
    history::list_records(HistoryQuery {
        ids: None,
        start_at,
        end_at,
        limit,
        before_received_at: None,
        before_id: None,
        search,
        content_type,
        via,
        from_device,
        source_app,
        delivery_status,
        favorite,
        pinned,
        tag,
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn list_notification_apps() -> Result<Vec<history::NotificationAppSummary>, String> {
    history::list_notification_apps().map_err(|e| e.to_string())
}

#[tauri::command]
fn cancel_ai_assistant_request(request_id: String) -> Result<(), String> {
    reporting::cancel_report_stream(&request_id);
    Ok(())
}

#[tauri::command]
async fn run_ai_assistant(
    session_id: Option<String>,
    question: String,
    skill_id: Option<String>,
    filters: Option<AiAssistantFilters>,
    request_id: Option<String>,
    app_handle: tauri::AppHandle,
) -> Result<AiAssistantRunResult, String> {
    let normalized_question = question.trim();
    if normalized_question.is_empty() {
        return Err("问题不能为空".to_string());
    }

    let session = match session_id {
        Some(id) => storage::ai::get_session(&id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "会话不存在".to_string())?,
        None => storage::ai::create_session(
            normalized_question.chars().take(40).collect(),
            None,
            filters
                .as_ref()
                .and_then(|value| serde_json::to_string(value).ok()),
        )
        .map_err(|e| e.to_string())?,
    };

    let user_message = storage::ai::add_message(
        session.id.clone(),
        "user".to_string(),
        normalized_question.to_string(),
        None,
    )
    .map_err(|e| e.to_string())?;

    let _ = skill_id;
    let session_messages = storage::ai::list_messages(&session.id).map_err(|e| e.to_string())?;
    let skills = storage::ai::list_skills().map_err(|e| e.to_string())?;
    let config = AppConfig::load();
    let assistant_request_id = request_id.clone();
    if let Some(request_id) = assistant_request_id.as_deref() {
        reporting::clear_report_stream_cancelled(request_id);
    }
    let plan_prompt =
        build_ai_assistant_plan_prompt(normalized_question, &skills, &session_messages, filters.as_ref());
    let plan_text = match reporting::generate_openai_text(
        config.openai.clone(),
        &plan_prompt,
        "你是语传 AI 助手的工具规划器。必须只输出 JSON，不要输出解释、Markdown 或代码块。你要自主选择 Skill 和工具，不能让前端或程序替你兜底。",
        None,
    )
    .await
    {
        Ok(value) => value,
        Err(message) => {
            emit_ai_assistant_event(
                &app_handle,
                assistant_request_id.as_deref(),
                &session.id,
                "assistant_error",
                None,
                None,
                Some(&message),
                None,
            );
            return Err(message);
        }
    };
    if let Some(request_id) = assistant_request_id.as_deref() {
        if reporting::is_report_stream_cancelled(request_id) {
            let message = "AI 生成已取消".to_string();
            emit_ai_assistant_event(
                &app_handle,
                Some(request_id),
                &session.id,
                "assistant_error",
                None,
                None,
                Some(&message),
                None,
            );
            return Err(message);
        }
    }
    let plan = match parse_ai_assistant_plan(&plan_text, true, &skills) {
        Ok(value) => value,
        Err(message) => {
            emit_ai_assistant_event(
                &app_handle,
                assistant_request_id.as_deref(),
                &session.id,
                "assistant_error",
                None,
                None,
                Some(&message),
                Some(serde_json::json!({ "raw_plan": plan_text })),
            );
            return Err(message);
        }
    };
    let skill = match plan.skill_id.as_deref().filter(|id| !id.trim().is_empty()) {
        Some(id) => storage::ai::get_skill(id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("AI 选择的 Skill 不存在: {id}"))?
            .into(),
        None => None,
    };
    if let Some(skill) = skill.as_ref() {
        storage::ai::update_session_metadata(
            &session.id,
            None,
            Some(skill.id.clone()),
            filters
                .as_ref()
                .and_then(|value| serde_json::to_string(value).ok()),
        )
        .map_err(|e| e.to_string())?;
    }

    let mut execution_state = AiToolExecutionState {
        records: Vec::new(),
        tool_call_ids: Vec::new(),
        tool_summaries: Vec::new(),
        observations: Vec::new(),
        export_answer_requested: false,
        export_answer_title: None,
    };
    execute_ai_assistant_plan_tools(
        plan.tools,
        &session.id,
        &user_message.id,
        assistant_request_id.as_deref(),
        &app_handle,
        &mut execution_state,
    )?;
    for react_round in 2..=3 {
        if execution_state.observations.is_empty() {
            break;
        }
        if let Some(request_id) = assistant_request_id.as_deref() {
            if reporting::is_report_stream_cancelled(request_id) {
                return Err("AI 生成已取消".to_string());
            }
        }
        let react_prompt = build_ai_assistant_react_prompt(
            normalized_question,
            &skills,
            &session_messages,
            filters.as_ref(),
            &execution_state.observations,
        );
        let react_plan_text = match reporting::generate_openai_text(
            config.openai.clone(),
            &react_prompt,
            "你是语传 AI 助手的 ReAct 工具规划器。根据观察结果决定是否继续调用工具。必须只输出 JSON。",
            None,
        )
        .await
        {
            Ok(value) => value,
            Err(message) => {
                emit_ai_assistant_event(
                    &app_handle,
                    assistant_request_id.as_deref(),
                    &session.id,
                    "assistant_error",
                    None,
                    None,
                    Some(&message),
                    None,
                );
                return Err(message);
            }
        };
        let react_plan = match parse_ai_assistant_plan(&react_plan_text, false, &skills) {
            Ok(value) => value,
            Err(message) => {
                emit_ai_assistant_event(
                    &app_handle,
                    assistant_request_id.as_deref(),
                    &session.id,
                    "assistant_error",
                    None,
                    None,
                    Some(&message),
                    Some(serde_json::json!({ "raw_plan": react_plan_text, "round": react_round })),
                );
                return Err(message);
            }
        };
        if react_plan.final_answer_ready || react_plan.tools.is_empty() {
            break;
        }
        execute_ai_assistant_plan_tools(
            react_plan.tools,
            &session.id,
            &user_message.id,
            assistant_request_id.as_deref(),
            &app_handle,
            &mut execution_state,
        )?;
    }
    dedupe_history_records(&mut execution_state.records);
    let prompt = build_ai_assistant_prompt(
        normalized_question,
        skill.as_ref(),
        &execution_state.records,
        &session_messages,
        &execution_state.tool_summaries,
        &execution_state.observations,
    );
    let stream_handle = request_id.map(|request_id| reporting::ReportStreamHandle {
        app_handle: app_handle.clone(),
        request_id,
        event_name: Some("ai_assistant_delta".to_string()),
    });
    let content = match reporting::generate_openai_text(
        config.openai,
        &prompt,
        "你是语传 AI 助手。你只能基于工具查询到的历史输入、通知和文件记录回答；如果记录不足，要说明未在记录中体现。回答要结构清晰，适合继续追问。",
        stream_handle,
    )
    .await
    {
        Ok(value) => value,
        Err(message) => {
            emit_ai_assistant_event(
                &app_handle,
                assistant_request_id.as_deref(),
                &session.id,
                "assistant_error",
                None,
                None,
                Some(&message),
                None,
            );
            return Err(message);
        }
    };
    if let Some(request_id) = assistant_request_id.as_deref() {
        if reporting::is_report_stream_cancelled(request_id) {
            return Err("AI 生成已取消".to_string());
        }
    }

    let assistant_message = storage::ai::add_message(
        session.id.clone(),
        "assistant".to_string(),
        content.clone(),
        Some(
            serde_json::json!({
                "skill_id": skill.map(|value| value.id),
                "tool_call_ids": execution_state.tool_call_ids.clone(),
                "record_ids": execution_state.records.iter().map(|record| record.id.clone()).collect::<Vec<_>>(),
                "record_count": execution_state.records.len()
            })
            .to_string(),
        ),
    )
    .map_err(|e| e.to_string())?;
    let exported_file = if execution_state.export_answer_requested {
        let title = execution_state.export_answer_title
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or(&session.title);
        Some(export_ai_answer_word(
            &session.id,
            title,
            &content,
            &assistant_message,
            &execution_state.tool_call_ids,
            &execution_state.records,
        )?)
    } else {
        None
    };
    emit_ai_assistant_event(
        &app_handle,
        assistant_request_id.as_deref(),
        &session.id,
        "assistant_done",
        None,
        None,
        Some("回答完成"),
        Some(serde_json::json!({
            "assistant_message_id": assistant_message.id,
            "record_count": execution_state.records.len(),
            "tool_call_ids": execution_state.tool_call_ids.clone(),
            "exported_file": exported_file.clone()
        })),
    );
    if let Some(request_id) = assistant_request_id.as_deref() {
        reporting::clear_report_stream_cancelled(request_id);
    }

    Ok(AiAssistantRunResult {
        session_id: session.id,
        user_message_id: user_message.id,
        assistant_message_id: assistant_message.id,
        tool_call_id: execution_state.tool_call_ids.first().cloned(),
        record_count: execution_state.records.len(),
        content,
        exported_file,
    })
}

fn history_query_from_tool_args(arguments: AiAssistantToolArguments) -> HistoryQuery {
    HistoryQuery {
        ids: None,
        start_at: arguments.start_at,
        end_at: arguments.end_at,
        limit: Some(arguments.limit.unwrap_or(80).min(200).max(1)),
        before_received_at: None,
        before_id: None,
        search: arguments.search,
        content_type: arguments.content_type,
        via: arguments.via,
        from_device: arguments.from_device,
        source_app: arguments.source_app,
        delivery_status: arguments.delivery_status,
        favorite: arguments.favorite,
        pinned: arguments.pinned,
        tag: arguments.tag,
    }
}

fn execute_ai_assistant_plan_tools(
    tools: Vec<AiAssistantToolPlan>,
    session_id: &str,
    user_message_id: &str,
    assistant_request_id: Option<&str>,
    app_handle: &tauri::AppHandle,
    state: &mut AiToolExecutionState,
) -> Result<(), String> {
    for tool in tools {
        if let Some(request_id) = assistant_request_id {
            if reporting::is_report_stream_cancelled(request_id) {
                return Err("AI 生成已取消".to_string());
            }
        }
        let tool_name = tool.name.clone();
        emit_ai_assistant_event(
            app_handle,
            assistant_request_id,
            session_id,
            "tool_call_start",
            None,
            Some(&tool_name),
            Some("开始调用工具"),
            Some(serde_json::json!({ "arguments": query_tool_arguments_to_json(&tool.arguments) })),
        );

        match tool.name.as_str() {
            "search_history_records" | "search_notification_records" => {
                let mut query = history_query_from_tool_args(tool.arguments);
                if tool.name == "search_notification_records" {
                    query.content_type = Some("notification".to_string());
                }
                let tool_records = history::list_records(query.clone()).map_err(|e| e.to_string())?;
                let tool_args = serde_json::to_string(&query_to_json(&query)).map_err(|e| e.to_string())?;
                let compact_records = tool_records
                    .iter()
                    .map(compact_history_record_for_ai)
                    .collect::<Vec<_>>();
                let tool_result = serde_json::json!({
                    "record_count": tool_records.len(),
                    "records": compact_records,
                });
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result.to_string()),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("工具调用完成"),
                    Some(with_tool_arguments(
                        records_event_payload(&tool_records),
                        &query_to_json(&query),
                    )),
                );
                state.observations.push(format!(
                    "{} 查询完成，返回 {} 条记录，参数 {}。样例：{}",
                    tool_call.tool_name,
                    tool_records.len(),
                    query_to_json(&query),
                    format_records_observation(&tool_records, 5)
                ));
                state.tool_call_ids.push(tool_call.id);
                state.records.extend(tool_records);
            }
            "list_notification_apps" => {
                let apps = history::list_notification_apps().map_err(|e| e.to_string())?;
                let tool_args = "{}".to_string();
                let tool_result = serde_json::json!({ "apps": apps });
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result.to_string()),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("工具调用完成"),
                    Some(with_tool_arguments(
                        serde_json::json!({ "app_count": apps.len() }),
                        &serde_json::json!({}),
                    )),
                );
                let app_summary = apps
                    .iter()
                    .take(20)
                    .map(|app| {
                        let label = if app.app_name.trim().is_empty() {
                            app.app_package.as_str()
                        } else {
                            app.app_name.as_str()
                        };
                        format!("{}({})", label, app.count)
                    })
                    .collect::<Vec<_>>()
                    .join(", ");
                state.observations.push(format!(
                    "{} 返回 {} 个通知 App：{}",
                    tool_call.tool_name,
                    apps.len(),
                    if app_summary.is_empty() { "无" } else { &app_summary }
                ));
                state.tool_call_ids.push(tool_call.id);
            }
            "get_record_detail" => {
                let record_id = tool
                    .arguments
                    .record_id
                    .as_deref()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .ok_or_else(|| "get_record_detail 缺少 record_id".to_string())?;
                let record = history::get_record(record_id)
                    .map_err(|e| e.to_string())?
                    .ok_or_else(|| format!("记录不存在: {record_id}"))?;
                let compact_record = compact_history_record_for_ai(&record);
                let tool_args = serde_json::json!({ "record_id": record_id }).to_string();
                let tool_result = serde_json::json!({
                    "record": compact_record,
                    "metadata": compact_metadata_for_ai(&record.content_type, record.metadata.as_deref())
                })
                .to_string();
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("工具调用完成"),
                    Some(with_tool_arguments(serde_json::json!({
                        "record_id": record.id,
                        "record": compact_history_record_for_ai(&record)
                    }), &serde_json::json!({ "record_id": record_id }))),
                );
                state.observations.push(format!(
                    "{} 获取记录 {}，类型 {}，metadata {}",
                    tool_call.tool_name,
                    record.id,
                    record.content_type,
                    compact_metadata_text(&record)
                ));
                state.tool_call_ids.push(tool_call.id);
                state.records.push(record);
            }
            "summarize_records" => {
                let selected_records = select_records_for_tool(&state.records, tool.arguments.record_ids.as_deref())?;
                let summary = summarize_history_records(&selected_records);
                let tool_args = serde_json::json!({
                    "record_ids": selected_records.iter().map(|record| record.id.clone()).collect::<Vec<_>>()
                })
                .to_string();
                let tool_result = serde_json::json!({
                    "record_count": selected_records.len(),
                    "summary": summary
                });
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result.to_string()),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                state.tool_summaries.push(summary.clone());
                state.records.extend(selected_records.clone());
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("摘要工具完成"),
                    Some(with_tool_arguments(
                        records_event_payload(&selected_records),
                        &serde_json::json!({
                            "record_ids": selected_records.iter().map(|record| record.id.clone()).collect::<Vec<_>>()
                        }),
                    )),
                );
                state.observations.push(format!(
                    "{} 摘要了 {} 条记录：{}",
                    tool_call.tool_name,
                    selected_records.len(),
                    sanitize_summary_line(&summary)
                ));
                state.tool_call_ids.push(tool_call.id);
            }
            "export_answer_word" => {
                let export_format = tool
                    .arguments
                    .format
                    .as_deref()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .unwrap_or("word");
                if export_format != "word" {
                    let message = format!("export_answer_word 只支持 format=word，收到: {export_format}");
                    emit_ai_assistant_event(
                        app_handle,
                        assistant_request_id,
                        session_id,
                        "assistant_error",
                        None,
                        Some("export_answer_word"),
                        Some(&message),
                        None,
                    );
                    return Err(message);
                }
                state.export_answer_requested = true;
                state.export_answer_title = tool.arguments.title.clone();
                let tool_args = serde_json::json!({
                    "title": tool.arguments.title,
                    "format": export_format
                })
                .to_string();
                let tool_result = serde_json::json!({
                    "scheduled": true,
                    "message": "最终答案生成后导出 Word"
                });
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result.to_string()),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("已安排答案导出"),
                    Some(with_tool_arguments(
                        serde_json::json!({ "scheduled": true }),
                        &serde_json::json!({
                            "title": tool.arguments.title,
                            "format": export_format
                        }),
                    )),
                );
                state.observations.push("export_answer_word 已安排最终答案导出。".to_string());
                state.tool_call_ids.push(tool_call.id);
            }
            "save_ai_session" => {
                let selected_skill = tool
                    .arguments
                    .selected_skill
                    .as_deref()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .map(str::to_string);
                if let Some(skill_id) = selected_skill.as_deref() {
                    ensure_ai_skill_available(skill_id)
                        .map_err(|message| format!("save_ai_session 选择的 {message}"))?;
                }
                let source_filters = tool
                    .arguments
                    .source_filters
                    .as_ref()
                    .map(serialize_ai_source_filters)
                    .transpose()?;
                let updated_session = storage::ai::update_session_metadata(
                    session_id,
                    tool.arguments.title.clone(),
                    selected_skill.clone(),
                    source_filters.clone(),
                )
                .map_err(|e| e.to_string())?;
                let tool_args = serde_json::json!({
                    "session_id": session_id,
                    "title": tool.arguments.title,
                    "selected_skill": selected_skill,
                    "source_filters": source_filters
                })
                .to_string();
                let tool_result = serde_json::json!({
                    "session_id": session_id,
                    "title": updated_session.title,
                    "selected_skill": updated_session.selected_skill,
                    "source_filters": updated_session.source_filters,
                    "saved": true
                });
                let tool_call = storage::ai::add_tool_call(
                    session_id.to_string(),
                    Some(user_message_id.to_string()),
                    tool.name,
                    tool_args,
                    Some(tool_result.to_string()),
                    "completed".to_string(),
                )
                .map_err(|e| e.to_string())?;
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "tool_call_result",
                    Some(&tool_call.id),
                    Some(&tool_call.tool_name),
                    Some("会话已保存"),
                    Some(with_tool_arguments(
                        serde_json::json!({ "session_id": session_id }),
                        &serde_json::json!({
                            "session_id": session_id,
                            "title": tool.arguments.title,
                            "selected_skill": selected_skill,
                            "source_filters": source_filters
                        }),
                    )),
                );
                state.observations.push("save_ai_session 已保存当前会话。".to_string());
                state.tool_call_ids.push(tool_call.id);
            }
            other => {
                let message = format!("AI 选择了不支持的工具: {other}");
                emit_ai_assistant_event(
                    app_handle,
                    assistant_request_id,
                    session_id,
                    "assistant_error",
                    None,
                    Some(other),
                    Some(&message),
                    None,
                );
                return Err(message);
            }
        }
    }
    Ok(())
}

fn select_records_for_tool(
    records: &[HistoryRecord],
    record_ids: Option<&[String]>,
) -> Result<Vec<HistoryRecord>, String> {
    let Some(record_ids) = record_ids else {
        return Ok(records.to_vec());
    };
    let wanted = record_ids
        .iter()
        .map(|value| value.trim())
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .collect::<Vec<_>>();
    if wanted.is_empty() {
        return Ok(records.to_vec());
    }
    let mut selected = records
        .iter()
        .filter(|record| wanted.iter().any(|id| id == &record.id))
        .cloned()
        .collect::<Vec<_>>();
    let mut found = selected
        .iter()
        .map(|record| record.id.clone())
        .collect::<std::collections::HashSet<_>>();
    let missing = wanted
        .iter()
        .filter(|id| !found.contains(*id))
        .cloned()
        .collect::<Vec<_>>();
    for record_id in missing {
        let record = history::get_record(&record_id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("summarize_records 指定的记录不存在: {record_id}"))?;
        found.insert(record.id.clone());
        selected.push(record);
    }
    Ok(selected)
}

fn summarize_history_records(records: &[HistoryRecord]) -> String {
    if records.is_empty() {
        return "未查询到可摘要的记录。".to_string();
    }
    let mut type_counts = std::collections::BTreeMap::<String, usize>::new();
    let mut app_counts = std::collections::BTreeMap::<String, usize>::new();
    for record in records {
        *type_counts.entry(record.content_type.clone()).or_default() += 1;
        if let Some(app) = extract_source_app(record).filter(|value| !value.is_empty()) {
            *app_counts.entry(app).or_default() += 1;
        }
    }
    let type_text = type_counts
        .iter()
        .map(|(key, count)| format!("{key}:{count}"))
        .collect::<Vec<_>>()
        .join(", ");
    let app_text = app_counts
        .iter()
        .take(8)
        .map(|(key, count)| format!("{key}:{count}"))
        .collect::<Vec<_>>()
        .join(", ");
    let samples = records
        .iter()
        .take(6)
        .map(|record| {
            let content = sanitize_summary_line(&record.content);
            let metadata = compact_metadata_text(record);
            if metadata == "无" {
                format!("- [{}] {}", record.content_type, content)
            } else {
                format!("- [{}] {} | metadata: {}", record.content_type, content, metadata)
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "摘要记录数：{}。\n类型分布：{}。\n来源 App：{}。\n样例：\n{}",
        records.len(),
        if type_text.is_empty() { "无" } else { &type_text },
        if app_text.is_empty() { "无" } else { &app_text },
        samples
    )
}

fn sanitize_summary_line(value: &str) -> String {
    let normalized = value.split_whitespace().collect::<Vec<_>>().join(" ");
    if normalized.chars().count() > 120 {
        normalized.chars().take(120).collect::<String>() + "..."
    } else {
        normalized
    }
}

fn compact_history_record_for_ai(record: &HistoryRecord) -> serde_json::Value {
    serde_json::json!({
        "id": record.id,
        "from_device_id": record.from_device_id,
        "from_device_name": record.from_device_name,
        "content_type": record.content_type,
        "content": record.content,
        "sent_at": record.sent_at,
        "received_at": record.received_at,
        "via": record.via,
        "delivery_mode": record.delivery_mode,
        "favorite": record.favorite,
        "pinned": record.pinned,
        "tags": record.tags,
        "metadata": compact_metadata_for_ai(&record.content_type, record.metadata.as_deref())
    })
}

fn records_event_payload(records: &[HistoryRecord]) -> serde_json::Value {
    serde_json::json!({
        "record_count": records.len(),
        "record_ids": records.iter().map(|record| record.id.clone()).collect::<Vec<_>>(),
        "record_samples": records.iter().take(5).map(compact_history_record_for_ai).collect::<Vec<_>>()
    })
}

fn with_tool_arguments(
    mut payload: serde_json::Value,
    arguments: &serde_json::Value,
) -> serde_json::Value {
    if let Some(object) = payload.as_object_mut() {
        object.insert("arguments".to_string(), arguments.clone());
        payload
    } else {
        serde_json::json!({
            "arguments": arguments,
            "value": payload
        })
    }
}

fn compact_metadata_for_ai(content_type: &str, metadata: Option<&str>) -> Option<serde_json::Value> {
    let metadata = metadata?;
    let parsed = serde_json::from_str::<serde_json::Value>(metadata).ok()?;
    let object = parsed.as_object()?;
    let allowed_keys: &[&str] = match content_type {
        "notification" => &[
            "app_name",
            "app_package",
            "title",
            "text",
            "sub_text",
            "big_text",
            "conversation_title",
            "channel_id",
            "group_key",
            "category",
            "post_time",
            "forward_mode",
            "silent",
            "copy_to_clipboard",
            "notification_key",
            "is_ongoing",
            "is_clearable",
            "importance",
        ],
        "image" => &[
            "file_name",
            "mime_type",
            "size",
            "width",
            "height",
            "saved_path",
        ],
        "file" => &["file_name", "mime_type", "size", "saved_path"],
        _ => &[],
    };
    let mut compact = serde_json::Map::new();
    for key in allowed_keys {
        if let Some(value) = object.get(*key).filter(|value| !value.is_null()) {
            if matches!(*key, "data" | "image_data" | "icon") {
                continue;
            }
            compact.insert((*key).to_string(), compact_metadata_value(value));
        }
    }
    if compact.is_empty() {
        None
    } else {
        Some(serde_json::Value::Object(compact))
    }
}

fn compact_metadata_value(value: &serde_json::Value) -> serde_json::Value {
    if let Some(text) = value.as_str() {
        let normalized = sanitize_summary_line(text);
        serde_json::Value::String(normalized)
    } else {
        value.clone()
    }
}

fn compact_metadata_text(record: &HistoryRecord) -> String {
    compact_metadata_for_ai(&record.content_type, record.metadata.as_deref())
        .and_then(|value| serde_json::to_string(&value).ok())
        .unwrap_or_else(|| "无".to_string())
}

fn format_records_observation(records: &[HistoryRecord], limit: usize) -> String {
    let samples = records
        .iter()
        .take(limit)
        .map(|record| {
            let metadata = compact_metadata_text(record);
            if metadata == "无" {
                format!(
                    "{} [{}] {}",
                    record.id,
                    record.content_type,
                    sanitize_summary_line(&record.content)
                )
            } else {
                format!(
                    "{} [{}] {} | metadata {}",
                    record.id,
                    record.content_type,
                    sanitize_summary_line(&record.content),
                    metadata
                )
            }
        })
        .collect::<Vec<_>>()
        .join("；");
    if samples.is_empty() {
        "无".to_string()
    } else {
        samples
    }
}

fn extract_source_app(record: &HistoryRecord) -> Option<String> {
    record.metadata.as_deref().and_then(|metadata| {
        serde_json::from_str::<serde_json::Value>(metadata)
            .ok()
            .and_then(|value| {
                value
                    .get("app_name")
                    .or_else(|| value.get("app_package"))
                    .and_then(|item| item.as_str())
                    .map(str::to_string)
            })
    })
}

fn emit_ai_assistant_event(
    app_handle: &tauri::AppHandle,
    request_id: Option<&str>,
    session_id: &str,
    event: &str,
    tool_call_id: Option<&str>,
    tool_name: Option<&str>,
    message: Option<&str>,
    data: Option<serde_json::Value>,
) {
    let Some(request_id) = request_id else {
        return;
    };
    let payload = AiAssistantEventPayload {
        request_id: request_id.to_string(),
        event: event.to_string(),
        session_id: session_id.to_string(),
        tool_call_id: tool_call_id.map(str::to_string),
        tool_name: tool_name.map(str::to_string),
        message: message.map(str::to_string),
        data,
    };
    app_handle.emit("ai_assistant_event", payload).unwrap_or(());
}

fn query_tool_arguments_to_json(arguments: &AiAssistantToolArguments) -> serde_json::Value {
    serde_json::json!({
        "record_id": arguments.record_id,
        "record_ids": arguments.record_ids,
        "title": arguments.title,
        "format": arguments.format,
        "start_at": arguments.start_at,
        "end_at": arguments.end_at,
        "search": arguments.search,
        "content_type": arguments.content_type,
        "via": arguments.via,
        "from_device": arguments.from_device,
        "source_app": arguments.source_app,
        "delivery_status": arguments.delivery_status,
        "favorite": arguments.favorite,
        "pinned": arguments.pinned,
        "tag": arguments.tag,
        "limit": arguments.limit,
    })
}

fn dedupe_history_records(records: &mut Vec<HistoryRecord>) {
    let mut seen = std::collections::HashSet::new();
    records.retain(|record| seen.insert(record.id.clone()));
}

fn query_to_json(query: &HistoryQuery) -> serde_json::Value {
    serde_json::json!({
        "start_at": query.start_at,
        "end_at": query.end_at,
        "limit": query.limit,
        "search": query.search,
        "content_type": query.content_type,
        "via": query.via,
        "from_device": query.from_device,
        "source_app": query.source_app,
        "delivery_status": query.delivery_status,
        "favorite": query.favorite,
        "pinned": query.pinned,
        "tag": query.tag,
    })
}

fn build_ai_assistant_plan_prompt(
    question: &str,
    skills: &[AiSkill],
    messages: &[AiMessage],
    ui_filters: Option<&AiAssistantFilters>,
) -> String {
    let skills_text = skills
        .iter()
        .filter(|skill| skill.enabled)
        .map(|skill| {
            format!(
                "- id: {}\n  name: {}\n  description: {}\n  input_schema: {}\n  default_period: {}\n  default_filters: {}\n  output_format: {}",
                skill.id,
                skill.name,
                skill.description,
                skill.input_schema,
                skill.default_period,
                skill.default_filters,
                skill.output_format
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    let conversation_text = messages
        .iter()
        .rev()
        .take(8)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .map(|message| format!("{}: {}", message.role, message.content))
        .collect::<Vec<_>>()
        .join("\n");
    let ui_filters_text = ui_filters
        .and_then(|filters| serde_json::to_string(filters).ok())
        .unwrap_or_else(|| "null".to_string());

    format!(
        "用户问题：\n{question}\n\n会话上下文：\n{}\n\n可用 Skills：\n{}\n\n可用工具：\n1. search_history_records(arguments): 查询历史输入、图片、文件、通知。arguments 可包含 start_at, end_at, search, content_type, via, from_device, source_app, delivery_status, favorite, pinned, tag, limit。delivery_status 可为 received/manual/offline_sync，favorite/pinned 为 true/false，tag 为标签关键词。\n2. search_notification_records(arguments): 只查询通知记录。arguments 可包含 start_at, end_at, search, source_app, via, from_device, delivery_status, favorite, pinned, tag, limit。favorite/pinned 为 true/false，tag 为标签关键词。\n3. list_notification_apps(arguments): 列出已有通知记录的 App。arguments 可为空。\n4. get_record_detail(arguments): 获取某条记录详情。arguments 必须包含 record_id。\n5. summarize_records(arguments): 对已查询到的记录做摘要。arguments 可包含 record_ids；为空时摘要当前工具查询结果。\n6. export_answer_word(arguments): 将本次最终答案导出 Word。arguments 可包含 title, format，format 只能是 word。\n7. save_ai_session(arguments): 显式保存当前会话。arguments 可包含 title、selected_skill、source_filters。selected_skill 必须是可用 Skills 中的 id；source_filters 必须是 JSON 对象。\n\n当前界面筛选，仅作为参考，是否采用由你决定：\n{ui_filters_text}\n如果当前界面筛选包含 record_ids，它们是 PC 可查询记录 ID；你可以逐个用 get_record_detail 查询，或在已查询记录后传给 summarize_records。\n\n请自主选择 Skill 和一个或多个工具。必须只返回严格 JSON，格式如下：\n{{\"skill_id\":\"weekly_report\",\"final_answer_ready\":false,\"tools\":[{{\"name\":\"search_history_records\",\"arguments\":{{\"content_type\":\"text\",\"limit\":80}}}}]}}\n如果不需要 Skill，skill_id 为 null。至少选择一个工具。不要输出 Markdown，不要输出代码块。",
        if conversation_text.is_empty() {
            "暂无上文。".to_string()
        } else {
            conversation_text
        },
        if skills_text.is_empty() {
            "暂无可用 Skill。".to_string()
        } else {
            skills_text
        }
    )
}

fn build_ai_assistant_react_prompt(
    question: &str,
    skills: &[AiSkill],
    messages: &[AiMessage],
    ui_filters: Option<&AiAssistantFilters>,
    observations: &[String],
) -> String {
    let skills_text = skills
        .iter()
        .filter(|skill| skill.enabled)
        .map(|skill| {
            format!(
                "- id: {} | {} | input_schema: {} | default_filters: {} | output_format: {}",
                skill.id,
                skill.description,
                skill.input_schema,
                skill.default_filters,
                skill.output_format
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    let conversation_text = messages
        .iter()
        .rev()
        .take(8)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .map(|message| format!("{}: {}", message.role, message.content))
        .collect::<Vec<_>>()
        .join("\n");
    let ui_filters_text = ui_filters
        .and_then(|filters| serde_json::to_string(filters).ok())
        .unwrap_or_else(|| "null".to_string());
    let observation_text = observations
        .iter()
        .rev()
        .take(8)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .enumerate()
        .map(|(index, item)| format!("{}. {}", index + 1, item))
        .collect::<Vec<_>>()
        .join("\n");

    format!(
        "用户问题：\n{question}\n\n会话上下文：\n{}\n\n可用 Skills：\n{}\n\n已有观察结果：\n{}\n\n当前界面筛选，仅作为参考：\n{ui_filters_text}\n如果当前界面筛选包含 record_ids，它们是 PC 可查询记录 ID，可用于 get_record_detail 或 summarize_records。\n\n你需要判断是否还要继续调用工具。如果已有观察足够回答，返回 {{\"skill_id\":null,\"final_answer_ready\":true,\"tools\":[]}}。\n如果需要继续查，只能返回可执行工具 JSON，格式：{{\"skill_id\":null,\"final_answer_ready\":false,\"tools\":[{{\"name\":\"get_record_detail\",\"arguments\":{{\"record_id\":\"...\"}}}}]}}。\n可用工具同前：search_history_records、search_notification_records、list_notification_apps、get_record_detail、summarize_records、export_answer_word、save_ai_session。save_ai_session 可保存 title、selected_skill、source_filters。不要输出 Markdown，不要解释。",
        if conversation_text.is_empty() {
            "暂无上文。".to_string()
        } else {
            conversation_text
        },
        if skills_text.is_empty() {
            "暂无可用 Skill。".to_string()
        } else {
            skills_text
        },
        if observation_text.is_empty() {
            "暂无观察。".to_string()
        } else {
            observation_text
        }
    )
}

fn parse_ai_assistant_plan(
    plan_text: &str,
    require_tools: bool,
    skills: &[AiSkill],
) -> Result<AiAssistantPlan, String> {
    let trimmed = plan_text.trim();
    let plan: AiAssistantPlan = serde_json::from_str(trimmed).map_err(|error| {
        format!("AI 工具规划不是严格 JSON，已停止执行: {error}. 原始输出: {trimmed}")
    })?;
    if require_tools && plan.tools.is_empty() {
        return Err("AI 工具规划没有选择任何工具，已停止执行。".to_string());
    }
    if !require_tools && !plan.final_answer_ready && plan.tools.is_empty() {
        return Err("AI ReAct 规划既没有准备回答，也没有选择工具，已停止执行。".to_string());
    }
    validate_ai_assistant_plan(&plan, skills)?;
    Ok(plan)
}

fn validate_ai_assistant_plan(plan: &AiAssistantPlan, skills: &[AiSkill]) -> Result<(), String> {
    if let Some(skill_id) = plan.skill_id.as_deref().map(str::trim).filter(|id| !id.is_empty()) {
        if !skills.iter().any(|skill| skill_is_available(skill, skill_id)) {
            return Err(format!("AI 选择的 Skill 不存在或已停用: {skill_id}"));
        }
    }

    for tool in &plan.tools {
        let tool_name = tool.name.trim();
        if !is_allowed_ai_assistant_tool(tool_name) {
            return Err(format!("AI 选择了不支持的工具: {tool_name}"));
        }
    }
    Ok(())
}

fn skill_is_available(skill: &AiSkill, skill_id: &str) -> bool {
    skill.enabled && skill.id == skill_id
}

fn ensure_ai_skill_available(skill_id: &str) -> Result<AiSkill, String> {
    let skill = storage::ai::get_skill(skill_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Skill 不存在或已停用: {skill_id}"))?;
    if !skill_is_available(&skill, skill_id) {
        return Err(format!("Skill 不存在或已停用: {skill_id}"));
    }
    Ok(skill)
}

fn serialize_ai_source_filters(value: &serde_json::Value) -> Result<String, String> {
    if !value.is_object() {
        return Err("source_filters 必须是 JSON 对象".to_string());
    }
    serde_json::to_string(value).map_err(|e| e.to_string())
}

fn is_allowed_ai_assistant_tool(tool_name: &str) -> bool {
    matches!(
        tool_name,
        "search_history_records"
            | "search_notification_records"
            | "list_notification_apps"
            | "get_record_detail"
            | "summarize_records"
            | "export_answer_word"
            | "save_ai_session"
    )
}

fn build_ai_assistant_prompt(
    question: &str,
    skill: Option<&AiSkill>,
    records: &[HistoryRecord],
    messages: &[AiMessage],
    tool_summaries: &[String],
    observations: &[String],
) -> String {
    let records_text = records
        .iter()
        .enumerate()
        .map(|(index, record)| {
            let metadata = compact_metadata_text(record);
            if metadata == "无" {
                format!(
                    "{}. [{}][{}][{}] {} | 来源: {} | 时间: {}",
                    index + 1,
                    record.content_type,
                    record.via,
                    record.delivery_mode,
                    record.content,
                    record.from_device_name,
                    record.received_at
                )
            } else {
                format!(
                    "{}. [{}][{}][{}] {} | 来源: {} | 时间: {} | metadata: {}",
                    index + 1,
                    record.content_type,
                    record.via,
                    record.delivery_mode,
                    record.content,
                    record.from_device_name,
                    record.received_at,
                    metadata
                )
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    let (skill_prompt, output_format) = skill
        .map(|value| (value.prompt_template.as_str(), value.output_format.as_str()))
        .unwrap_or((
            "请基于以下记录回答用户问题，必要时提取待办、总结进展和风险。",
            "Markdown：直接回答用户问题，必要时列出依据记录。",
        ));
    let conversation_text = messages
        .iter()
        .rev()
        .take(8)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .map(|message| format!("{}: {}", message.role, message.content))
        .collect::<Vec<_>>()
        .join("\n");
    let summaries_text = if tool_summaries.is_empty() {
        "无额外摘要。".to_string()
    } else {
        tool_summaries.join("\n\n")
    };
    let observations_text = if observations.is_empty() {
        "无工具观察。".to_string()
    } else {
        observations.join("\n")
    };

    format!(
        "用户问题：\n{question}\n\n会话上下文：\n{}\n\nSkill 指令：\n{skill_prompt}\n\n输出格式要求：\n{output_format}\n\n工具观察：\n{observations_text}\n\n工具摘要：\n{summaries_text}\n\n工具查询结果：共 {} 条记录。\n{}\n\n请直接回答用户问题，并在信息不足时明确说明。",
        if conversation_text.is_empty() {
            "暂无上文。".to_string()
        } else {
            conversation_text
        },
        records.len(),
        if records_text.is_empty() {
            "没有查询到相关记录。".to_string()
        } else {
            records_text
        }
    )
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
async fn send_relay_payload(to_device_id: String, payload: serde_json::Value) -> Result<(), String> {
    let config = storage::config::AppConfig::load();
    let ws_client = network::websocket::get_ws_client();
    let client = ws_client.lock().await;
    if !client.is_connected().await {
        return Err("服务器未连接".to_string());
    }
    let msg = serde_json::json!({
        "type": "RELAY_MESSAGE",
        "from_device_id": config.device_id,
        "to_device_id": to_device_id,
        "payload": payload
    });
    client.send(&msg.to_string()).await.map_err(|e| e.to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

    fn test_skills() -> Vec<AiSkill> {
        vec![AiSkill {
            id: "weekly_report".to_string(),
            name: "周报".to_string(),
            description: "生成周报".to_string(),
            input_schema: "{}".to_string(),
            prompt_template: "生成周报".to_string(),
            default_period: "week".to_string(),
            default_filters: "{}".to_string(),
            output_format: "Markdown".to_string(),
            enabled: true,
        }]
    }

    fn disabled_test_skill() -> AiSkill {
        AiSkill {
            id: "disabled_report".to_string(),
            name: "停用模板".to_string(),
            description: "不可用模板".to_string(),
            input_schema: "{}".to_string(),
            prompt_template: "不可用".to_string(),
            default_period: "custom".to_string(),
            default_filters: "{}".to_string(),
            output_format: "Markdown".to_string(),
            enabled: false,
        }
    }

    fn test_ai_session() -> AiSession {
        AiSession {
            id: "session-1".to_string(),
            title: "项目周报".to_string(),
            selected_skill: Some("weekly_report".to_string()),
            source_filters: Some(r#"{"content_type":"text,notification"}"#.to_string()),
            exported_files: Vec::new(),
            created_at: 100,
            updated_at: 200,
        }
    }

    fn test_ai_message(id: &str, role: &str, content: &str, metadata: Option<&str>) -> AiMessage {
        AiMessage {
            id: id.to_string(),
            session_id: "session-1".to_string(),
            role: role.to_string(),
            content: content.to_string(),
            created_at: 150,
            metadata: metadata.map(str::to_string),
        }
    }

    fn test_ai_tool_call(id: &str, name: &str, result_json: serde_json::Value) -> AiToolCall {
        AiToolCall {
            id: id.to_string(),
            session_id: "session-1".to_string(),
            message_id: Some("message-user".to_string()),
            tool_name: name.to_string(),
            arguments_json: r#"{"content_type":"notification","limit":20}"#.to_string(),
            result_json: Some(result_json.to_string()),
            status: "completed".to_string(),
            created_at: 160,
            completed_at: Some(170),
        }
    }

    fn test_history_record(content_type: &str, metadata: serde_json::Value) -> HistoryRecord {
        HistoryRecord {
            id: "record-1".to_string(),
            from_device_id: "phone-1".to_string(),
            from_device_name: "手机".to_string(),
            content_type: content_type.to_string(),
            content: "项目群 下午三点同步".to_string(),
            sent_at: 1,
            received_at: 2,
            via: "server".to_string(),
            delivery_mode: "received".to_string(),
            metadata: Some(metadata.to_string()),
            favorite: false,
            pinned: false,
            tags: "工作".to_string(),
        }
    }

    #[test]
    fn ai_plan_parser_rejects_non_json_without_fallback() {
        let error = parse_ai_assistant_plan(
            "我建议先查询历史",
            true,
            &test_skills(),
        )
        .expect_err("non-json plans must fail");

        assert!(error.contains("不是严格 JSON"));
    }

    #[test]
    fn ai_plan_parser_requires_initial_tool_choice() {
        let error = parse_ai_assistant_plan(
            r#"{"skill_id":null,"final_answer_ready":false,"tools":[]}"#,
            true,
            &test_skills(),
        )
        .expect_err("initial plans without tools must fail");

        assert!(error.contains("没有选择任何工具"));
    }

    #[test]
    fn ai_plan_parser_rejects_unknown_tool_before_execution() {
        let error = parse_ai_assistant_plan(
            r#"{"skill_id":null,"final_answer_ready":false,"tools":[{"name":"guess_user_intent","arguments":{}}]}"#,
            true,
            &test_skills(),
        )
        .expect_err("unknown tools must fail");

        assert!(error.contains("不支持的工具"));
        assert!(error.contains("guess_user_intent"));
    }

    #[test]
    fn ai_plan_parser_rejects_disabled_or_missing_skill() {
        let error = parse_ai_assistant_plan(
            r#"{"skill_id":"daily_report","final_answer_ready":false,"tools":[{"name":"search_history_records","arguments":{}}]}"#,
            true,
            &test_skills(),
        )
        .expect_err("missing skills must fail");

        assert!(error.contains("Skill 不存在或已停用"));
    }

    #[test]
    fn ai_plan_parser_rejects_disabled_skill() {
        let mut skills = test_skills();
        skills.push(disabled_test_skill());
        let error = parse_ai_assistant_plan(
            r#"{"skill_id":"disabled_report","final_answer_ready":false,"tools":[{"name":"search_history_records","arguments":{}}]}"#,
            true,
            &skills,
        )
        .expect_err("disabled skills must fail");

        assert!(error.contains("Skill 不存在或已停用"));
    }

    #[test]
    fn ai_react_parser_allows_final_answer_when_observation_is_enough() {
        let plan = parse_ai_assistant_plan(
            r#"{"skill_id":null,"final_answer_ready":true,"tools":[]}"#,
            false,
            &test_skills(),
        )
        .expect("react can stop after observations");

        assert!(plan.final_answer_ready);
        assert!(plan.tools.is_empty());
    }

    #[test]
    fn ai_plan_parser_accepts_allowed_tool_and_enabled_skill() {
        let plan = parse_ai_assistant_plan(
            r#"{"skill_id":"weekly_report","final_answer_ready":false,"tools":[{"name":"search_notification_records","arguments":{"favorite":true,"pinned":true,"tag":"项目A"}}]}"#,
            true,
            &test_skills(),
        )
        .expect("allowed tools and enabled skills should pass");

        assert_eq!(plan.skill_id.as_deref(), Some("weekly_report"));
        assert_eq!(plan.tools[0].name, "search_notification_records");
        assert_eq!(plan.tools[0].arguments.favorite, Some(true));
        assert_eq!(plan.tools[0].arguments.pinned, Some(true));
        assert_eq!(plan.tools[0].arguments.tag.as_deref(), Some("项目A"));
    }

    #[test]
    fn skill_availability_requires_enabled_matching_id() {
        let skills = test_skills();
        assert!(skill_is_available(&skills[0], "weekly_report"));
        assert!(!skill_is_available(&skills[0], "monthly_report"));
        assert!(!skill_is_available(&disabled_test_skill(), "disabled_report"));
    }

    #[test]
    fn source_filters_serializer_requires_json_object() {
        let serialized = serialize_ai_source_filters(&serde_json::json!({
            "content_type": "notification",
            "limit": 20
        }))
        .expect("object filters should serialize");
        assert!(serialized.contains("\"content_type\":\"notification\""));

        let error = serialize_ai_source_filters(&serde_json::json!(["notification"]))
            .expect_err("arrays are not valid source filters");
        assert!(error.contains("source_filters 必须是 JSON 对象"));

        let error = serialize_ai_source_filters(&serde_json::json!("notification"))
            .expect_err("strings are not valid source filters");
        assert!(error.contains("source_filters 必须是 JSON 对象"));
    }

    #[test]
    fn ai_session_export_content_keeps_scope_message_metadata_and_tool_trace() {
        let session = test_ai_session();
        let messages = vec![
            test_ai_message("message-user", "user", "生成本周总结", None),
            test_ai_message(
                "message-assistant",
                "assistant",
                "本周完成项目推进。",
                Some(r#"{"skill_id":"weekly_report","record_count":2,"record_ids":["record-1","record-2"]}"#),
            ),
        ];
        let tool_calls = vec![test_ai_tool_call(
            "tool-1",
            "search_notification_records",
            serde_json::json!({
                "record_count": 2,
                "records": [{
                    "id": "record-1",
                    "metadata": {
                        "app_name": "微信",
                        "importance": 4
                    }
                }]
            }),
        )];

        let content = format_ai_session_export_content(&session, &messages, &tool_calls);

        assert!(content.contains("- 会话 ID: session-1"));
        assert!(content.contains("- Skill: weekly_report"));
        assert!(content.contains(r#"- 数据范围: {"content_type":"text,notification"}"#));
        assert!(content.contains("## 用户"));
        assert!(content.contains("## 助手"));
        assert!(content.contains("- 引用记录: 2 条"));
        assert!(content.contains("- 引用 ID: record-1, record-2"));
        assert!(content.contains("### search_notification_records"));
        assert!(content.contains("记录 2 条"));
        assert!(content.contains("metadata 样例: record-1: {\"app_name\":\"微信\",\"importance\":4}"));
    }

    #[test]
    fn ai_answer_export_content_keeps_references_metadata_and_tool_trace() {
        let session = test_ai_session();
        let assistant_message = test_ai_message(
            "message-assistant",
            "assistant",
            "本周完成项目推进。",
            Some(r#"{"skill_id":"weekly_report","record_count":1,"record_ids":["record-1"]}"#),
        );
        let records = vec![test_history_record(
            "notification",
            serde_json::json!({
                "app_name": "微信",
                "app_package": "com.tencent.mm",
                "title": "项目群",
                "text": "下午三点同步",
                "importance": 4,
                "icon": "base64-icon"
            }),
        )];
        let tool_calls = vec![test_ai_tool_call(
            "tool-1",
            "summarize_records",
            serde_json::json!({
                "record_count": 1,
                "summary": "项目群提醒下午三点同步。"
            }),
        )];

        let content = format_ai_answer_export_content(
            &session,
            "本周项目推进正常。",
            &assistant_message,
            &tool_calls,
            &records,
        );

        assert!(content.contains("## 回答"));
        assert!(content.contains("本周项目推进正常。"));
        assert!(content.contains("- 会话 ID: session-1"));
        assert!(content.contains("- 消息 ID: message-assistant"));
        assert!(content.contains("- Skill: weekly_report"));
        assert!(content.contains("- 引用记录: 1 条"));
        assert!(content.contains("## 引用记录"));
        assert!(content.contains("record-1 | notification | 手机"));
        assert!(content.contains("\"importance\":4"));
        assert!(!content.contains("base64-icon"));
        assert!(content.contains("## 工具调用"));
        assert!(content.contains("### summarize_records"));
        assert!(content.contains("摘要: 项目群提醒下午三点同步。"));
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            get_config,
            set_server_url,
            set_server_mode,
            set_input_mode,
            get_app_version,
            check_app_update,
            download_and_open_app_update,
            connect_server,
            disconnect_server,
            get_server_status,
            get_message_history,
            get_message_history_page,
            clear_message_history,
            delete_message_history_records,
            create_desktop_text_record,
            update_message_history_record,
            delete_message_history_record,
            delete_message_history_records_by_ids,
            set_message_history_favorite,
            set_message_history_favorite_by_ids,
            set_message_history_pinned,
            set_message_history_pinned_by_ids,
            set_message_history_tags,
            copy_message_history_image_record,
            open_history_record_file,
            open_history_record_folder,
            open_path,
            open_parent_folder,
            save_history_image_as,
            export_message_history,
            insert_text_to_cursor,
            copy_text_to_clipboard,
            export_ai_session_word,
            save_ai_model_config,
            list_ai_skills,
            save_ai_skill,
            export_ai_skills_json,
            import_ai_skills_json,
            create_ai_session,
            list_ai_sessions,
            add_ai_message,
            list_ai_messages,
            list_ai_tool_calls,
            list_ai_exported_files,
            record_ai_tool_call,
            search_history_records_tool,
            list_notification_apps,
            cancel_ai_assistant_request,
            run_ai_assistant,
            generate_encryption_key,
            set_encryption_key,
            send_encrypted_message,
            send_relay_payload,
            generate_pairing_qr,
            unpair_device
        ])
        .setup(|app| {
            let app_handle = app.handle().clone();

            if let Err(error) = history::init() {
                eprintln!("历史数据库初始化失败: {}", error);
            }
            if let Err(error) = storage::ai::init() {
                eprintln!("AI 数据库初始化失败: {}", error);
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
        .expect("运行 Tauri 应用时出错");
}

fn main() {
    // 初始化 Rustls 加密提供者
    use rustls::crypto::ring::default_provider;
    let _ = default_provider().install_default();

    run();
}
