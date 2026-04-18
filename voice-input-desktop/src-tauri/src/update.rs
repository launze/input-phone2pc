use crate::storage::config::AppConfig;
use anyhow::{anyhow, Result};
use reqwest::Url;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::process::Command;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateAsset {
    pub file_name: String,
    pub download_url: String,
    pub sha256: String,
    pub size: u64,
    pub mime_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateInfo {
    pub has_update: bool,
    pub latest_version: String,
    pub minimum_supported_version: Option<String>,
    pub force_update: bool,
    pub release_notes: String,
    pub asset: Option<UpdateAsset>,
}

fn current_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

fn normalize_server_base(server_url: &str) -> Result<String> {
    let normalized = if let Some(rest) = server_url.strip_prefix("wss://") {
        format!("https://{}", rest.trim_end_matches('/'))
    } else if let Some(rest) = server_url.strip_prefix("ws://") {
        format!("http://{}", rest.trim_end_matches('/'))
    } else if server_url.starts_with("https://") || server_url.starts_with("http://") {
        server_url.trim_end_matches('/').to_string()
    } else {
        return Err(anyhow!("unsupported server url: {}", server_url));
    };

    let mut url = Url::parse(&normalized)?;
    let next_port = match url.port_or_known_default() {
        Some(7070) => 7071,
        Some(port) => port,
        _ => 7071,
    };
    url.set_port(Some(next_port))
        .map_err(|_| anyhow!("failed to set update port"))?;
    url.set_path("");
    url.set_query(None);
    url.set_fragment(None);
    Ok(url.to_string().trim_end_matches('/').to_string())
}

pub async fn check_update() -> Result<UpdateInfo> {
    let config = AppConfig::load();
    let base = normalize_server_base(&config.server_url)?;
    let platform = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    };
    let arch = if cfg!(target_arch = "x86_64") {
        "x64"
    } else if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "unknown"
    };
    let url = format!(
        "{}/api/updates/check?device_type=desktop&platform={}&arch={}&version={}&channel=stable",
        base,
        platform,
        arch,
        current_version()
    );

    let info = reqwest::get(url)
        .await?
        .error_for_status()?
        .json::<UpdateInfo>()
        .await?;
    Ok(info)
}

pub async fn download_and_open_update(info: UpdateInfo) -> Result<String> {
    let asset = info.asset.ok_or_else(|| anyhow!("missing update asset"))?;
    let config = AppConfig::load();
    let base = normalize_server_base(&config.server_url)?;
    let download_url = if asset.download_url.starts_with("http://")
        || asset.download_url.starts_with("https://")
    {
        asset.download_url.clone()
    } else {
        format!("{}{}", base, asset.download_url)
    };

    let bytes = reqwest::get(download_url)
        .await?
        .error_for_status()?
        .bytes()
        .await?;
    let target_dir = dirs::download_dir()
        .or_else(dirs::document_dir)
        .or_else(dirs::desktop_dir)
        .or_else(|| std::env::current_dir().ok())
        .ok_or_else(|| anyhow!("cannot resolve download dir"))?
        .join("voiceinput-updates");
    std::fs::create_dir_all(&target_dir)?;
    let target_path = target_dir.join(&asset.file_name);
    std::fs::write(&target_path, &bytes)?;

    open_installer(&target_path)?;
    Ok(target_path.display().to_string())
}

fn open_installer(path: &PathBuf) -> Result<()> {
    #[cfg(target_os = "windows")]
    {
        Command::new("cmd")
            .args(["/C", "start", "", path.to_string_lossy().as_ref()])
            .spawn()?;
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open").arg(path).spawn()?;
    }
    #[cfg(target_os = "linux")]
    {
        Command::new("xdg-open").arg(path).spawn()?;
    }
    Ok(())
}
