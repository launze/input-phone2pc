use anyhow::{anyhow, Result};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiSkill {
    pub id: String,
    pub name: String,
    pub description: String,
    #[serde(default)]
    pub input_schema: String,
    pub prompt_template: String,
    #[serde(default)]
    pub default_period: String,
    #[serde(default)]
    pub default_filters: String,
    #[serde(default)]
    pub output_format: String,
    #[serde(default = "default_skill_enabled")]
    pub enabled: bool,
}

fn default_skill_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiSession {
    pub id: String,
    pub title: String,
    pub selected_skill: Option<String>,
    pub source_filters: Option<String>,
    #[serde(default)]
    pub exported_files: Vec<AiExportedFile>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiMessage {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub created_at: i64,
    pub metadata: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiToolCall {
    pub id: String,
    pub session_id: String,
    pub message_id: Option<String>,
    pub tool_name: String,
    pub arguments_json: String,
    pub result_json: Option<String>,
    pub status: String,
    pub created_at: i64,
    pub completed_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiExportedFile {
    pub id: String,
    pub session_id: String,
    pub message_id: Option<String>,
    pub file_type: String,
    pub filename: String,
    pub saved_path: String,
    pub created_at: i64,
}

pub fn init() -> Result<()> {
    let conn = open_connection()?;
    ensure_default_skills(&conn)?;
    Ok(())
}

pub fn list_skills() -> Result<Vec<AiSkill>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, name, description, input_schema, prompt_template, default_period, default_filters, output_format, enabled
         FROM ai_skills
         ORDER BY id ASC",
    )?;
    let rows = stmt.query_map([], map_skill_row)?;
    collect_rows(rows)
}

pub fn upsert_skill(skill: AiSkill) -> Result<AiSkill> {
    let conn = open_connection()?;
    conn.execute(
        "INSERT INTO ai_skills (
            id, name, description, input_schema, prompt_template, default_period, default_filters, output_format, enabled
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            description = excluded.description,
            input_schema = excluded.input_schema,
            prompt_template = excluded.prompt_template,
            default_period = excluded.default_period,
            default_filters = excluded.default_filters,
            output_format = excluded.output_format,
            enabled = excluded.enabled",
        params![
            skill.id,
            skill.name,
            skill.description,
            skill.input_schema,
            skill.prompt_template,
            skill.default_period,
            skill.default_filters,
            skill.output_format,
            if skill.enabled { 1 } else { 0 },
        ],
    )?;
    get_skill(&skill.id)?.ok_or_else(|| anyhow!("skill missing after save"))
}

pub fn get_skill(id: &str) -> Result<Option<AiSkill>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, name, description, input_schema, prompt_template, default_period, default_filters, output_format, enabled
         FROM ai_skills
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(map_skill_row(row)?))
    } else {
        Ok(None)
    }
}

pub fn create_session(
    title: String,
    selected_skill: Option<String>,
    source_filters: Option<String>,
) -> Result<AiSession> {
    let conn = open_connection()?;
    let now = chrono::Utc::now().timestamp_millis();
    let id = uuid::Uuid::new_v4().to_string();
    let title = title.trim();
    let title = if title.is_empty() { "新会话" } else { title };
    conn.execute(
        "INSERT INTO ai_sessions (
            id, title, selected_skill, source_filters, created_at, updated_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, title, selected_skill, source_filters, now, now],
    )?;
    get_session(&id)?.ok_or_else(|| anyhow!("session missing after create"))
}

pub fn list_sessions(limit: Option<usize>) -> Result<Vec<AiSession>> {
    let conn = open_connection()?;
    let sql = if limit.is_some() {
        "SELECT id, title, selected_skill, source_filters, created_at, updated_at
         FROM ai_sessions
         ORDER BY updated_at DESC
         LIMIT ?1"
            .to_string()
    } else {
        "SELECT id, title, selected_skill, source_filters, created_at, updated_at
         FROM ai_sessions
         ORDER BY updated_at DESC"
            .to_string()
    };
    let mut stmt = conn.prepare(&sql)?;
    let mut sessions = if let Some(limit) = limit {
        collect_rows(stmt.query_map([limit as i64], map_session_row)?)?
    } else {
        collect_rows(stmt.query_map([], map_session_row)?)?
    };
    hydrate_exported_files(&mut sessions)?;
    Ok(sessions)
}

