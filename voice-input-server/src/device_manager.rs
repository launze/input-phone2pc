use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
pub struct Device {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub session_id: String,
    pub last_seen: i64,
    pub paired_devices: Vec<String>,
    pub encryption_key: Option<String>, // 用于端到端加密的密钥
}

/// 设备管理器配置
#[derive(Debug, Clone)]
pub struct ManagerConfig {
    /// 设备超时时间（秒）- 超过此时间未收到心跳则认为离线
    pub device_timeout_secs: i64,
    /// 清理间隔（秒）- 多久执行一次过期设备清理
    pub cleanup_interval_secs: u64,
    /// 最大设备数量限制 - 防止无限增长
    pub max_devices: usize,
}

impl Default for ManagerConfig {
    fn default() -> Self {
        Self {
            device_timeout_secs: 120,  // 2分钟
            cleanup_interval_secs: 30, // 每30秒清理一次
            max_devices: 10000,        // 最多10000个设备
        }
    }
}

pub struct DeviceManager {
    // device_id -> Device
    devices: Arc<DashMap<String, Device>>,
    // device_id -> WebSocket sender
    connections: Arc<DashMap<String, mpsc::UnboundedSender<Message>>>,
    // device_id -> paired device IDs
    paired_devices: Arc<DashMap<String, Vec<String>>>,
    // 配置参数
    config: ManagerConfig,
}

impl DeviceManager {
    pub fn new() -> Self {
        Self::with_config(ManagerConfig::default())
    }

    pub fn with_config(config: ManagerConfig) -> Self {
        Self {
            devices: Arc::new(DashMap::new()),
            connections: Arc::new(DashMap::new()),
            paired_devices: Arc::new(DashMap::new()),
            config,
        }
    }

    pub fn register_device(
        &self,
        device_id: String,
        device_name: String,
        device_type: String,
        session_id: String,
        sender: mpsc::UnboundedSender<Message>,
    ) -> Result<(), String> {
        // 检查是否超过最大设备数量
        if self.devices.len() >= self.config.max_devices {
            warn!(
                "⚠️ 设备数量已达上限 ({}/{}), 拒绝新设备注册: {}",
                self.devices.len(),
                self.config.max_devices,
                device_id
            );
            return Err(format!(
                "Server full: {} devices connected",
                self.devices.len()
            ));
        }

        let device = Device {
            device_id: device_id.clone(),
            device_name: device_name.clone(),
            device_type: device_type.clone(),
            session_id,
            last_seen: chrono::Utc::now().timestamp(),
            paired_devices: Vec::new(),
            encryption_key: None,
        };

        self.devices.insert(device_id.clone(), device);
        self.connections.insert(device_id.clone(), sender);

        info!(
            "✅ 设备已注册: {} ({}) [{}] - 当前在线: {}",
            device_name,
            device_id,
            device_type,
            self.devices.len()
        );

        Ok(())
    }

    /// 注销设备并返回其信息（用于离线通知）
    pub fn unregister_device(&self, device_id: &str) -> Option<Device> {
        let device = self.devices.remove(device_id).map(|(_, d)| d);
        self.connections.remove(device_id);

        if let Some(ref dev) = device {
            info!(
                "📴 设备已注销: {} ({}) - 当前在线: {}",
                dev.device_name,
                dev.device_id,
                self.devices.len()
            );
        }

        device
    }

    /// 获取设备的配对设备ID列表
    pub fn get_paired_device_ids(&self, device_id: &str) -> Vec<String> {
        self.paired_devices
            .get(device_id)
            .map(|v| v.value().clone())
            .unwrap_or_default()
    }

    pub fn get_device(&self, device_id: &str) -> Option<Device> {
        self.devices.get(device_id).map(|d| d.clone())
    }

    pub fn get_online_devices(&self, device_type: Option<&str>) -> Vec<Device> {
        self.devices
            .iter()
            .filter(|entry| {
                if let Some(dtype) = device_type {
                    entry.device_type == dtype
                } else {
                    true
                }
            })
            .map(|entry| entry.value().clone())
            .collect()
    }

    /// 获取在线设备数量
    pub fn get_device_count(&self) -> usize {
        self.devices.len()
    }

    /// 获取连接数量
    pub fn get_connection_count(&self) -> usize {
        self.connections.len()
    }

    pub fn send_to_device(&self, device_id: &str, message: Message) -> bool {
        if let Some(sender) = self.connections.get(device_id) {
            sender.send(message).is_ok()
        } else {
            false
        }
    }

    pub fn add_pairing(&self, device1_id: String, device2_id: String) {
        // 双向配对
        self.paired_devices
            .entry(device1_id.clone())
            .or_insert_with(Vec::new)
            .push(device2_id.clone());

        self.paired_devices
            .entry(device2_id)
            .or_insert_with(Vec::new)
            .push(device1_id);
    }

    pub fn remove_pairing(&self, device1_id: &str, device2_id: &str) {
        if let Some(mut paired) = self.paired_devices.get_mut(device1_id) {
            paired.retain(|id| id != device2_id);
        }
        if let Some(mut paired) = self.paired_devices.get_mut(device2_id) {
            paired.retain(|id| id != device1_id);
        }
    }

    pub fn is_paired(&self, device1_id: &str, device2_id: &str) -> bool {
        if let Some(paired) = self.paired_devices.get(device1_id) {
            paired.contains(&device2_id.to_string())
        } else {
            false
        }
    }

