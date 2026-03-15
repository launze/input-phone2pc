use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub server_mode_enabled: bool,
    pub server_url: String,
    pub device_id: String,
    pub device_name: String,
    pub paired_devices: Vec<PairedDevice>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub paired_at: i64,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            server_mode_enabled: false,
            server_url: "wss://nas.smarthome2020.top:7070".to_string(),
            device_id: uuid::Uuid::new_v4().to_string(),
            device_name: hostname::get()
                .ok()
                .and_then(|h| h.into_string().ok())
                .unwrap_or_else(|| "Unknown".to_string()),
            paired_devices: Vec::new(),
        }
    }
}

impl AppConfig {
    pub fn load() -> Self {
        let config_path = Self::get_config_path();
        
        if config_path.exists() {
            if let Ok(content) = fs::read_to_string(&config_path) {
                if let Ok(config) = serde_json::from_str(&content) {
                    return config;
                }
            }
        }
        
        let config = Self::default();
        let _ = config.save();
        config
    }

    pub fn save(&self) -> Result<(), Box<dyn std::error::Error>> {
        let config_path = Self::get_config_path();
        
        if let Some(parent) = config_path.parent() {
            fs::create_dir_all(parent)?;
        }
        
        let content = serde_json::to_string_pretty(self)?;
        fs::write(config_path, content)?;
        
        Ok(())
    }

    fn get_config_path() -> PathBuf {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."));
        config_dir.join("voice-input").join("config.json")
    }

    pub fn set_server_url(&mut self, url: String) {
        self.server_url = url;
    }

    pub fn set_server_mode(&mut self, enabled: bool) {
        self.server_mode_enabled = enabled;
    }

    pub fn add_paired_device(&mut self, device_id: String, device_name: String) {
        if !self.paired_devices.iter().any(|d| d.device_id == device_id) {
            self.paired_devices.push(PairedDevice {
                device_id,
                device_name,
                paired_at: chrono::Utc::now().timestamp(),
            });
        }
    }

    pub fn is_device_paired(&self, device_id: &str) -> bool {
        self.paired_devices.iter().any(|d| d.device_id == device_id)
    }

    pub fn remove_paired_device(&mut self, device_id: &str) {
        self.paired_devices.retain(|d| d.device_id != device_id);
    }
}