pub fn get_session(id: &str) -> Result<Option<AiSession>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, title, selected_skill, source_filters, created_at, updated_at
         FROM ai_sessions
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        let mut session = map_session_row(row)?;
        session.exported_files = list_exported_files(&session.id)?;
        Ok(Some(session))
    } else {
        Ok(None)
    }
}

pub fn update_session_metadata(
    id: &str,
    title: Option<String>,
    selected_skill: Option<String>,
    source_filters: Option<String>,
) -> Result<AiSession> {
    let conn = open_connection()?;
    let existing = get_session(id)?.ok_or_else(|| anyhow!("session not found"))?;
    let now = chrono::Utc::now().timestamp_millis();
    let title = title
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(&existing.title)
        .to_string();
    let selected_skill = selected_skill.or(existing.selected_skill);
    let source_filters = source_filters.or(existing.source_filters);

    conn.execute(
        "UPDATE ai_sessions
         SET title = ?2, selected_skill = ?3, source_filters = ?4, updated_at = ?5
         WHERE id = ?1",
        params![id, title, selected_skill, source_filters, now],
    )?;
    get_session(id)?.ok_or_else(|| anyhow!("session missing after update"))
}

pub fn add_message(
    session_id: String,
    role: String,
    content: String,
    metadata: Option<String>,
) -> Result<AiMessage> {
    let conn = open_connection()?;
    let now = chrono::Utc::now().timestamp_millis();
    let id = uuid::Uuid::new_v4().to_string();
    conn.execute(
        "INSERT INTO ai_messages (
            id, session_id, role, content, created_at, metadata
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, session_id, role, content, now, metadata],
    )?;
    touch_session(&conn, &session_id, now)?;
    get_message(&id)?.ok_or_else(|| anyhow!("message missing after create"))
}

pub fn list_messages(session_id: &str) -> Result<Vec<AiMessage>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, role, content, created_at, metadata
         FROM ai_messages
         WHERE session_id = ?1
         ORDER BY created_at ASC, id ASC",
    )?;
    let result = collect_rows(stmt.query_map([session_id], map_message_row)?);
    result
}

pub fn add_tool_call(
    session_id: String,
    message_id: Option<String>,
    tool_name: String,
    arguments_json: String,
    result_json: Option<String>,
    status: String,
) -> Result<AiToolCall> {
    let conn = open_connection()?;
    let now = chrono::Utc::now().timestamp_millis();
    let id = uuid::Uuid::new_v4().to_string();
    let completed_at = if status == "completed" { Some(now) } else { None };
    conn.execute(
        "INSERT INTO ai_tool_calls (
            id, session_id, message_id, tool_name, arguments_json, result_json,
            status, created_at, completed_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            id,
            session_id,
            message_id,
            tool_name,
            arguments_json,
            result_json,
            status,
            now,
            completed_at
        ],
    )?;
    touch_session(&conn, &session_id, now)?;
    get_tool_call(&id)?.ok_or_else(|| anyhow!("tool call missing after create"))
}

pub fn list_tool_calls(session_id: &str) -> Result<Vec<AiToolCall>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, message_id, tool_name, arguments_json, result_json,
            status, created_at, completed_at
         FROM ai_tool_calls
         WHERE session_id = ?1
         ORDER BY created_at ASC, id ASC",
    )?;
    let result = collect_rows(stmt.query_map([session_id], map_tool_call_row)?);
    result
}

pub fn add_exported_file(
    session_id: String,
    message_id: Option<String>,
    file_type: String,
    filename: String,
    saved_path: String,
) -> Result<AiExportedFile> {
    let conn = open_connection()?;
    let now = chrono::Utc::now().timestamp_millis();
    let id = uuid::Uuid::new_v4().to_string();
    conn.execute(
        "INSERT INTO ai_exported_files (
            id, session_id, message_id, file_type, filename, saved_path, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![id, session_id, message_id, file_type, filename, saved_path, now],
    )?;
    touch_session(&conn, &session_id, now)?;
    get_exported_file(&id)?.ok_or_else(|| anyhow!("exported file missing after create"))
}

