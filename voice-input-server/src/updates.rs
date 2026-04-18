use anyhow::{anyhow, Result};
use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Json, Response},
    routing::get,
    Router,
};
use serde::{Deserialize, Serialize};
use std::{
    cmp::Ordering,
    env,
    fs,
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
        let manifest: UpdateManifest = serde_json::from_str(&content)
            .map_err(|e| anyhow!("failed to parse manifest {}: {}", manifest_path.display(), e))?;
        Ok(manifest)
    }

    pub fn resolve_download_path(&self, channel: &str, version: &str, file_name: &str) -> Result<PathBuf> {
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
        let release = manifest
            .releases
            .iter()
            .find(|item| item.version == manifest.latest_version)
            .ok_or_else(|| anyhow!("latest release {} not found in manifest", manifest.latest_version))?;

        let arch = query.arch.as_deref().unwrap_or("universal");
        let asset = release.assets.iter().find(|asset| {
            asset.platform.eq_ignore_ascii_case(&query.platform)
                && (asset.arch.eq_ignore_ascii_case(arch)
                    || asset.arch.eq_ignore_ascii_case("universal"))
        });

        let has_update = compare_versions(&manifest.latest_version, &query.version)? == Ordering::Greater;
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
            latest_version: manifest.latest_version,
            minimum_supported_version: minimum_supported,
            force_update: release.force_update || force_by_minimum,
            release_notes: release.release_notes.clone(),
            asset: asset_response,
        })
    }
}

pub async fn start_http_server(service: Arc<UpdateService>) -> Result<()> {
    let state = UpdateHttpState { service };
    let app = Router::new()
        .route("/api/updates/check", get(handle_check_update))
        .route("/updates/download/:channel/:version/:file_name", get(handle_download))
        .with_state(state);

    let addr: SocketAddr = "0.0.0.0:7071".parse()?;
    let listener = tokio::net::TcpListener::bind(addr).await?;
    info!("update http server listening on {}", addr);
    axum::serve(listener, app).await?;
    Ok(())
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
