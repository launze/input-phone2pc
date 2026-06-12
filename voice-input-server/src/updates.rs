use anyhow::{anyhow, Result};
use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Json},
    routing::get,
    Router,
};
use serde::{Deserialize, Serialize};
use std::{
    cmp::Ordering,
    env, fs,
    net::SocketAddr,
    path::{Path as FsPath, PathBuf},
    sync::Arc,
};
use tokio::fs as tokio_fs;
use tracing::{info, warn};

#[derive(Debug, Clone)]
pub struct UpdateService {
    root_dir: PathBuf,
}

#[derive(Debug, Clone)]
struct UpdateHttpState {
    service: Arc<UpdateService>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateManifest {
    pub channel: String,
    pub latest_version: String,
    #[serde(default)]
    pub minimum_supported_version: Option<String>,
    #[serde(default)]
    pub releases: Vec<ReleaseEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReleaseEntry {
    pub version: String,
    #[serde(default)]
    pub release_notes: String,
    #[serde(default)]
    pub published_at: Option<String>,
    #[serde(default)]
    pub force_update: bool,
    #[serde(default)]
    pub assets: Vec<AssetEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AssetEntry {
    pub platform: String,
    pub arch: String,
    pub file_name: String,
    pub sha256: String,
    pub size: u64,
    #[serde(default)]
    pub mime_type: String,
}

#[derive(Debug, Deserialize)]
pub struct UpdateCheckQuery {
    pub device_type: Option<String>,
    pub platform: String,
    pub arch: Option<String>,
    pub version: String,
    pub channel: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct UpdateCheckResponse {
    pub has_update: bool,
    pub latest_version: String,
    pub minimum_supported_version: Option<String>,
    pub force_update: bool,
    pub release_notes: String,
    pub asset: Option<UpdateAssetResponse>,
}

#[derive(Debug, Serialize)]
pub struct UpdateAssetResponse {
    pub file_name: String,
    pub download_url: String,
    pub sha256: String,
    pub size: u64,
    pub mime_type: String,
}

impl UpdateService {
    pub fn new() -> Result<Self> {
        Ok(Self {
            root_dir: resolve_updates_root()?,
        })
    }

    pub fn root_dir(&self) -> &FsPath {
        &self.root_dir
    }

    pub fn read_manifest(&self, channel: &str) -> Result<UpdateManifest> {
        let manifest_path = self.root_dir.join(channel).join("manifest.json");
        let content = fs::read_to_string(&manifest_path)
            .map_err(|e| anyhow!("failed to read manifest {}: {}", manifest_path.display(), e))?;
        let manifest: UpdateManifest = serde_json::from_str(&content).map_err(|e| {
            anyhow!(
                "failed to parse manifest {}: {}",
                manifest_path.display(),
                e
            )
        })?;
        Ok(manifest)
    }

    pub fn resolve_download_path(
        &self,
        channel: &str,
        version: &str,
        file_name: &str,
    ) -> Result<PathBuf> {
        let safe_name = PathBuf::from(file_name)
            .file_name()
            .ok_or_else(|| anyhow!("invalid file name"))?
            .to_string_lossy()
            .to_string();
        Ok(self.root_dir.join(channel).join(version).join(safe_name))
    }

    pub fn check_update(&self, query: &UpdateCheckQuery) -> Result<UpdateCheckResponse> {
        let channel = query.channel.as_deref().unwrap_or("stable");
        let manifest = self.read_manifest(channel)?;
        let arch = query.arch.as_deref().unwrap_or("universal");

        let release = manifest
            .releases
            .iter()
            .find(|item| find_matching_asset(item, query, arch).is_some())
            .ok_or_else(|| {
                anyhow!(
                    "no compatible asset found for platform {} arch {}",
                    query.platform,
                    arch
                )
            })?;

        let asset = find_matching_asset(release, query, arch);

        let has_update =
            compare_versions(&release.version, &query.version)? == Ordering::Greater;
        let minimum_supported = manifest.minimum_supported_version.clone();
        let force_by_minimum = minimum_supported
            .as_deref()
            .map(|min| compare_versions(min, &query.version).map(|ord| ord == Ordering::Greater))
            .transpose()?
            .unwrap_or(false);

        let asset_response = asset.map(|entry| UpdateAssetResponse {
            file_name: entry.file_name.clone(),
            download_url: format!(
                "/updates/download/{}/{}/{}",
                channel, release.version, entry.file_name
            ),
            sha256: entry.sha256.clone(),
            size: entry.size,
            mime_type: entry.mime_type.clone(),
        });

        Ok(UpdateCheckResponse {
            has_update,
            latest_version: release.version.clone(),
            minimum_supported_version: minimum_supported,
            force_update: release.force_update || force_by_minimum,
            release_notes: release.release_notes.clone(),
            asset: asset_response,
        })
    }
}

fn find_matching_asset<'a>(
    release: &'a ReleaseEntry,
    query: &UpdateCheckQuery,
    arch: &str,
) -> Option<&'a AssetEntry> {
    release
        .assets
        .iter()
        .find(|asset| asset_matches_query(asset, query, arch))
}

fn asset_matches_query(asset: &AssetEntry, query: &UpdateCheckQuery, arch: &str) -> bool {
    asset.platform.eq_ignore_ascii_case(&query.platform)
        && (asset.arch.eq_ignore_ascii_case(arch) || asset.arch.eq_ignore_ascii_case("universal"))
}

pub async fn start_http_server(service: Arc<UpdateService>) -> Result<()> {
    let state = UpdateHttpState { service };
    let app = Router::new()
        .route("/", get(handle_download_page))
        .route("/downloads", get(handle_download_page))
        .route("/api/updates/check", get(handle_check_update))
        .route(
            "/updates/download/:channel/:version/:file_name",
            get(handle_download),
        )
        .with_state(state);

    let addr: SocketAddr = "0.0.0.0:16909".parse()?;
    let listener = tokio::net::TcpListener::bind(addr).await?;
    info!("update http server listening on {}", addr);
    axum::serve(listener, app).await?;
    Ok(())
}

async fn handle_download_page(State(state): State<UpdateHttpState>) -> impl IntoResponse {
    match render_download_page(&state.service, "stable") {
        Ok(html) => {
            let mut headers = HeaderMap::new();
            headers.insert(
                header::CONTENT_TYPE,
                "text/html; charset=utf-8".parse().unwrap(),
            );
            (StatusCode::OK, headers, html).into_response()
        }
        Err(error) => {
            warn!("download page render failed: {}", error);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("failed to render download page: {}", error),
            )
                .into_response()
        }
    }
}

async fn handle_check_update(
    State(state): State<UpdateHttpState>,
    Query(query): Query<UpdateCheckQuery>,
) -> impl IntoResponse {
    match state.service.check_update(&query) {
        Ok(response) => (StatusCode::OK, Json(response)).into_response(),
        Err(error) => {
            warn!("update check failed: {}", error);
            (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({
                    "error": error.to_string()
                })),
            )
                .into_response()
        }
    }
}

