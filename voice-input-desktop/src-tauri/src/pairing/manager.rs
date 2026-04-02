use crate::storage::config::{AppConfig, PairedDevice};
use std::sync::Arc;
use tokio::sync::Mutex;

pub struct PairingManager {
    config: Arc<Mutex<AppConfig>>,
}

impl PairingManager {
    pub fn new() -> Self {
        let config = AppConfig::load();
        Self {
            config: Arc::new(Mutex::new(config)),
        }
    }

    pub async fn add_paired_device(&self, device_id: String, device_name: String) {
        let mut config = self.config.lock().await;
        config.add_paired_device(device_id, device_name);
        if let Err(e) = config.save() {
            eprintln!("保存配置失败: {}", e);
        }
    }

    pub async fn is_paired(&self, device_id: &str) -> bool {
        let config = self.config.lock().await;
        config.is_device_paired(device_id)
    }

    pub async fn get_device_name(&self, device_id: &str) -> Option<String> {
        let config = self.config.lock().await;
        config
            .paired_devices
            .iter()
            .find(|d| d.device_id == device_id)
            .map(|d| d.device_name.clone())
    }

    pub async fn remove_device(&self, device_id: &str) {
        let mut config = self.config.lock().await;
        config.remove_paired_device(device_id);
        if let Err(e) = config.save() {
            eprintln!("保存配置失败: {}", e);
        }
    }

    pub async fn get_all_paired_devices(&self) -> Vec<PairedDevice> {
        let config = self.config.lock().await;
        config.paired_devices.clone()
    }
}
