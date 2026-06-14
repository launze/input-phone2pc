use anyhow::{anyhow, Result};
use chrono::{Local, TimeZone};
use rusqlite::types::Value as SqlValue;
use rusqlite::{params, params_from_iter, Connection};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryRecord {
    pub id: String,
    pub from_device_id: String,
    pub from_device_name: String,
    pub content_type: String,
    pub content: String,
    pub sent_at: i64,
    pub received_at: i64,
    pub via: String,
    pub delivery_mode: String,
    #[serde(default)]
    pub metadata: Option<String>,
    #[serde(default)]
    pub favorite: bool,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default)]
    pub tags: String,
}

#[derive(Debug, Clone)]
pub struct NewHistoryRecord {
    pub id: String,
    pub from_device_id: String,
    pub from_device_name: String,
    pub content_type: String,
    pub content: String,
    pub sent_at: i64,
    pub received_at: i64,
    pub via: String,
    pub delivery_mode: String,
    pub metadata: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct HistoryQuery {
    pub ids: Option<Vec<String>>,
    pub start_at: Option<i64>,
    pub end_at: Option<i64>,
    pub limit: Option<usize>,
    pub before_received_at: Option<i64>,
    pub before_id: Option<String>,
    pub search: Option<String>,
    pub content_type: Option<String>,
    pub via: Option<String>,
    pub from_device: Option<String>,
    pub source_app: Option<String>,
    pub delivery_status: Option<String>,
    pub favorite: Option<bool>,
    pub pinned: Option<bool>,
    pub tag: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationAppSummary {
    pub app_name: String,
    pub app_package: String,
    pub count: usize,
    pub latest_received_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryPage {
    pub records: Vec<HistoryRecord>,
    pub has_more: bool,
}

#[derive(Debug, Clone)]
pub struct HistoryExportBundle {
    pub content: String,
    pub title: &'static str,
    pub scope: &'static str,
}

pub fn init() -> Result<()> {
    let _ = open_connection()?;
    Ok(())
}

pub fn record_message(record: NewHistoryRecord) -> Result<(HistoryRecord, bool)> {
    let conn = open_connection()?;
    let inserted = conn.execute(
        "INSERT OR IGNORE INTO message_history (
            id,
            from_device_id,
            from_device_name,
            content_type,
            content,
            sent_at,
            received_at,
            via,
            delivery_mode,
            metadata
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
        params![
            record.id,
            record.from_device_id,
            record.from_device_name,
            record.content_type,
            record.content,
            record.sent_at,
            record.received_at,
            record.via,
            record.delivery_mode,
            record.metadata
        ],
    )?;

    let stored = get_record_by_id(&conn, &record.id)?
        .ok_or_else(|| anyhow!("history record missing after insert"))?;
    Ok((stored, inserted > 0))
}

pub fn list_records(query: HistoryQuery) -> Result<Vec<HistoryRecord>> {
    let conn = open_connection()?;
    let (sql, params) = build_list_query(&query);

    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params_from_iter(params), map_record_row)?;

    let mut result = Vec::new();
    for row in rows {
        result.push(row?);
    }

    Ok(result)
}

pub fn list_record_page(query: HistoryQuery) -> Result<HistoryPage> {
    let page_size = query.limit.unwrap_or(100).max(1);
    let mut page_query = query;
    page_query.limit = Some(page_size + 1);

    let mut records = list_records(page_query)?;
    let has_more = records.len() > page_size;
    if has_more {
        records.truncate(page_size);
    }

    Ok(HistoryPage { records, has_more })
}

pub fn clear_records() -> Result<()> {
    let conn = open_connection()?;
    conn.execute("DELETE FROM message_history", [])?;
    Ok(())
}

pub fn delete_records(query: HistoryQuery) -> Result<usize> {
    let records = list_records(HistoryQuery {
        ids: query.ids,
        start_at: query.start_at,
        end_at: query.end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
        search: query.search,
        content_type: query.content_type,
        via: query.via,
        from_device: query.from_device,
        source_app: query.source_app,
        delivery_status: query.delivery_status,
        favorite: query.favorite,
        pinned: query.pinned,
        tag: query.tag,
    })?;
    if records.is_empty() {
        return Ok(0);
    }

    let conn = open_connection()?;
    let tx = conn.unchecked_transaction()?;
    for record in &records {
        tx.execute("DELETE FROM message_history WHERE id = ?1", [&record.id])?;
    }
    tx.commit()?;
    Ok(records.len())
}

pub fn update_record_content(id: &str, content: &str) -> Result<HistoryRecord> {
    let normalized = content.trim();
    if normalized.is_empty() {
        return Err(anyhow!("content cannot be empty"));
    }

    let conn = open_connection()?;
    let updated = conn.execute(
        "UPDATE message_history
         SET content = ?2
         WHERE id = ?1",
        params![id, normalized],
    )?;

    if updated == 0 {
        return Err(anyhow!("history record not found"));
    }

    get_record_by_id(&conn, id)?.ok_or_else(|| anyhow!("history record missing after update"))
}

pub fn set_record_favorite(id: &str, favorite: bool) -> Result<HistoryRecord> {
    let conn = open_connection()?;
    let updated = conn.execute(
        "UPDATE message_history
         SET favorite = ?2
         WHERE id = ?1",
        params![id, if favorite { 1 } else { 0 }],
    )?;

    if updated == 0 {
        return Err(anyhow!("history record not found"));
    }

    get_record_by_id(&conn, id)?.ok_or_else(|| anyhow!("history record missing after favorite update"))
}

pub fn set_records_favorite_by_ids(ids: &[String], favorite: bool) -> Result<usize> {
    let Some((sql, params)) = build_flag_by_ids_query("favorite", ids, favorite) else {
        return Ok(0);
    };

    let conn = open_connection()?;
    conn.execute(&sql, params_from_iter(params)).map_err(Into::into)
}

pub fn set_record_pinned(id: &str, pinned: bool) -> Result<HistoryRecord> {
    let conn = open_connection()?;
    let updated = conn.execute(
        "UPDATE message_history
         SET pinned = ?2
         WHERE id = ?1",
        params![id, if pinned { 1 } else { 0 }],
    )?;

    if updated == 0 {
        return Err(anyhow!("history record not found"));
    }

    get_record_by_id(&conn, id)?.ok_or_else(|| anyhow!("history record missing after pinned update"))
}

pub fn set_records_pinned_by_ids(ids: &[String], pinned: bool) -> Result<usize> {
    let Some((sql, params)) = build_flag_by_ids_query("pinned", ids, pinned) else {
        return Ok(0);
    };

    let conn = open_connection()?;
    conn.execute(&sql, params_from_iter(params)).map_err(Into::into)
}

pub fn set_record_tags(id: &str, tags: &str) -> Result<HistoryRecord> {
    let normalized = normalize_tags(tags);
    let conn = open_connection()?;
    let updated = conn.execute(
        "UPDATE message_history
         SET tags = ?2
         WHERE id = ?1",
        params![id, normalized],
    )?;

    if updated == 0 {
        return Err(anyhow!("history record not found"));
    }

    get_record_by_id(&conn, id)?.ok_or_else(|| anyhow!("history record missing after tags update"))
}

pub fn delete_record(id: &str) -> Result<()> {
    let conn = open_connection()?;
    let deleted = conn.execute("DELETE FROM message_history WHERE id = ?1", [id])?;
    if deleted == 0 {
        return Err(anyhow!("history record not found"));
    }
    Ok(())
}

pub fn delete_records_by_ids(ids: &[String]) -> Result<usize> {
    let Some((sql, params)) = build_delete_by_ids_query(ids) else {
        return Ok(0);
    };

    let conn = open_connection()?;
    conn.execute(&sql, params_from_iter(params)).map_err(Into::into)
}

pub fn export_csv(query: HistoryQuery) -> Result<HistoryExportBundle> {
    let fallback_scope = export_scope_for_query(&query);
    let records = export_records(query)?;
    Ok(HistoryExportBundle {
        content: format_records_csv(&records),
        title: export_title_for_records(&records, fallback_scope),
        scope: export_scope_for_records(&records, fallback_scope),
    })
}

pub fn export_text(query: HistoryQuery) -> Result<HistoryExportBundle> {
    let fallback_scope = export_scope_for_query(&query);
    let records = export_records(query)?;
    let title = export_title_for_records(&records, fallback_scope);
    let scope = export_scope_for_records(&records, fallback_scope);
    let text = format_records_text(title, &records);
    Ok(HistoryExportBundle {
        content: text,
        title,
        scope,
    })
}

pub fn export_markdown(query: HistoryQuery) -> Result<HistoryExportBundle> {
    let fallback_scope = export_scope_for_query(&query);
    let records = export_records(query)?;
    let title = export_title_for_records(&records, fallback_scope);
    let scope = export_scope_for_records(&records, fallback_scope);
    let markdown = format_records_markdown(title, &records);
    Ok(HistoryExportBundle {
        content: markdown,
        title,
        scope,
    })
}

fn export_records(query: HistoryQuery) -> Result<Vec<HistoryRecord>> {
    list_records(HistoryQuery {
        ids: query.ids,
        start_at: query.start_at,
        end_at: query.end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
        search: query.search,
        content_type: query.content_type,
        via: query.via,
        from_device: query.from_device,
        source_app: query.source_app,
        delivery_status: query.delivery_status,
        favorite: query.favorite,
        pinned: query.pinned,
        tag: query.tag,
    })
}

fn format_records_text(title: &str, records: &[HistoryRecord]) -> String {
    let mut text = format!("语传{}\n\n", title);
    for record in records {
        let (source_app, source_package) = source_app_fields(&record);
        text.push_str(&format!(
            "[{}] {} | {} | {} | {} | 收藏:{} | 置顶:{}\n",
            format_timestamp(record.received_at),
            record.content_type,
            record.from_device_name,
            record.via,
            record.delivery_mode,
            if record.favorite { "是" } else { "否" },
            if record.pinned { "是" } else { "否" },
        ));
        if !source_app.is_empty() || !source_package.is_empty() {
            text.push_str(&format!("来源 App: {} {}\n", source_app, source_package));
        }
        if let Some(metadata) = export_metadata_summary(&record) {
            text.push_str(&format!("metadata: {}\n", metadata));
        }
        if !record.tags.trim().is_empty() {
            text.push_str(&format!("标签: {}\n", record.tags.trim()));
        }
        text.push_str(record.content.trim());
        text.push_str("\n\n");
    }
    text
}

fn format_records_markdown(title: &str, records: &[HistoryRecord]) -> String {
    let mut markdown = format!("# 语传{}\n\n", title);
    markdown.push_str(&format!("- 导出时间: {}\n", format_timestamp(Local::now().timestamp_millis())));
    markdown.push_str(&format!("- 记录数: {}\n\n", records.len()));
    for record in records {
        let (source_app, source_package) = source_app_fields(&record);
        markdown.push_str(&format!(
            "## {} · {}\n\n",
            format_timestamp(record.received_at),
            type_label(&record.content_type)
        ));
        markdown.push_str(&format!(
            "- ID: `{}`\n- 来源设备: {}\n- 通道: {}\n- 状态: {}\n- 收藏: {}\n- 置顶: {}\n",
            escape_markdown_inline(&record.id),
            escape_markdown_inline(&record.from_device_name),
            escape_markdown_inline(&record.via),
            escape_markdown_inline(&record.delivery_mode),
            if record.favorite { "是" } else { "否" },
            if record.pinned { "是" } else { "否" },
        ));
        if !source_app.is_empty() || !source_package.is_empty() {
            markdown.push_str(&format!(
                "- 来源 App: {} {}\n",
                escape_markdown_inline(&source_app),
                escape_markdown_inline(&source_package)
            ));
        }
        if let Some(metadata) = export_metadata_summary(&record) {
            markdown.push_str(&format!(
                "- metadata: `{}`\n",
                escape_markdown_inline(&metadata)
            ));
        }
        if !record.tags.trim().is_empty() {
            markdown.push_str(&format!("- 标签: {}\n", escape_markdown_inline(record.tags.trim())));
        }
        markdown.push_str("\n");
        markdown.push_str(&escape_markdown_content(record.content.trim()));
        markdown.push_str("\n\n");
    }
    markdown
}

fn format_records_csv(records: &[HistoryRecord]) -> String {
    let mut csv = String::from(
        "id,from_device_id,from_device_name,content_type,content,sent_at_iso,received_at_iso,via,delivery_mode,source_app,source_package,favorite,pinned,tags,metadata\n",
    );

    for record in records {
        let (source_app, source_package) = source_app_fields(record);
        csv.push_str(&format!(
            "{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}\n",
            escape_csv(&record.id),
            escape_csv(&record.from_device_id),
            escape_csv(&record.from_device_name),
            escape_csv(&record.content_type),
            escape_csv(&record.content),
            escape_csv(&format_timestamp(record.sent_at)),
            escape_csv(&format_timestamp(record.received_at)),
            escape_csv(&record.via),
            escape_csv(&record.delivery_mode),
            escape_csv(&source_app),
            escape_csv(&source_package),
            escape_csv(if record.favorite { "1" } else { "0" }),
            escape_csv(if record.pinned { "1" } else { "0" }),
            escape_csv(&record.tags),
            escape_csv(record.metadata.as_deref().unwrap_or_default()),
        ));
    }

    csv
}

fn export_title_for_records(records: &[HistoryRecord], fallback_scope: &'static str) -> &'static str {
    if !records.is_empty() && records.iter().all(|record| record.content_type == "notification") {
        "通知记录"
    } else if records.is_empty() && fallback_scope == "notifications" {
        "通知记录"
    } else {
        "历史记录"
    }
}

fn export_scope_for_records(records: &[HistoryRecord], fallback_scope: &'static str) -> &'static str {
    if !records.is_empty() && records.iter().all(|record| record.content_type == "notification") {
        "notifications"
    } else if records.is_empty() {
        fallback_scope
    } else {
        "history"
    }
}

fn export_scope_for_query(query: &HistoryQuery) -> &'static str {
    match query.content_type.as_deref().map(str::trim) {
        Some("notification") => "notifications",
        _ => "history",
    }
}

fn source_app_fields(record: &HistoryRecord) -> (String, String) {
    if record.content_type == "notification" {
        notification_app_from_metadata(record.metadata.as_deref())
    } else {
        (String::new(), String::new())
    }
}

fn type_label(content_type: &str) -> &'static str {
    match content_type {
        "text" => "文本",
        "image" => "图片",
        "file" => "文件",
        "notification" => "通知",
        _ => "记录",
    }
}

fn export_metadata_summary(record: &HistoryRecord) -> Option<String> {
    let metadata = record.metadata.as_deref()?;
    let parsed = serde_json::from_str::<serde_json::Value>(metadata).ok()?;
    let object = parsed.as_object()?;
    let allowed_keys: &[&str] = match record.content_type.as_str() {
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
            "is_ongoing",
            "is_clearable",
            "importance",
            "forward_mode",
            "notification_key",
        ],
        "image" => &["file_name", "mime_type", "width", "height", "size", "saved_path"],
        "file" => &["file_name", "mime_type", "size", "saved_path"],
        _ => &[],
    };
    if allowed_keys.is_empty() {
        return None;
    }

    let mut compact = serde_json::Map::new();
    for key in allowed_keys {
        if let Some(value) = object.get(*key).filter(|value| !value.is_null()) {
            compact.insert((*key).to_string(), compact_metadata_export_value(value));
        }
    }
    if compact.is_empty() {
        return None;
    }
    serde_json::to_string(&serde_json::Value::Object(compact)).ok()
}

fn compact_metadata_export_value(value: &serde_json::Value) -> serde_json::Value {
    match value {
        serde_json::Value::String(text) if text.chars().count() > 240 => {
            let truncated: String = text.chars().take(240).collect();
            serde_json::Value::String(format!("{truncated}..."))
        }
        _ => value.clone(),
    }
}

fn escape_markdown_inline(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('`', "\\`")
        .replace('[', "\\[")
        .replace(']', "\\]")
}

fn escape_markdown_content(value: &str) -> String {
    value.replace('\r', "").replace('\n', "\n\n")
}

pub fn list_notification_apps() -> Result<Vec<NotificationAppSummary>> {
    let records = list_records(HistoryQuery {
        ids: None,
        start_at: None,
        end_at: None,
        limit: None,
        before_received_at: None,
        before_id: None,
        search: None,
        content_type: Some("notification".to_string()),
        via: None,
        from_device: None,
        source_app: None,
        delivery_status: None,
        favorite: None,
        pinned: None,
        tag: None,
    })?;

    let mut apps = std::collections::BTreeMap::<String, NotificationAppSummary>::new();
    for record in records {
        let (app_name, app_package) = notification_app_from_metadata(record.metadata.as_deref());
        let key = if app_package.is_empty() {
            app_name.clone()
        } else {
            app_package.clone()
        };
        let entry = apps.entry(key).or_insert(NotificationAppSummary {
            app_name,
            app_package,
            count: 0,
            latest_received_at: 0,
        });
        entry.count += 1;
        entry.latest_received_at = entry.latest_received_at.max(record.received_at);
    }

    let mut result = apps.into_values().collect::<Vec<_>>();
    result.sort_by(|a, b| b.latest_received_at.cmp(&a.latest_received_at));
    Ok(result)
}

pub fn notification_app_from_metadata(metadata: Option<&str>) -> (String, String) {
    let parsed = metadata
        .and_then(|value| serde_json::from_str::<serde_json::Value>(value).ok())
        .unwrap_or(serde_json::Value::Null);
    let app_name = parsed
        .get("app_name")
        .and_then(|value| value.as_str())
        .unwrap_or("未知应用")
        .to_string();
    let app_package = parsed
        .get("app_package")
        .and_then(|value| value.as_str())
        .unwrap_or("")
        .to_string();
    (app_name, app_package)
}

fn get_record_by_id(conn: &Connection, id: &str) -> Result<Option<HistoryRecord>> {
    let mut stmt = conn.prepare(
        "SELECT
            id,
            from_device_id,
            from_device_name,
            content_type,
            content,
            sent_at,
            received_at,
            via,
            delivery_mode,
            metadata,
            favorite,
            pinned,
            tags
         FROM message_history
         WHERE id = ?1",
    )?;

    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(map_record_row(row)?))
    } else {
        Ok(None)
    }
}