pub fn list_exported_files(session_id: &str) -> Result<Vec<AiExportedFile>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, message_id, file_type, filename, saved_path, created_at
         FROM ai_exported_files
         WHERE session_id = ?1
         ORDER BY created_at DESC, id DESC",
    )?;
    let result = collect_rows(stmt.query_map([session_id], map_exported_file_row)?);
    result
}

fn get_message(id: &str) -> Result<Option<AiMessage>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, role, content, created_at, metadata
         FROM ai_messages
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(map_message_row(row)?))
    } else {
        Ok(None)
    }
}

fn get_tool_call(id: &str) -> Result<Option<AiToolCall>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, message_id, tool_name, arguments_json, result_json,
            status, created_at, completed_at
         FROM ai_tool_calls
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(map_tool_call_row(row)?))
    } else {
        Ok(None)
    }
}

fn get_exported_file(id: &str) -> Result<Option<AiExportedFile>> {
    let conn = open_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, message_id, file_type, filename, saved_path, created_at
         FROM ai_exported_files
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(map_exported_file_row(row)?))
    } else {
        Ok(None)
    }
}

fn hydrate_exported_files(sessions: &mut [AiSession]) -> Result<()> {
    for session in sessions {
        session.exported_files = list_exported_files(&session.id)?;
    }
    Ok(())
}

fn open_connection() -> Result<Connection> {
    let path = get_ai_db_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let conn = Connection::open(path)?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS ai_skills (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            input_schema TEXT NOT NULL DEFAULT '{}',
            prompt_template TEXT NOT NULL,
            default_period TEXT NOT NULL,
            default_filters TEXT NOT NULL,
            output_format TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1
        );

        CREATE TABLE IF NOT EXISTS ai_sessions (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            selected_skill TEXT,
            source_filters TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS ai_messages (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            metadata TEXT
        );

        CREATE TABLE IF NOT EXISTS ai_tool_calls (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            message_id TEXT,
            tool_name TEXT NOT NULL,
            arguments_json TEXT NOT NULL,
            result_json TEXT,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            completed_at INTEGER
        );

        CREATE TABLE IF NOT EXISTS ai_exported_files (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            message_id TEXT,
            file_type TEXT NOT NULL,
            filename TEXT NOT NULL,
            saved_path TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_ai_sessions_updated_at
            ON ai_sessions (updated_at DESC);
        CREATE INDEX IF NOT EXISTS idx_ai_messages_session_id
            ON ai_messages (session_id, created_at ASC);
        CREATE INDEX IF NOT EXISTS idx_ai_tool_calls_session_id
            ON ai_tool_calls (session_id, created_at ASC);
        CREATE INDEX IF NOT EXISTS idx_ai_exported_files_session_id
            ON ai_exported_files (session_id, created_at DESC);",
    )?;
    ensure_ai_skill_column(&conn, "input_schema", "TEXT NOT NULL DEFAULT '{}'")?;
    ensure_ai_skill_column(&conn, "output_format", "TEXT NOT NULL DEFAULT ''")?;
    Ok(conn)
}

fn ensure_ai_skill_column(conn: &Connection, column_name: &str, column_type: &str) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(ai_skills)")?;
    let columns = stmt.query_map([], |row| row.get::<_, String>(1))?;
    for column in columns {
        if column? == column_name {
            return Ok(());
        }
    }
    conn.execute(
        &format!("ALTER TABLE ai_skills ADD COLUMN {column_name} {column_type}"),
        [],
    )?;
    Ok(())
}

fn get_ai_db_path() -> PathBuf {
    let base_dir = dirs::data_local_dir()
        .or_else(dirs::data_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    base_dir.join("voice-input").join("ai.db")
}

fn ensure_default_skills(conn: &Connection) -> Result<()> {
    for skill in default_skills() {
        conn.execute(
            "INSERT OR IGNORE INTO ai_skills (
                id, name, description, input_schema, prompt_template, default_period, default_filters, output_format, enabled
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                skill.id,
                skill.name,
                skill.description,
                skill.input_schema,
                skill.prompt_template,
                skill.default_period,
                skill.default_filters,
                skill.output_format,
                if skill.enabled { 1 } else { 0 }
            ],
        )?;
        conn.execute(
            "UPDATE ai_skills
             SET input_schema = ?2
             WHERE id = ?1 AND TRIM(COALESCE(input_schema, '')) = ''",
            params![skill.id, skill.input_schema],
        )?;
        conn.execute(
            "UPDATE ai_skills
             SET output_format = ?2
             WHERE id = ?1 AND TRIM(COALESCE(output_format, '')) = ''",
            params![skill.id, skill.output_format],
        )?;
        conn.execute(
            "UPDATE ai_skills
             SET default_filters = ?2
             WHERE id = ?1 AND TRIM(COALESCE(default_filters, '{}')) = '{}'",
            params![skill.id, skill.default_filters],
        )?;
    }
    Ok(())
}

