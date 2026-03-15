use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

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

pub struct DeviceManager {
    // device_id -> Device
    devices: Arc<DashMap<String, Device>>,
    // device_id -> WebSocket sender
    connections: Arc<DashMap<String, mpsc::UnboundedSender<Message>>>,
    // (device1_id, device2_id) -> paired
    paired_devices: Arc<DashMap<String, Vec<String>>>,
}

impl DeviceManager {
    pub fn new() -> Self {
        Self {
            devices: Arc::new(DashMap::new()),
            connections: Arc::new(DashMap::new()),
            paired_devices: Arc::new(DashMap::new()),
        }
    }

    pub fn register_device(
        &self,
        device_id: String,
        device_name: String,
        device_type: String,
        session_id: String,
        sender: mpsc::UnboundedSender<Message>,
    ) {
        let device = Device {
            device_id: device_id.clone(),
            device_name,
            device_type,
            session_id,
            last_seen: chrono::Utc::now().timestamp(),
            paired_devices: Vec::new(),
            encryption_key: None,
        };

        self.devices.insert(device_id.clone(), device);
        self.connections.insert(device_id, sender);
    }

    /// Unregister a device and return its info (for offline notification)
    pub fn unregister_device(&self, device_id: &str) -> Option<Device> {
        let device = self.devices.remove(device_id).map(|(_, d)| d);
        self.connections.remove(device_id);
        device
    }

    /// Get the list of paired device IDs for a given device (from memory)
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
        self.devices.get(device_id).and_then(|d| d.encryption_key.clone())
    }
}