fn map_record_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<HistoryRecord> {
    Ok(HistoryRecord {
        id: row.get(0)?,
        from_device_id: row.get(1)?,
        from_device_name: row.get(2)?,
        content_type: row.get(3)?,
        content: row.get(4)?,
        sent_at: row.get(5)?,
        received_at: row.get(6)?,
        via: row.get(7)?,
        delivery_mode: row.get(8)?,
        metadata: row.get(9)?,
        favorite: row.get::<_, i64>(10).unwrap_or(0) != 0,
        pinned: row.get::<_, i64>(11).unwrap_or(0) != 0,
        tags: row.get::<_, String>(12).unwrap_or_default(),
    })
}

pub fn get_record(id: &str) -> Result<Option<HistoryRecord>> {
    let conn = open_connection()?;
    get_record_by_id(&conn, id)
}

fn open_connection() -> Result<Connection> {
    let path = get_history_db_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }

    let conn = Connection::open(path)?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS message_history (
            id TEXT PRIMARY KEY,
            from_device_id TEXT NOT NULL,
            from_device_name TEXT NOT NULL,
            content_type TEXT NOT NULL,
            content TEXT NOT NULL,
            sent_at INTEGER NOT NULL,
            received_at INTEGER NOT NULL,
            via TEXT NOT NULL,
            delivery_mode TEXT NOT NULL,
            metadata TEXT,
            favorite INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0,
            tags TEXT NOT NULL DEFAULT ''
        );

        CREATE INDEX IF NOT EXISTS idx_message_history_received_at
            ON message_history (received_at DESC);",
    )?;
    ensure_metadata_column(&conn)?;
    ensure_favorite_column(&conn)?;
    ensure_pinned_column(&conn)?;
    ensure_tags_column(&conn)?;

    Ok(conn)
}