async fn handle_download(
    State(state): State<UpdateHttpState>,
    Path((channel, version, file_name)): Path<(String, String, String)>,
) -> impl IntoResponse {
    let path = match state
        .service
        .resolve_download_path(&channel, &version, &file_name)
    {
        Ok(path) => path,
        Err(error) => {
            return (
                StatusCode::BAD_REQUEST,
                format!("invalid download request: {}", error),
            )
                .into_response()
        }
    };

    match tokio_fs::read(&path).await {
        Ok(bytes) => {
            let mut headers = HeaderMap::new();
            headers.insert(
                header::CONTENT_TYPE,
                guess_content_type(&file_name).parse().unwrap(),
            );
            headers.insert(
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"{}\"", file_name)
                    .parse()
                    .unwrap(),
            );
            (StatusCode::OK, headers, bytes).into_response()
        }
        Err(_) => (StatusCode::NOT_FOUND, "file not found").into_response(),
    }
}

fn render_download_page(service: &UpdateService, channel: &str) -> Result<String> {
    let manifest = service.read_manifest(channel)?;

    let mut android = Vec::new();
    let mut desktop = Vec::new();
    let mut tools = Vec::new();
    let mut android_version = None;
    let mut desktop_version = None;

    for release in &manifest.releases {
        if android_version.is_none() && release.assets.iter().any(is_android_asset) {
            android_version = Some(release.version.clone());
            android.extend(
                release
                    .assets
                    .iter()
                    .filter(|asset| is_android_asset(asset))
                    .map(|asset| render_asset_item(channel, release, asset)),
            );
        }

        tools.extend(
            release
                .assets
                .iter()
                .filter(|asset| is_tool_asset(asset))
                .map(|asset| render_asset_item(channel, release, asset)),
        );

        if desktop_version.is_none() && release.assets.iter().any(is_desktop_asset)
        {
            desktop_version = Some(release.version.clone());
            desktop.extend(
                release
                    .assets
                    .iter()
                    .filter(|asset| is_desktop_asset(asset))
                    .map(|asset| render_asset_item(channel, release, asset)),
            );
        }

        if android_version.is_some() && desktop_version.is_some() {
            break;
        }
    }

    let android_list = if android.is_empty() {
        "<li><span>暂无 Android 安装包</span></li>".to_string()
    } else {
        android.join("\n")
    };
    let desktop_list = if desktop.is_empty() {
        "<li><span>暂无 PC 客户端安装包</span></li>".to_string()
    } else {
        desktop.join("\n")
    };
    let tool_list = if tools.is_empty() {
        "<li><span>暂无文件二维码生成工具</span></li>".to_string()
    } else {
        tools.join("\n")
    };
    let notes = markdownish_to_html(
        manifest
            .releases
            .iter()
            .find(|item| item.version == manifest.latest_version)
            .or_else(|| manifest.releases.first())
            .map(|release| release.release_notes.as_str())
            .unwrap_or_default(),
    );

    Ok(format!(
        r#"<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>语传-手机转电脑语音输入助手</title>
  <style>
    :root {{ color-scheme: light; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }}
    body {{ margin: 0; background: #f6f8fb; color: #172033; }}
    main {{ width: min(920px, calc(100% - 32px)); margin: 0 auto; padding: 40px 0 56px; }}
    header {{ margin-bottom: 28px; }}
    h1 {{ margin: 0 0 10px; font-size: 32px; line-height: 1.2; }}
    h2 {{ margin: 28px 0 12px; font-size: 20px; }}
    p {{ margin: 0; color: #526070; }}
    .intro {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 18px; }}
    .intro-card {{ background: #fff; border: 1px solid #d9e0ea; border-radius: 8px; padding: 16px; }}
    .intro-card h2 {{ margin: 0 0 8px; font-size: 18px; }}
    .intro-card p {{ line-height: 1.65; }}
    section {{ background: #fff; border: 1px solid #d9e0ea; border-radius: 8px; padding: 20px; margin-top: 16px; }}
    ul {{ list-style: none; padding: 0; margin: 0; display: grid; gap: 10px; }}
    li {{ display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid #edf1f6; }}
    li:last-child {{ border-bottom: 0; }}
    a {{ color: #0f6bcb; font-weight: 650; text-decoration: none; overflow-wrap: anywhere; }}
    a:hover {{ text-decoration: underline; }}
    span {{ color: #667386; font-size: 14px; white-space: nowrap; }}
    .notes {{ color: #344054; line-height: 1.65; }}
    .notes p {{ margin: 8px 0; color: #344054; }}
    @media (max-width: 640px) {{
      main {{ width: min(100% - 24px, 920px); padding-top: 28px; }}
      h1 {{ font-size: 26px; }}
      .intro {{ grid-template-columns: 1fr; }}
      section {{ padding: 16px; }}
      li {{ align-items: flex-start; flex-direction: column; gap: 4px; }}
      span {{ white-space: normal; }}
    }}
  </style>
</head>
<body>
  <main>
    <header>
      <h1>语传-手机转电脑语音输入助手</h1>
      <p>手机端语音、文字、图片和文件快速发送到电脑端；电脑端接收后可复制、编辑、插入到光标位置，并管理历史记录。</p>
      <div class="intro">
        <div class="intro-card">
          <h2>手机端</h2>
          <p>支持语音输入、文字发送、图片发送、扫码接收文件、历史记录查看，以及手动检查和安装新版本。</p>
        </div>
        <div class="intro-card">
          <h2>电脑端</h2>
          <p>支持扫码配对、自动接收手机内容、文字工作区、历史记录导出、AI 报告生成，以及手动检查和下载新版本。</p>
        </div>
      </div>
      <p style="margin-top: 14px;">当前版本 {version}，频道 {channel}</p>
    </header>
    <section>
      <h2>Android App</h2>
      <ul>{android_list}</ul>
    </section>
    <section>
      <h2>PC 客户端</h2>
      <ul>{desktop_list}</ul>
    </section>
    <section>
      <h2>文件二维码生成工具</h2>
      <p style="margin-bottom: 12px;">用于在电脑端选择文件并生成文件二维码，手机端可通过扫码传输文件功能接收。</p>
      <ul>{tool_list}</ul>
    </section>
    <section>
      <h2>更新说明</h2>
      <div class="notes">{notes}</div>
    </section>
  </main>
</body>
</html>"#,
        version = html_escape(&manifest.latest_version),
        channel = html_escape(channel),
        android_list = android_list,
        desktop_list = desktop_list,
        tool_list = tool_list,
        notes = notes,
    ))
}

fn is_android_asset(asset: &AssetEntry) -> bool {
    asset.file_name.ends_with(".apk") || asset.platform.eq_ignore_ascii_case("android")
}

fn is_desktop_asset(asset: &AssetEntry) -> bool {
    matches!(
        asset.platform.to_ascii_lowercase().as_str(),
        "windows" | "macos" | "linux"
    )
}

fn is_tool_asset(asset: &AssetEntry) -> bool {
    asset.platform.eq_ignore_ascii_case("tool") || asset.file_name.contains("file-qr-generator")
}

fn render_asset_item(channel: &str, release: &ReleaseEntry, asset: &AssetEntry) -> String {
    let url = format!(
        "/updates/download/{}/{}/{}",
        channel,
        release.version,
        url_path_escape(&asset.file_name)
    );
    format!(
        r#"<li><a href="{url}">{name}</a><span>{platform} {arch} - {size}</span></li>"#,
        url = url,
        name = html_escape(&asset.file_name),
        platform = html_escape(&asset.platform),
        arch = html_escape(&asset.arch),
        size = format_size(asset.size),
    )
}

fn format_size(size: u64) -> String {
    const KB: f64 = 1024.0;
    const MB: f64 = KB * 1024.0;
    const GB: f64 = MB * 1024.0;
    let size = size as f64;
    if size >= GB {
        format!("{:.2} GB", size / GB)
    } else if size >= MB {
        format!("{:.2} MB", size / MB)
    } else if size >= KB {
        format!("{:.1} KB", size / KB)
    } else {
        format!("{} B", size as u64)
    }
}

fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn url_path_escape(value: &str) -> String {
    value
        .replace('%', "%25")
        .replace('#', "%23")
        .replace('?', "%3F")
}

fn markdownish_to_html(value: &str) -> String {
    let paragraphs: Vec<String> = value
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(|line| {
            let line = line.trim_start_matches('#').trim_start_matches("- ").trim();
            format!("<p>{}</p>", html_escape(line))
        })
        .collect();
    if paragraphs.is_empty() {
        "<p>暂无更新说明。</p>".to_string()
    } else {
        paragraphs.join("\n")
    }
}

fn resolve_updates_root() -> Result<PathBuf> {
    let exe = env::current_exe()?;
    let exe_dir = exe
        .parent()
        .ok_or_else(|| anyhow!("failed to resolve executable directory"))?;
    let updates_root = exe_dir.join("updates");
    fs::create_dir_all(&updates_root)?;
    Ok(updates_root)
}

fn compare_versions(left: &str, right: &str) -> Result<Ordering> {
    let parse = |value: &str| -> Result<Vec<u64>> {
        value
            .split('.')
            .map(|part| {
                part.parse::<u64>()
                    .map_err(|e| anyhow!("invalid version component {}: {}", part, e))
            })
            .collect()
    };

    let mut left_parts = parse(left)?;
    let mut right_parts = parse(right)?;
    let len = left_parts.len().max(right_parts.len());
    left_parts.resize(len, 0);
    right_parts.resize(len, 0);
    Ok(left_parts.cmp(&right_parts))
}

fn guess_content_type(file_name: &str) -> &'static str {
    if file_name.ends_with(".apk") {
        "application/vnd.android.package-archive"
    } else if file_name.ends_with(".msi") {
        "application/x-msi"
    } else if file_name.ends_with(".exe") {
        "application/vnd.microsoft.portable-executable"
    } else if file_name.ends_with(".dmg") {
        "application/x-apple-diskimage"
    } else if file_name.ends_with(".deb") {
        "application/vnd.debian.binary-package"
    } else if file_name.ends_with(".AppImage") {
        "application/octet-stream"
    } else {
        "application/octet-stream"
    }
}

pub fn build_default_manifest(channel: &str) -> UpdateManifest {
    UpdateManifest {
        channel: channel.to_string(),
        latest_version: "0.0.0".to_string(),
        minimum_supported_version: None,
        releases: Vec::new(),
    }
}
