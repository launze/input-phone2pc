use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

const DEFAULT_SERVER_URL: &str = "wss://ha.wwszxc.tax:16908";
const LEGACY_SERVER_URL: &str = "wss://nas.smarthome2020.top:7070";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AppConfig {
    pub server_mode_enabled: bool,
    pub server_url: String,
    pub device_id: String,
    pub device_name: String,
    pub paired_devices: Vec<PairedDevice>,
    pub openai: OpenAiReportConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub paired_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct OpenAiReportConfig {
    pub api_key: String,
    pub api_url: String,
    pub model_name: String,
    #[serde(default = "default_weekly_prompt_template")]
    pub weekly_prompt_template: String,
    #[serde(default = "default_monthly_prompt_template")]
    pub monthly_prompt_template: String,
    #[serde(default = "default_quarterly_prompt_template")]
    pub quarterly_prompt_template: String,
    #[serde(default = "default_half_year_prompt_template")]
    pub half_year_prompt_template: String,
    #[serde(default = "default_yearly_prompt_template")]
    pub yearly_prompt_template: String,
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
            paired_devices: Vec::new(),
            openai: OpenAiReportConfig::default(),
        }
    }
}

impl Default for OpenAiReportConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            api_url: "https://api.openai.com/v1/responses".to_string(),
            model_name: "gpt-5-mini".to_string(),
            weekly_prompt_template: default_weekly_prompt_template(),
            monthly_prompt_template: default_monthly_prompt_template(),
            quarterly_prompt_template: default_quarterly_prompt_template(),
            half_year_prompt_template: default_half_year_prompt_template(),
            yearly_prompt_template: default_yearly_prompt_template(),
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

    pub fn set_openai_config(&mut self, openai: OpenAiReportConfig) {
        self.openai = openai;
    }
}

fn migrate_server_url(config: &mut AppConfig) -> bool {
    if config.server_url == LEGACY_SERVER_URL {
        config.server_url = DEFAULT_SERVER_URL.to_string();
        return true;
    }
    false
}

fn default_weekly_prompt_template() -> String {
    [
        "请基于以下语音输入历史，为我生成一份结构清晰的中文周报。",
        "时间范围：{{start_date}} 至 {{end_date}}",
        "记录数：{{record_count}}",
        "",
        "输出要求：",
        "1. 先给出一句总览。",
        "2. 按 3 到 5 个主题归纳本周主要工作。",
        "3. 明确列出已完成事项、待跟进事项、风险或阻塞。",
        "4. 如原始记录信息不足，请明确写“记录中未体现”。",
        "5. 不要虚构事实，只能依据提供的记录总结。",
        "",
        "原始记录：",
        "{{records}}",
    ]
    .join("\n")
}

fn default_monthly_prompt_template() -> String {
    [
        "请基于以下语音输入历史，为我生成一份结构清晰的中文月报。",
        "时间范围：{{start_date}} 至 {{end_date}}",
        "记录数：{{record_count}}",
        "",
        "输出要求：",
        "1. 先写本月总体概述。",
        "2. 按主题归纳核心成果与关键推进事项。",
        "3. 单独列出重点里程碑、待办和下月建议跟进方向。",
        "4. 如原始记录信息不足，请明确写“记录中未体现”。",
        "5. 不要虚构事实，只能依据提供的记录总结。",
        "",
        "原始记录：",
        "{{records}}",
    ]
    .join("\n")
}

fn default_quarterly_prompt_template() -> String {
    [
        "请基于以下语音输入历史，为我生成一份结构清晰的中文季度报表。",
        "时间范围：{{start_date}} 至 {{end_date}}",
        "记录数：{{record_count}}",
        "",
        "输出要求：",
        "1. 先写本季度总体概述。",
        "2. 按主题归纳核心成果、关键推进事项和阶段性变化。",
        "3. 单独列出重点里程碑、未完成事项、风险阻塞和下季度建议。",
        "4. 如原始记录信息不足，请明确写“记录中未体现”。",
        "5. 不要虚构事实，只能依据提供的记录总结。",
        "",
        "原始记录：",
        "{{records}}",
    ]
    .join("\n")
}

fn default_half_year_prompt_template() -> String {
    [
        "请基于以下语音输入历史，为我生成一份结构清晰的中文半年报。",
        "时间范围：{{start_date}} 至 {{end_date}}",
        "记录数：{{record_count}}",
        "",
        "输出要求：",
        "1. 先写半年度总体概述。",
        "2. 按主题归纳主要成果、重要推进事项和阶段性进展。",
        "3. 总结关键里程碑、经验沉淀、风险阻塞和下半年/下一阶段建议。",
        "4. 如原始记录信息不足，请明确写“记录中未体现”。",
        "5. 不要虚构事实，只能依据提供的记录总结。",
        "",
        "原始记录：",
        "{{records}}",
    ]
    .join("\n")
}

fn default_yearly_prompt_template() -> String {
    [
        "请基于以下语音输入历史，为我生成一份结构清晰的中文年报。",
        "时间范围：{{start_date}} 至 {{end_date}}",
        "记录数：{{record_count}}",
        "",
        "输出要求：",
        "1. 先写年度总体概述。",
        "2. 按主题归纳年度核心成果、关键项目和重要推进事项。",
        "3. 总结重点里程碑、问题风险、经验沉淀和下一年度建议方向。",
        "4. 如原始记录信息不足，请明确写“记录中未体现”。",
        "5. 不要虚构事实，只能依据提供的记录总结。",
        "",
        "原始记录：",
        "{{records}}",
    ]
    .join("\n")
}