fn ensure_metadata_column(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(message_history)")?;
    let columns = stmt.query_map([], |row| row.get::<_, String>(1))?;

    for column in columns {
        if column? == "metadata" {
            return Ok(());
        }
    }

    conn.execute("ALTER TABLE message_history ADD COLUMN metadata TEXT", [])?;
    Ok(())
}

fn ensure_favorite_column(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(message_history)")?;
    let columns = stmt.query_map([], |row| row.get::<_, String>(1))?;

    for column in columns {
        if column? == "favorite" {
            return Ok(());
        }
    }

    conn.execute(
        "ALTER TABLE message_history ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",
        [],
    )?;
    Ok(())
}

fn ensure_pinned_column(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(message_history)")?;
    let columns = stmt.query_map([], |row| row.get::<_, String>(1))?;

    for column in columns {
        if column? == "pinned" {
            return Ok(());
        }
    }

    conn.execute(
        "ALTER TABLE message_history ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
        [],
    )?;
    Ok(())
}

fn ensure_tags_column(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(message_history)")?;
    let columns = stmt.query_map([], |row| row.get::<_, String>(1))?;

    for column in columns {
        if column? == "tags" {
            return Ok(());
        }
    }

    conn.execute(
        "ALTER TABLE message_history ADD COLUMN tags TEXT NOT NULL DEFAULT ''",
        [],
    )?;
    Ok(())
}

