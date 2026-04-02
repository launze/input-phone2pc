use anyhow::Result;
use rusqlite::{params, Connection};
use std::sync::Mutex;
use tracing::info;

pub struct PairingDb {
    conn: Mutex<Connection>,
}

#[derive(Debug, Clone)]
pub struct PendingRelayMessage {
    pub message_id: String,
    pub from_device_id: String,
    pub from_device_name: String,
    pub to_device_id: String,
    pub payload: serde_json::Value,
    pub stored_at: i64,
}

impl PairingDb {
    pub fn new(path: &str) -> Result<Self> {
        let conn = Connection::open(path)?;

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS pairings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device1_id TEXT NOT NULL,
                device1_name TEXT NOT NULL,
                device2_id TEXT NOT NULL,
                device2_name TEXT NOT NULL,
                paired_at INTEGER NOT NULL,
                UNIQUE(device1_id, device2_id)
            );

            CREATE TABLE IF NOT EXISTS relay_messages (
                message_id TEXT PRIMARY KEY,
                from_device_id TEXT NOT NULL,
                from_device_name TEXT NOT NULL,
                to_device_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_type TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                stored_at INTEGER NOT NULL,
                delivered_at INTEGER
            );

            CREATE INDEX IF NOT EXISTS idx_relay_messages_target_pending
                ON relay_messages (to_device_id, delivered_at, stored_at);",
        )?;

        info!("配对数据库初始化完成: {}", path);
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// 添加配对关系（双向存储）
    pub fn add_pairing(
        &self,
        device1_id: &str,
        device1_name: &str,
        device2_id: &str,
        device2_name: &str,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = chrono::Utc::now().timestamp();

        conn.execute(
            "INSERT OR REPLACE INTO pairings (device1_id, device1_name, device2_id, device2_name, paired_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            (device1_id, device1_name, device2_id, device2_name, now),
        )?;

        conn.execute(
            "INSERT OR REPLACE INTO pairings (device1_id, device1_name, device2_id, device2_name, paired_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            (device2_id, device2_name, device1_id, device1_name, now),
        )?;

        info!("添加配对: {} <-> {}", device1_id, device2_id);
        Ok(())
    }

    /// 获取某个设备的所有配对设备
    pub fn get_paired_devices(&self, device_id: &str) -> Result<Vec<(String, String)>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt =
            conn.prepare("SELECT device2_id, device2_name FROM pairings WHERE device1_id = ?1")?;

        let rows = stmt.query_map([device_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;

        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    /// 检查两个设备是否已配对
    pub fn is_paired(&self, device1_id: &str, device2_id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM pairings WHERE device1_id = ?1 AND device2_id = ?2",
            (device1_id, device2_id),
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    /// 移除配对关系（双向）
    pub fn remove_pairing(&self, device1_id: &str, device2_id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "DELETE FROM pairings WHERE device1_id = ?1 AND device2_id = ?2",
            (device1_id, device2_id),
        )?;
        conn.execute(
            "DELETE FROM pairings WHERE device1_id = ?1 AND device2_id = ?2",
            (device2_id, device1_id),
        )?;
        info!("移除配对: {} <-> {}", device1_id, device2_id);
        Ok(())
    }

    pub fn queue_relay_message(
        &self,
        message_id: &str,
        from_device_id: &str,
        from_device_name: &str,
        to_device_id: &str,
        payload: &serde_json::Value,
        sent_at: i64,
    ) -> Result<i64> {
        let conn = self.conn.lock().unwrap();
        let stored_at = chrono::Utc::now().timestamp_millis();
        let payload_json = serde_json::to_string(payload)?;
        let payload_type = payload
            .get("type")
            .and_then(|value| value.as_str())
            .unwrap_or("UNKNOWN");

        conn.execute(
            "INSERT OR REPLACE INTO relay_messages (
                message_id,
                from_device_id,
                from_device_name,
                to_device_id,
                payload_json,
                payload_type,
                sent_at,
                stored_at,
                delivered_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, NULL)",
            params![
                message_id,
                from_device_id,
                from_device_name,
                to_device_id,
                payload_json,
                payload_type,
                sent_at,
                stored_at
            ],
        )?;

        Ok(stored_at)
    }

    pub fn get_pending_messages(&self, to_device_id: &str) -> Result<Vec<PendingRelayMessage>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT
                message_id,
                from_device_id,
                from_device_name,
                to_device_id,
                payload_json,
                stored_at
             FROM relay_messages
             WHERE to_device_id = ?1 AND delivered_at IS NULL
             ORDER BY stored_at ASC",
        )?;

        let rows = stmt.query_map([to_device_id], |row| {
            let payload_json: String = row.get(4)?;
            let payload: serde_json::Value =
                serde_json::from_str(&payload_json).map_err(|error| {
                    rusqlite::Error::FromSqlConversionFailure(
                        4,
                        rusqlite::types::Type::Text,
                        Box::new(error),
                    )
                })?;

            Ok(PendingRelayMessage {
                message_id: row.get(0)?,
                from_device_id: row.get(1)?,
                from_device_name: row.get(2)?,
                to_device_id: row.get(3)?,
                payload,
                stored_at: row.get(5)?,
            })
        })?;

        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    pub fn mark_message_delivered(&self, message_id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let delivered_at = chrono::Utc::now().timestamp_millis();
        let updated = conn.execute(
            "UPDATE relay_messages
             SET delivered_at = ?2
             WHERE message_id = ?1 AND delivered_at IS NULL",
            params![message_id, delivered_at],
        )?;
        Ok(updated > 0)
    }
}
