use anyhow::Result;
use rusqlite::Connection;
use std::sync::Mutex;
use tracing::info;

pub struct PairingDb {
    conn: Mutex<Connection>,
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
            );"
        )?;

        info!("配对数据库初始化完成: {}", path);
        Ok(Self { conn: Mutex::new(conn) })
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
        let mut stmt = conn.prepare(
            "SELECT device2_id, device2_name FROM pairings WHERE device1_id = ?1"
        )?;

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
}