fn get_history_db_path() -> PathBuf {
    let base_dir = dirs::data_local_dir()
        .or_else(dirs::data_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    base_dir.join("voice-input").join("history.db")
}

fn format_timestamp(timestamp: i64) -> String {
    Local
        .timestamp_millis_opt(timestamp)
        .single()
        .map(|value| value.format("%Y-%m-%d %H:%M:%S").to_string())
        .unwrap_or_default()
}

fn escape_csv(value: &str) -> String {
    let escaped = value.replace('\"', "\"\"");
    format!("\"{}\"", escaped)
}

fn normalize_tags(value: &str) -> String {
    let mut tags = value
        .split([',', ';', '，', '；', '\n'])
        .map(str::trim)
        .filter(|tag| !tag.is_empty())
        .map(|tag| tag.trim_start_matches('#').trim().to_string())
        .filter(|tag| !tag.is_empty())
        .collect::<Vec<_>>();
    tags.sort();
    tags.dedup();
    tags.join(",")
}

fn split_filter_values(value: &str) -> Vec<String> {
    value
        .split([',', ';', '，', '；', '\n'])
        .map(str::trim)
        .filter(|item| !item.is_empty() && *item != "all")
        .map(str::to_string)
        .collect()
}

fn build_delete_by_ids_query(ids: &[String]) -> Option<(String, Vec<SqlValue>)> {
    let normalized = ids
        .iter()
        .map(|id| id.trim())
        .filter(|id| !id.is_empty())
        .collect::<Vec<_>>();
    if normalized.is_empty() {
        return None;
    }

    let placeholders = std::iter::repeat("?")
        .take(normalized.len())
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!("DELETE FROM message_history WHERE id IN ({})", placeholders);
    let params = normalized
        .into_iter()
        .map(|id| SqlValue::Text(id.to_string()))
        .collect::<Vec<_>>();
    Some((sql, params))
}

fn build_flag_by_ids_query(field: &str, ids: &[String], enabled: bool) -> Option<(String, Vec<SqlValue>)> {
    let field = match field {
        "favorite" | "pinned" => field,
        _ => return None,
    };
    let normalized = ids
        .iter()
        .map(|id| id.trim())
        .filter(|id| !id.is_empty())
        .collect::<Vec<_>>();
    if normalized.is_empty() {
        return None;
    }

    let placeholders = std::iter::repeat("?")
        .take(normalized.len())
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!("UPDATE message_history SET {field} = ? WHERE id IN ({placeholders})");
    let mut params = vec![SqlValue::Integer(if enabled { 1 } else { 0 })];
    params.extend(
        normalized
            .into_iter()
            .map(|id| SqlValue::Text(id.to_string())),
    );
    Some((sql, params))
}

fn build_list_query(query: &HistoryQuery) -> (String, Vec<SqlValue>) {
    let mut sql = String::from(
        "SELECT
            id,
            from_device_id,
            from_device_name,
            content_type,
            content,
            sent_at,
            received_at,
            via,
            delivery_mode,
            metadata,
            favorite,
            pinned,
            tags
         FROM message_history
         WHERE 1 = 1",
    );
    let mut params = Vec::<SqlValue>::new();

    if let Some(start_at) = query.start_at {
        sql.push_str(" AND received_at >= ?");
        params.push(SqlValue::Integer(start_at));
    }

    if let Some(end_at) = query.end_at {
        sql.push_str(" AND received_at <= ?");
        params.push(SqlValue::Integer(end_at));
    }

    if let Some(ids) = query.ids.as_ref().filter(|ids| !ids.is_empty()) {
        sql.push_str(" AND id IN (");
        sql.push_str(&std::iter::repeat("?")
            .take(ids.len())
            .collect::<Vec<_>>()
            .join(","));
        sql.push(')');
        params.extend(ids.iter().map(|id| SqlValue::Text(id.clone())));
    }

    if let Some(content_type) = query
        .content_type
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        let content_types = split_filter_values(content_type);
        if content_types.len() > 1 {
            sql.push_str(" AND content_type IN (");
            sql.push_str(&std::iter::repeat("?")
                .take(content_types.len())
                .collect::<Vec<_>>()
                .join(","));
            sql.push(')');
            params.extend(content_types.into_iter().map(SqlValue::Text));
        } else {
            sql.push_str(" AND content_type = ?");
            params.push(SqlValue::Text(content_type.to_string()));
        }
    }

    if let Some(via) = query
        .via
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        sql.push_str(" AND via = ?");
        params.push(SqlValue::Text(via.to_string()));
    }

    if let Some(from_device) = query
        .from_device
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        sql.push_str(" AND (from_device_name = ? OR from_device_id = ?)");
        params.push(SqlValue::Text(from_device.to_string()));
        params.push(SqlValue::Text(from_device.to_string()));
    }

    if let Some(source_app) = query
        .source_app
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        let pattern = format!("%{}%", source_app);
        sql.push_str(" AND content_type = 'notification' AND metadata LIKE ?");
        params.push(SqlValue::Text(pattern));
    }

    if let Some(delivery_status) = query
        .delivery_status
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        match delivery_status {
            "received" => {
                sql.push_str(" AND delivery_mode NOT IN ('manual', 'offline_sync')");
            }
            "manual" | "offline_sync" => {
                sql.push_str(" AND delivery_mode = ?");
                params.push(SqlValue::Text(delivery_status.to_string()));
            }
            _ => {}
        }
    }

    if let Some(favorite) = query.favorite {
        sql.push_str(" AND favorite = ?");
        params.push(SqlValue::Integer(if favorite { 1 } else { 0 }));
    }

    if let Some(pinned) = query.pinned {
        sql.push_str(" AND pinned = ?");
        params.push(SqlValue::Integer(if pinned { 1 } else { 0 }));
    }

    if let Some(tag) = query
        .tag
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "all")
    {
        let pattern = format!("%{}%", tag);
        sql.push_str(" AND tags LIKE ?");
        params.push(SqlValue::Text(pattern));
    }

    if let Some(search) = query
        .search
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        let pattern = format!("%{}%", search);
        sql.push_str(
            " AND (
                content LIKE ?
                OR from_device_name LIKE ?
                OR from_device_id LIKE ?
                OR via LIKE ?
                OR delivery_mode LIKE ?
                OR content_type LIKE ?
                OR tags LIKE ?
                OR metadata LIKE ?
            )",
        );
        for _ in 0..8 {
            params.push(SqlValue::Text(pattern.clone()));
        }
    }

    if let Some(before_received_at) = query.before_received_at {
        let before_id = query.before_id.clone().unwrap_or_default();
        sql.push_str(" AND (received_at < ? OR (received_at = ? AND id < ?))");
        params.push(SqlValue::Integer(before_received_at));
        params.push(SqlValue::Integer(before_received_at));
        params.push(SqlValue::Text(before_id));
    }

    sql.push_str(" ORDER BY pinned DESC, favorite DESC, received_at DESC, id DESC");

    if let Some(limit) = query.limit {
        sql.push_str(" LIMIT ?");
        params.push(SqlValue::Integer(limit as i64));
    }

    (sql, params)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(content_type: &str, metadata: serde_json::Value) -> HistoryRecord {
        HistoryRecord {
            id: "record-1".to_string(),
            from_device_id: "phone-1".to_string(),
            from_device_name: "手机".to_string(),
            content_type: content_type.to_string(),
            content: "content".to_string(),
            sent_at: 1,
            received_at: 2,
            via: "server".to_string(),
            delivery_mode: "received".to_string(),
            metadata: Some(metadata.to_string()),
            favorite: false,
            pinned: false,
            tags: String::new(),
        }
    }

    #[test]
    fn export_metadata_summary_keeps_notification_analysis_fields() {
        let summary = export_metadata_summary(&record(
            "notification",
            serde_json::json!({
                "app_name": "微信",
                "app_package": "com.tencent.mm",
                "title": "项目群",
                "text": "下午三点同步",
                "importance": 4,
                "channel_id": "message",
                "icon": "base64-icon"
            }),
        ))
        .expect("notification metadata should be summarized");

        assert!(summary.contains("\"importance\":4"));
        assert!(summary.contains("\"channel_id\":\"message\""));
        assert!(!summary.contains("base64-icon"));
        assert!(!summary.contains("\"icon\""));
    }

    #[test]
    fn export_metadata_summary_excludes_raw_image_data() {
        let summary = export_metadata_summary(&record(
            "image",
            serde_json::json!({
                "file_name": "shot.png",
                "mime_type": "image/png",
                "width": 1080,
                "height": 1920,
                "size": 12345,
                "data": "raw-base64",
                "image_data": "raw-image-base64"
            }),
        ))
        .expect("image metadata should be summarized");

        assert!(summary.contains("\"file_name\":\"shot.png\""));
        assert!(summary.contains("\"width\":1080"));
        assert!(!summary.contains("raw-base64"));
        assert!(!summary.contains("image_data"));
    }

    #[test]
    fn export_scope_uses_actual_notification_records() {
        let records = vec![record(
            "notification",
            serde_json::json!({
                "app_name": "微信",
                "app_package": "com.tencent.mm"
            }),
        )];

        assert_eq!(export_title_for_records(&records, "history"), "通知记录");
        assert_eq!(export_scope_for_records(&records, "history"), "notifications");
    }

    #[test]
    fn empty_notification_export_uses_query_scope() {
        let records: Vec<HistoryRecord> = Vec::new();

        assert_eq!(export_title_for_records(&records, "notifications"), "通知记录");
        assert_eq!(export_scope_for_records(&records, "notifications"), "notifications");
    }

    #[test]
    fn format_csv_keeps_notification_app_and_raw_metadata_columns() {
        let mut notification = record(
            "notification",
            serde_json::json!({
                "app_name": "微信",
                "app_package": "com.tencent.mm",
                "title": "项目群",
                "text": "下午三点同步",
                "importance": 4
            }),
        );
        notification.id = "notification-1".to_string();
        notification.content = "项目群: 下午三点同步".to_string();
        notification.favorite = true;
        notification.pinned = true;
        notification.tags = "工作,待办".to_string();

        let csv = format_records_csv(&[notification]);

        assert!(csv.starts_with("id,from_device_id,from_device_name,content_type,content"));
        assert!(csv.contains("source_app,source_package,favorite,pinned,tags,metadata"));
        assert!(csv.contains("\"微信\",\"com.tencent.mm\",\"1\",\"1\",\"工作,待办\""));
        assert!(csv.contains("\"\"app_name\"\""));
        assert!(csv.contains("\"\"微信\"\""));
        assert!(csv.contains("\"\"importance\"\":4"));
    }

    #[test]
    fn format_csv_quotes_commas_quotes_and_newlines() {
        let mut text_record = record("text", serde_json::json!({}));
        text_record.id = "text-1".to_string();
        text_record.content = "第一行,\"重点\"\n第二行".to_string();
        text_record.metadata = None;

        let csv = format_records_csv(&[text_record]);

        assert!(csv.contains("\"第一行,\"\"重点\"\"\n第二行\""));
    }

    #[test]
    fn text_export_body_includes_compact_metadata_without_raw_image_payload() {
        let mut image = record(
            "image",
            serde_json::json!({
                "file_name": "shot.png",
                "mime_type": "image/png",
                "width": 1080,
                "height": 1920,
                "size": 12345,
                "saved_path": "C:/Temp/shot.png",
                "image_data": "raw-image-base64"
            }),
        );
        image.content = "[图片] shot.png".to_string();
        image.tags = "截图".to_string();

        let text = format_records_text("历史记录", &[image]);

        assert!(text.contains("metadata: {\"file_name\":\"shot.png\""));
        assert!(text.contains("\"width\":1080"));
        assert!(text.contains("标签: 截图"));
        assert!(!text.contains("raw-image-base64"));
        assert!(!text.contains("image_data"));
    }

    #[test]
    fn markdown_export_body_escapes_notification_fields_and_uses_notification_title() {
        let mut notification = record(
            "notification",
            serde_json::json!({
                "app_name": "企业`微信",
                "app_package": "com.tencent.wework",
                "title": "项目[群]",
                "text": "下午三点同步",
                "channel_id": "message"
            }),
        );
        notification.id = "notification-[1]".to_string();
        notification.content = "[通知] 企业微信\n项目推进".to_string();
        notification.tags = "工作`重点".to_string();

        let title = export_title_for_records(&[notification.clone()], "history");
        let markdown = format_records_markdown(title, &[notification]);

        assert!(markdown.starts_with("# 语传通知记录"));
        assert!(markdown.contains("- ID: `notification-\\[1\\]`"));
        assert!(markdown.contains("- 来源 App: 企业\\`微信 com.tencent.wework"));
        assert!(markdown.contains("\"title\":\"项目\\[群\\]\""));
        assert!(markdown.contains("- 标签: 工作\\`重点"));
        assert!(markdown.contains("[通知] 企业微信\n\n项目推进"));
    }

    #[test]
    fn delete_by_ids_query_ignores_blank_ids() {
        let ids = vec![
            "first".to_string(),
            " ".to_string(),
            "second".to_string(),
        ];
        let (sql, params) = build_delete_by_ids_query(&ids).expect("query should be built");

        assert_eq!(sql, "DELETE FROM message_history WHERE id IN (?,?)");
        assert_eq!(
            params,
            vec![
                SqlValue::Text("first".to_string()),
                SqlValue::Text("second".to_string()),
            ]
        );
        assert!(build_delete_by_ids_query(&[" ".to_string()]).is_none());
    }

    #[test]
    fn flag_by_ids_query_ignores_blank_ids_and_validates_field() {
        let ids = vec![
            "first".to_string(),
            "".to_string(),
            "second".to_string(),
        ];
        let (sql, params) = build_flag_by_ids_query("favorite", &ids, true).expect("query should be built");
        let (pinned_sql, pinned_params) = build_flag_by_ids_query("pinned", &ids, false).expect("pinned query should be built");

        assert_eq!(sql, "UPDATE message_history SET favorite = ? WHERE id IN (?,?)");
        assert_eq!(
            params,
            vec![
                SqlValue::Integer(1),
                SqlValue::Text("first".to_string()),
                SqlValue::Text("second".to_string()),
            ]
        );
        assert_eq!(pinned_sql, "UPDATE message_history SET pinned = ? WHERE id IN (?,?)");
        assert_eq!(
            pinned_params,
            vec![
                SqlValue::Integer(0),
                SqlValue::Text("first".to_string()),
                SqlValue::Text("second".to_string()),
            ]
        );
        assert!(build_flag_by_ids_query("pinned", &[" ".to_string()], false).is_none());
        assert!(build_flag_by_ids_query("content", &ids, true).is_none());
    }

    #[test]
    fn list_query_supports_history_and_notification_productivity_filters() {
        let query = HistoryQuery {
            ids: Some(vec!["id-1".to_string(), "id-2".to_string()]),
            start_at: Some(100),
            end_at: Some(200),
            limit: Some(50),
            before_received_at: Some(180),
            before_id: Some("id-cursor".to_string()),
            search: Some("项目".to_string()),
            content_type: Some("text,image,file".to_string()),
            via: Some("server".to_string()),
            from_device: Some("phone-1".to_string()),
            source_app: Some("微信".to_string()),
            delivery_status: Some("manual".to_string()),
            favorite: Some(true),
            pinned: Some(false),
            tag: Some("工作".to_string()),
        };

        let (sql, params) = build_list_query(&query);

        assert!(sql.contains("received_at >= ?"));
        assert!(sql.contains("received_at <= ?"));
        assert!(sql.contains("id IN (?,?)"));
        assert!(sql.contains("content_type IN (?,?,?)"));
        assert!(sql.contains("via = ?"));
        assert!(sql.contains("(from_device_name = ? OR from_device_id = ?)"));
        assert!(sql.contains("content_type = 'notification' AND metadata LIKE ?"));
        assert!(sql.contains("delivery_mode = ?"));
        assert!(sql.contains("favorite = ?"));
        assert!(sql.contains("pinned = ?"));
        assert!(sql.contains("tags LIKE ?"));
        assert!(sql.contains("metadata LIKE ?"));
        assert!(sql.contains("(received_at < ? OR (received_at = ? AND id < ?))"));
        assert!(sql.ends_with("ORDER BY pinned DESC, favorite DESC, received_at DESC, id DESC LIMIT ?"));

        assert_eq!(
            params,
            vec![
                SqlValue::Integer(100),
                SqlValue::Integer(200),
                SqlValue::Text("id-1".to_string()),
                SqlValue::Text("id-2".to_string()),
                SqlValue::Text("text".to_string()),
                SqlValue::Text("image".to_string()),
                SqlValue::Text("file".to_string()),
                SqlValue::Text("server".to_string()),
                SqlValue::Text("phone-1".to_string()),
                SqlValue::Text("phone-1".to_string()),
                SqlValue::Text("%微信%".to_string()),
                SqlValue::Text("manual".to_string()),
                SqlValue::Integer(1),
                SqlValue::Integer(0),
                SqlValue::Text("%工作%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Text("%项目%".to_string()),
                SqlValue::Integer(180),
                SqlValue::Integer(180),
                SqlValue::Text("id-cursor".to_string()),
                SqlValue::Integer(50),
            ]
        );
    }

    #[test]
    fn list_query_received_status_excludes_manual_and_offline_without_param() {
        let query = HistoryQuery {
            ids: None,
            start_at: None,
            end_at: None,
            limit: None,
            before_received_at: None,
            before_id: None,
            search: None,
            content_type: None,
            via: None,
            from_device: None,
            source_app: None,
            delivery_status: Some("received".to_string()),
            favorite: None,
            pinned: None,
            tag: None,
        };

        let (sql, params) = build_list_query(&query);

        assert!(sql.contains("delivery_mode NOT IN ('manual', 'offline_sync')"));
        assert!(params.is_empty());
    }

    #[test]
    fn list_query_ignores_all_and_blank_filter_values() {
        let query = HistoryQuery {
            ids: None,
            start_at: None,
            end_at: None,
            limit: None,
            before_received_at: None,
            before_id: None,
            search: None,
            content_type: Some("all".to_string()),
            via: Some(" ".to_string()),
            from_device: Some("all".to_string()),
            source_app: Some("".to_string()),
            delivery_status: Some("unknown".to_string()),
            favorite: None,
            pinned: None,
            tag: Some("all".to_string()),
        };

        let (sql, params) = build_list_query(&query);

        assert!(!sql.contains("content_type = ?"));
        assert!(!sql.contains("via = ?"));
        assert!(!sql.contains("from_device_name = ?"));
        assert!(!sql.contains("metadata LIKE ?"));
        assert!(!sql.contains("delivery_mode = ?"));
        assert!(!sql.contains("tags LIKE ?"));
        assert!(params.is_empty());
    }
}