fn default_skills() -> Vec<AiSkill> {
    vec![
        skill(
            "weekly_report",
            "周报",
            "基于历史输入和通知生成本周工作周报。",
            r#"{"required":["start_at","end_at"],"optional":["search","source_app","from_device","tag"]}"#,
            "请基于以下记录生成结构清晰的中文周报，包含总览、主要工作、待跟进事项和风险阻塞。只能依据记录，不要虚构。\n\n{{records}}",
            "week",
            r#"{"content_type":"text,image,file,notification"}"#,
            "Markdown：总览、主要工作、待跟进事项、风险阻塞。",
        ),
        skill(
            "monthly_report",
            "月报",
            "基于历史输入和通知生成本月工作月报。",
            r#"{"required":["start_at","end_at"],"optional":["search","source_app","from_device","tag"]}"#,
            "请基于以下记录生成中文月报，包含本月目标推进、关键成果、协作沟通、待办事项、风险阻塞和下月计划。只能依据记录，不要虚构。\n\n{{records}}",
            "month",
            r#"{"content_type":"text,image,file,notification"}"#,
            "Markdown：目标推进、关键成果、协作沟通、待办、风险、下月计划。",
        ),
        skill(
            "quarterly_report",
            "季报",
            "基于历史输入和通知生成季度工作总结。",
            r#"{"required":["start_at","end_at"],"optional":["search","source_app","from_device","tag"]}"#,
            "请基于以下记录生成季度工作总结，包含季度重点、阶段成果、跨项目协作、遗留问题、风险和下一季度计划。只能依据记录，不要虚构。\n\n{{records}}",
            "quarter",
            r#"{"content_type":"text,image,file,notification"}"#,
            "Markdown：季度重点、阶段成果、协作、遗留问题、风险、下一季度计划。",
        ),
        skill(
            "half_year_report",
            "半年报",
            "基于历史输入和通知生成半年工作复盘。",
            r#"{"required":["start_at","end_at"],"optional":["search","source_app","from_device","tag"]}"#,
            "请基于以下记录生成半年工作复盘，包含核心产出、项目进展、能力沉淀、问题风险、改进建议和下半年计划。只能依据记录，不要虚构。\n\n{{records}}",
            "half_year",
            r#"{"content_type":"text,image,file,notification"}"#,
            "Markdown：核心产出、项目进展、能力沉淀、问题风险、改进建议、下半年计划。",
        ),
        skill(
            "yearly_report",
            "年报",
            "基于历史输入和通知生成年度工作总结。",
            r#"{"required":["start_at","end_at"],"optional":["search","source_app","from_device","tag"]}"#,
            "请基于以下记录生成年度工作总结，包含年度概览、关键成果、重要项目、协作与影响、问题风险、经验沉淀和明年规划。只能依据记录，不要虚构。\n\n{{records}}",
            "year",
            r#"{"content_type":"text,image,file,notification"}"#,
            "Markdown：年度概览、关键成果、重要项目、协作影响、风险、经验、明年规划。",
        ),
        skill(
            "meeting_notes_summary",
            "会议纪要",
            "从历史输入和通知中整理会议结论与行动项。",
            r#"{"optional":["search","start_at","end_at","source_app","from_device","tag"]}"#,
            "请从以下记录中整理会议纪要，包含背景、关键讨论、已确认结论、行动项、负责人、截止时间和待确认问题。只能依据记录，不要虚构。\n\n{{records}}",
            "custom",
            r#"{"content_type":"text,notification"}"#,
            "Markdown：背景、关键讨论、结论、行动项表格、待确认问题。",
        ),
        skill(
            "daily_notification_digest",
            "通知日报",
            "汇总今天通知中的工作相关信息和待办。",
            r#"{"required":["content_type=notification"],"optional":["source_app","search","tag"]}"#,
            "请从以下通知和输入记录中提取今天的工作相关信息、待办、需要跟进的人和事项。\n\n{{records}}",
            "day",
            r#"{"content_type":"notification"}"#,
            "Markdown：工作相关通知、待办、跟进对象、非工作/不确定项。",
        ),
        skill(
            "todo_extractor",
            "待办提取",
            "从输入历史和通知消息中提取待办事项。",
            r#"{"optional":["search","start_at","end_at","source_app","from_device","tag"]}"#,
            "请从以下记录中提取明确待办、潜在待办、截止时间、相关联系人，并按优先级整理。\n\n{{records}}",
            "custom",
            r#"{"content_type":"text,notification"}"#,
            "Markdown 表格：事项、来源、负责人/联系人、截止时间、优先级、依据记录。",
        ),
        skill(
            "project_progress_summary",
            "项目进展总结",
            "按关键词或项目名总结相关进展。",
            r#"{"required":["search"],"optional":["start_at","end_at","source_app","from_device","tag"]}"#,
            "请围绕用户指定的项目或关键词，总结以下记录中的项目进展、问题、风险和下一步。\n\n{{records}}",
            "custom",
            r#"{"content_type":"text,notification"}"#,
            "Markdown：项目概览、进展、问题风险、下一步、引用依据。",
        ),
    ]
}

