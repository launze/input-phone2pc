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
    #[serde(skip_serializing, default)]
    pub metadata: Option<String>,
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
    pub start_at: Option<i64>,
    pub end_at: Option<i64>,
    pub limit: Option<usize>,
    pub before_received_at: Option<i64>,
    pub before_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryPage {
    pub records: Vec<HistoryRecord>,
    pub has_more: bool,
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

pub fn delete_record(id: &str) -> Result<()> {
    let conn = open_connection()?;
    let deleted = conn.execute("DELETE FROM message_history WHERE id = ?1", [id])?;
    if deleted == 0 {
        return Err(anyhow!("history record not found"));
    }
    Ok(())
}

pub fn export_csv(query: HistoryQuery) -> Result<String> {
    let records = list_records(HistoryQuery {
        start_at: query.start_at,
        end_at: query.end_at,
        limit: None,
        before_received_at: None,
        before_id: None,
    })?;

    let mut csv = String::from(
        "id,from_device_id,from_device_name,content_type,content,sent_at_iso,received_at_iso,via,delivery_mode\n",
    );

    for record in records {
        csv.push_str(&format!(
            "{},{},{},{},{},{},{},{},{}\n",
            escape_csv(&record.id),
            escape_csv(&record.from_device_id),
            escape_csv(&record.from_device_name),
            escape_csv(&record.content_type),
            escape_csv(&record.content),
            escape_csv(&format_timestamp(record.sent_at)),
            escape_csv(&format_timestamp(record.received_at)),
            escape_csv(&record.via),
            escape_csv(&record.delivery_mode),
        ));
    }

    Ok(csv)
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
            metadata
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
            metadata TEXT
        );

        CREATE INDEX IF NOT EXISTS idx_message_history_received_at
            ON message_history (received_at DESC);",
    )?;
    ensure_metadata_column(&conn)?;

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
            metadata
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

    if let Some(before_received_at) = query.before_received_at {
        let before_id = query.before_id.clone().unwrap_or_default();
        sql.push_str(" AND (received_at < ? OR (received_at = ? AND id < ?))");
        params.push(SqlValue::Integer(before_received_at));
        params.push(SqlValue::Integer(before_received_at));
        params.push(SqlValue::Text(before_id));
    }

    sql.push_str(" ORDER BY received_at DESC, id DESC");

    if let Some(limit) = query.limit {
        sql.push_str(" LIMIT ?");
        params.push(SqlValue::Integer(limit as i64));
    }

    (sql, params)
}