    pub fn update_last_seen(&self, device_id: &str) {
        if let Some(mut device) = self.devices.get_mut(device_id) {
            device.last_seen = chrono::Utc::now().timestamp();
        }
    }

    pub fn set_encryption_key(&self, device_id: &str, encryption_key: String) {
        if let Some(mut device) = self.devices.get_mut(device_id) {
            device.encryption_key = Some(encryption_key);
        }
    }

    pub fn get_encryption_key(&self, device_id: &str) -> Option<String> {
        self.devices
            .get(device_id)
            .and_then(|d| d.encryption_key.clone())
    }

    /// 清理过期设备 - 移除超过超时时间未活动的设备
    pub fn cleanup_stale_devices(&self) -> usize {
        let now = chrono::Utc::now().timestamp();
        let timeout = self.config.device_timeout_secs;
        let mut removed_count = 0;

        // 收集需要移除的设备ID
        let stale_devices: Vec<String> = self
            .devices
            .iter()
            .filter(|entry| now - entry.last_seen > timeout)
            .map(|entry| entry.device_id.clone())
            .collect();

        // 移除过期设备
        for device_id in stale_devices {
            if let Some((_, device)) = self.devices.remove(&device_id) {
                self.connections.remove(&device_id);

                // 清理配对关系
                let paired_ids = self.get_paired_device_ids(&device_id);
                for paired_id in paired_ids {
                    self.remove_pairing(&device_id, &paired_id);
                }

                warn!(
                    "🗑️ 清理过期设备: {} ({}) - 离线时长: {}秒",
                    device.device_name,
                    device.device_id,
                    now - device.last_seen
                );

                removed_count += 1;
            }
        }

        if removed_count > 0 {
            info!(
                "🧹 清理完成: 移除 {} 个过期设备, 当前在线: {}",
                removed_count,
                self.devices.len()
            );
        }

        removed_count
    }

    /// 清理孤立的配对关系 - 移除不存在的设备的配对记录
    pub fn cleanup_orphaned_pairings(&self) -> usize {
        let mut removed_count = 0;

        // 收集需要清理的配对关系
        let orphaned_pairs: Vec<(String, String)> = self
            .paired_devices
            .iter()
            .flat_map(|entry| {
                let device_id = entry.key().clone();
                let paired_ids: Vec<String> = entry
                    .value()
                    .iter()
                    .filter(|paired_id| !self.devices.contains_key(*paired_id))
                    .cloned()
                    .collect();
                paired_ids
                    .into_iter()
                    .map(move |paired_id| (device_id.clone(), paired_id))
            })
            .collect();

        // 移除孤立的配对关系
        for (device_id, paired_id) in orphaned_pairs {
            self.remove_pairing(&device_id, &paired_id);
            debug!("🗑️ 清理孤立配对: {} <-> {}", device_id, paired_id);
            removed_count += 1;
        }

        if removed_count > 0 {
            info!("🧹 清理孤立配对: {} 个", removed_count);
        }

        removed_count
    }

    /// 获取统计信息
    pub fn get_stats(&self) -> DeviceStats {
        let devices = self.devices.len();
        let connections = self.connections.len();
        let pairings = self.paired_devices.len();

        DeviceStats {
            total_devices: devices,
            active_connections: connections,
            pairing_records: pairings,
            max_devices: self.config.max_devices,
            device_timeout_secs: self.config.device_timeout_secs,
        }
    }
}

/// 设备管理器统计信息
#[derive(Debug, Clone)]
pub struct DeviceStats {
    pub total_devices: usize,
    pub active_connections: usize,
    pub pairing_records: usize,
    pub max_devices: usize,
    pub device_timeout_secs: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_device_registration() {
        let manager = DeviceManager::new();
        let (tx, _rx) = mpsc::unbounded_channel();

        let result = manager.register_device(
            "device1".to_string(),
            "Test Device".to_string(),
            "android".to_string(),
            "session1".to_string(),
            tx,
        );

        assert!(result.is_ok());
        assert_eq!(manager.get_device_count(), 1);
    }

    #[test]
    fn test_device_unregistration() {
        let manager = DeviceManager::new();
        let (tx, _rx) = mpsc::unbounded_channel();

        manager
            .register_device(
                "device1".to_string(),
                "Test Device".to_string(),
                "android".to_string(),
                "session1".to_string(),
                tx,
            )
            .unwrap();

        let device = manager.unregister_device("device1");
        assert!(device.is_some());
        assert_eq!(manager.get_device_count(), 0);
    }

    #[test]
    fn test_max_devices_limit() {
        let config = ManagerConfig {
            max_devices: 2,
            ..Default::default()
        };
        let manager = DeviceManager::with_config(config);

        for i in 0..3 {
            let (tx, _rx) = mpsc::unbounded_channel();
            let result = manager.register_device(
                format!("device{}", i),
                format!("Device {}", i),
                "android".to_string(),
                format!("session{}", i),
                tx,
            );

            if i < 2 {
                assert!(result.is_ok());
            } else {
                assert!(result.is_err());
            }
        }
    }

    #[test]
    fn test_pairing() {
        let manager = DeviceManager::new();

        manager.add_pairing("device1".to_string(), "device2".to_string());

        assert!(manager.is_paired("device1", "device2"));
        assert!(manager.is_paired("device2", "device1"));

        manager.remove_pairing("device1", "device2");

        assert!(!manager.is_paired("device1", "device2"));
    }
}