fn skill(
    id: &str,
    name: &str,
    description: &str,
    input_schema: &str,
    prompt_template: &str,
    period: &str,
    default_filters: &str,
    output_format: &str,
) -> AiSkill {
    AiSkill {
        id: id.to_string(),
        name: name.to_string(),
        description: description.to_string(),
        input_schema: input_schema.to_string(),
        prompt_template: prompt_template.to_string(),
        default_period: period.to_string(),
        default_filters: default_filters.to_string(),
        output_format: output_format.to_string(),
        enabled: true,
    }
}

fn touch_session(conn: &Connection, session_id: &str, updated_at: i64) -> Result<()> {
    conn.execute(
        "UPDATE ai_sessions SET updated_at = ?2 WHERE id = ?1",
        params![session_id, updated_at],
    )?;
    Ok(())
}

fn map_skill_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<AiSkill> {
    Ok(AiSkill {
        id: row.get(0)?,
        name: row.get(1)?,
        description: row.get(2)?,
        input_schema: row.get(3)?,
        prompt_template: row.get(4)?,
        default_period: row.get(5)?,
        default_filters: row.get(6)?,
        output_format: row.get(7)?,
        enabled: row.get::<_, i64>(8)? != 0,
    })
}

fn map_session_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<AiSession> {
    Ok(AiSession {
        id: row.get(0)?,
        title: row.get(1)?,
        selected_skill: row.get(2)?,
        source_filters: row.get(3)?,
        exported_files: Vec::new(),
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
    })
}

fn map_message_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<AiMessage> {
    Ok(AiMessage {
        id: row.get(0)?,
        session_id: row.get(1)?,
        role: row.get(2)?,
        content: row.get(3)?,
        created_at: row.get(4)?,
        metadata: row.get(5)?,
    })
}

fn map_tool_call_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<AiToolCall> {
    Ok(AiToolCall {
        id: row.get(0)?,
        session_id: row.get(1)?,
        message_id: row.get(2)?,
        tool_name: row.get(3)?,
        arguments_json: row.get(4)?,
        result_json: row.get(5)?,
        status: row.get(6)?,
        created_at: row.get(7)?,
        completed_at: row.get(8)?,
    })
}

fn map_exported_file_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<AiExportedFile> {
    Ok(AiExportedFile {
        id: row.get(0)?,
        session_id: row.get(1)?,
        message_id: row.get(2)?,
        file_type: row.get(3)?,
        filename: row.get(4)?,
        saved_path: row.get(5)?,
        created_at: row.get(6)?,
    })
}

fn collect_rows<T, E>(rows: impl Iterator<Item = std::result::Result<T, E>>) -> Result<Vec<T>>
where
    E: Into<anyhow::Error>,
{
    let mut result = Vec::new();
    for row in rows {
        result.push(row.map_err(Into::into)?);
    }
    Ok(result)
}
