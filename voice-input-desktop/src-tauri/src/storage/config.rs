use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

const DEFAULT_SERVER_URL: &str = "wss://8.153.163.104:16908";
const LEGACY_SERVER_URLS: &[&str] = &[
    "wss://nas.smarthome2020.top:7070",
    "wss://ha.wwszxc.tax:16908",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AppConfig {
    pub server_mode_enabled: bool,
    pub server_url: String,
    pub device_id: String,
    pub device_name: String,
    pub input_mode: String,
    pub paired_devices: Vec<PairedDevice>,
    pub openai: OpenAiConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub paired_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct OpenAiConfig {
    pub api_key: String,
    pub api_url: String,
    pub model_name: String,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            server_mode_enabled: false,
            server_url: DEFAULT_SERVER_URL.to_string(),
            device_id: uuid::Uuid::new_v4().to_string(),
            device_name: hostname::get()
                .ok()
                .and_then(|h| h.into_string().ok())
                .unwrap_or_else(|| "本机".to_string()),
            input_mode: "direct".to_string(),
            paired_devices: Vec::new(),
            openai: OpenAiConfig::default(),
        }
    }
}

impl Default for OpenAiConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            api_url: "https://api.openai.com/v1/responses".to_string(),
            model_name: "gpt-5-mini".to_string(),
        }
    }
}

impl AppConfig {
    pub fn load() -> Self {
        let config_path = Self::get_config_path();

        if config_path.exists() {
            if let Ok(content) = fs::read_to_string(&config_path) {
                if let Ok(mut config) = serde_json::from_str::<Self>(&content) {
                    if migrate_server_url(&mut config) {
                        let _ = config.save();
                    }
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
        let config_dir = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        config_dir.join("voice-input").join("config.json")
    }

    pub fn set_server_url(&mut self, url: String) {
        self.server_url = url;
    }

    pub fn set_server_mode(&mut self, enabled: bool) {
        self.server_mode_enabled = enabled;
    }

    pub fn set_input_mode(&mut self, mode: String) {
        self.input_mode = match mode.as_str() {
            "clipboard" | "confirm" => mode,
            _ => "direct".to_string(),
        };
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

    pub fn remove_paired_device(&mut self, device_id: &str) {
        self.paired_devices.retain(|d| d.device_id != device_id);
    }

    pub fn set_openai_config(&mut self, openai: OpenAiConfig) {
        self.openai = openai;
    }
}

fn migrate_server_url(config: &mut AppConfig) -> bool {
    if LEGACY_SERVER_URLS.contains(&config.server_url.as_str()) {
        config.server_url = DEFAULT_SERVER_URL.to_string();
        return true;
    }
    false
}
