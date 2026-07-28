use libloading::Library;
use serde::Serialize;
use std::ffi::CString;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tauri::{AppHandle, Manager};

const PANEL_WIDTH: f64 = 130.0;
const PANEL_HEIGHT: f64 = 380.0;
const PANEL_RIGHT_MARGIN: i32 = 48;

type ReaderStart = unsafe extern "C" fn(*const i8, i32, i32, i32, i32, i32, i32, *mut i8, usize) -> i32;
type ReaderStop = unsafe extern "C" fn();
type ReaderIsRunning = unsafe extern "C" fn() -> i32;
type ReaderStatus = unsafe extern "C" fn(*mut i8, usize) -> i32;

struct Bridge {
    _library: Library,
    start: ReaderStart,
    stop: ReaderStop,
    is_running: ReaderIsRunning,
    status: ReaderStatus,
}

#[derive(Default)]
struct ReaderRuntime {
    bridge: Option<Arc<Bridge>>,
}

lazy_static::lazy_static! {
    static ref READER_RUNTIME: Mutex<ReaderRuntime> = Mutex::new(ReaderRuntime::default());
}

#[derive(Debug, Clone, Serialize)]
pub struct C2ReaderStatus {
    pub available: bool,
    pub running: bool,
    pub frames: u64,
    pub progress: u32,
    pub backlog: u32,
    pub files_decoded: u32,
    pub output_dir: String,
    pub last_saved: String,
    pub error: String,
    pub scanned: u32,
    pub decoded: u32,
    pub perfect: u32,
    pub tracked: u32,
    pub mode: i32,
    pub detected_mode: i32,
    pub capture_left: i32,
    pub capture_top: i32,
    pub capture_width: i32,
    pub capture_height: i32,
    pub diagnostics: Vec<String>,
}

fn bridge_filename() -> &'static str {
    #[cfg(target_os = "windows")]
    {
        "voiceinput_c2_reader_bridge.dll"
    }
    #[cfg(target_os = "linux")]
    {
        "libvoiceinput_c2_reader_bridge.so"
    }
    #[cfg(target_os = "macos")]
    {
        "libvoiceinput_c2_reader_bridge.dylib"
    }
}

fn candidate_bridge_paths(app_handle: &AppHandle) -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        paths.push(
            resource_dir
                .join("resources")
                .join("native")
                .join(bridge_filename()),
        );
        paths.push(resource_dir.join("native").join(bridge_filename()));
    }
    if let Ok(current_dir) = std::env::current_dir() {
        paths.push(
            current_dir
                .join("src-tauri")
                .join("resources")
                .join("native")
                .join(bridge_filename()),
        );
        paths.push(
            current_dir
                .join("resources")
                .join("native")
                .join(bridge_filename()),
        );
    }
    paths
}

unsafe fn load_bridge_from(path: PathBuf) -> Result<Bridge, String> {
    println!("[c2-reader:rust] loading bridge {}", path.display());
    let started = Instant::now();
    let library = Library::new(&path)
        .map_err(|error| format!("加载二维码读取器失败: {} ({})", path.display(), error))?;
    let start: ReaderStart = *library
        .get::<ReaderStart>(b"voiceinput_c2_reader_start")
        .map_err(|error| format!("读取器入口缺失: {}", error))?;
    let stop: ReaderStop = *library
        .get::<ReaderStop>(b"voiceinput_c2_reader_stop")
        .map_err(|error| format!("读取器停止入口缺失: {}", error))?;
    let is_running: ReaderIsRunning = *library
        .get::<ReaderIsRunning>(b"voiceinput_c2_reader_is_running")
        .map_err(|error| format!("读取器状态入口缺失: {}", error))?;
    let status: ReaderStatus = *library
        .get::<ReaderStatus>(b"voiceinput_c2_reader_status")
        .map_err(|error| format!("读取器状态详情入口缺失: {}", error))?;

    println!(
        "[c2-reader:rust] bridge loaded in {} ms",
        started.elapsed().as_millis()
    );
    Ok(Bridge {
        _library: library,
        start,
        stop,
        is_running,
        status,
    })
}

fn get_bridge(app_handle: &AppHandle) -> Result<Arc<Bridge>, String> {
    {
        let runtime = READER_RUNTIME
            .lock()
            .map_err(|_| "二维码读取器状态锁定失败".to_string())?;
        if let Some(bridge) = &runtime.bridge {
            return Ok(Arc::clone(bridge));
        }
    }

    let mut missing = Vec::new();
    for path in candidate_bridge_paths(app_handle) {
        if !path.exists() {
            missing.push(path);
            continue;
        }
        let bridge = unsafe { load_bridge_from(path) }?;
        let bridge = Arc::new(bridge);
        let mut runtime = READER_RUNTIME
            .lock()
            .map_err(|_| "二维码读取器状态锁定失败".to_string())?;
        runtime.bridge = Some(Arc::clone(&bridge));
        return Ok(bridge);
    }

    let searched = missing
        .iter()
        .map(|path| path.display().to_string())
        .collect::<Vec<_>>()
        .join("; ");
    Err(format!(
        "缺少内置二维码读取模块 {}。请先构建 native bridge。已查找: {}",
        bridge_filename(),
        searched
    ))
}

fn read_c_string(buffer: &[i8]) -> String {
    let end = buffer
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(buffer.len());
    let bytes = buffer[..end]
        .iter()
        .map(|value| *value as u8)
        .collect::<Vec<_>>();
    String::from_utf8_lossy(&bytes).to_string()
}

fn default_output_dir() -> Result<PathBuf, String> {
    let dir = dirs::download_dir()
        .or_else(dirs::document_dir)
        .or_else(dirs::desktop_dir)
        .ok_or_else(|| "无法确定文件保存目录".to_string())?
        .join("VoiceInputC2");
    std::fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
    Ok(dir)
}

fn current_capture_rect(app_handle: &AppHandle) -> (i32, i32, i32, i32) {
    if let Some(main_window) = app_handle.get_webview_window("main") {
        if let Ok(Some(monitor)) = main_window.current_monitor() {
            let area = monitor.work_area();
            return (
                area.position.x,
                area.position.y,
                area.size.width as i32,
                area.size.height as i32,
            );
        }
    }
    (0, 0, 0, 0)
}

pub fn open_panel(app_handle: AppHandle) -> Result<(), String> {
    println!("[c2-reader:rust] open_panel called");
    if let Some(window) = app_handle.get_webview_window("c2-reader") {
        println!("[c2-reader:rust] existing c2-reader window found");
        let _ = window.show();
        let _ = window.set_focus();
        return Ok(());
    }

    let started = Instant::now();
    let window = tauri::WebviewWindowBuilder::new(
        &app_handle,
        "c2-reader",
        tauri::WebviewUrl::App("c2-reader.html".into()),
    )
    .title("二维码读取")
    .inner_size(PANEL_WIDTH, PANEL_HEIGHT)
    .min_inner_size(PANEL_WIDTH, PANEL_HEIGHT)
    .max_inner_size(PANEL_WIDTH, PANEL_HEIGHT)
    .resizable(false)
    .decorations(false)
    .always_on_top(true)
    .skip_taskbar(false)
    .build()
    .map_err(|error| format!("打开二维码读取窗口失败: {}", error))?;
    println!(
        "[c2-reader:rust] c2-reader window built in {} ms",
        started.elapsed().as_millis()
    );

    if let Some(main_window) = app_handle.get_webview_window("main") {
        if let Ok(Some(monitor)) = main_window.current_monitor() {
            let area = monitor.work_area();
            let x = area.position.x + area.size.width as i32 - PANEL_WIDTH as i32 - PANEL_RIGHT_MARGIN;
            let y = area.position.y + ((area.size.height as i32 - PANEL_HEIGHT as i32) / 2).max(8);
            println!(
                "[c2-reader:rust] positioning on current monitor x={} y={} width={} height={}",
                x, y, area.size.width, area.size.height
            );
            let _ = window.set_position(tauri::Position::Physical(tauri::PhysicalPosition { x, y }));
        }
        if let Err(error) = main_window.minimize() {
            eprintln!("[c2-reader:rust] minimize main window failed: {}", error);
        } else {
            println!("[c2-reader:rust] main window minimized for screen capture");
        }
    }
    let _ = window.set_focus();

    let stop_handle = app_handle.clone();
    window.on_window_event(move |event| {
        println!("[c2-reader:rust] window event: {:?}", event);
        if matches!(event, tauri::WindowEvent::CloseRequested { .. }) {
            let _ = stop(stop_handle.clone());
            if let Some(main_window) = stop_handle.get_webview_window("main") {
                let _ = main_window.unminimize();
                let _ = main_window.show();
                let _ = main_window.set_focus();
            }
        }
    });
    Ok(())
}

pub fn close_panel(app_handle: AppHandle) -> Result<(), String> {
    println!("[c2-reader:rust] close_panel called");
    let _ = stop(app_handle.clone());
    if let Some(window) = app_handle.get_webview_window("c2-reader") {
        window
            .close()
            .map_err(|error| format!("关闭二维码读取窗口失败: {}", error))?;
    }
    if let Some(main_window) = app_handle.get_webview_window("main") {
        let _ = main_window.unminimize();
        let _ = main_window.show();
        let _ = main_window.set_focus();
    }
    Ok(())
}

pub fn start(app_handle: AppHandle) -> Result<C2ReaderStatus, String> {
    println!("[c2-reader:rust] start called");
    let started = Instant::now();
    #[cfg(not(target_os = "windows"))]
    {
        let _ = app_handle;
        return Err("内置桌面二维码读取目前仅支持 Windows。".to_string());
    }

    #[cfg(target_os = "windows")]
    {
        let bridge = get_bridge(&app_handle)?;
        println!(
            "[c2-reader:rust] bridge ready before native start in {} ms",
            started.elapsed().as_millis()
        );
        let output_dir = default_output_dir()?;
        let output_dir_text = output_dir.to_string_lossy().to_string();
        let output_dir_c = CString::new(output_dir_text)
            .map_err(|_| "保存目录包含无法传递给原生读取器的字符".to_string())?;
        let (capture_left, capture_top, capture_width, capture_height) =
            current_capture_rect(&app_handle);
        println!(
            "[c2-reader:rust] native capture rect {} {} {}x{}",
            capture_left, capture_top, capture_width, capture_height
        );
        let mut error = vec![0i8; 1024];
        let ok = unsafe {
            (bridge.start)(
                output_dir_c.as_ptr(),
                68,
                16,
                capture_left,
                capture_top,
                capture_width,
                capture_height,
                error.as_mut_ptr(),
                error.len(),
            )
        };
        println!(
            "[c2-reader:rust] native start returned {} in {} ms",
            ok,
            started.elapsed().as_millis()
        );
        if ok == 0 {
            let message = read_c_string(&error);
            return Err(if message.is_empty() {
                "启动二维码读取失败".to_string()
            } else {
                message
            });
        }
        status(app_handle)
    }
}

pub fn stop(app_handle: AppHandle) -> Result<C2ReaderStatus, String> {
    println!("[c2-reader:rust] stop called");
    let started = Instant::now();
    if let Ok(bridge) = get_bridge(&app_handle) {
        unsafe {
            (bridge.stop)();
        }
    }
    println!(
        "[c2-reader:rust] stop finished in {} ms",
        started.elapsed().as_millis()
    );
    status(app_handle)
}

pub fn status(app_handle: AppHandle) -> Result<C2ReaderStatus, String> {
    let started = Instant::now();
    let bridge = match get_bridge(&app_handle) {
        Ok(bridge) => bridge,
        Err(error) => {
            return Ok(C2ReaderStatus {
                available: false,
                running: false,
                frames: 0,
                progress: 0,
                backlog: 0,
                files_decoded: 0,
                output_dir: String::new(),
                last_saved: String::new(),
                error,
                scanned: 0,
                decoded: 0,
                perfect: 0,
                tracked: 0,
                mode: 0,
                detected_mode: 0,
                capture_left: 0,
                capture_top: 0,
                capture_width: 0,
                capture_height: 0,
                diagnostics: Vec::new(),
            });
        }
    };

    let mut buffer = vec![0i8; 4096];
    let ok = unsafe { (bridge.status)(buffer.as_mut_ptr(), buffer.len()) };
    println!(
        "[c2-reader:rust] status returned {} in {} ms",
        ok,
        started.elapsed().as_millis()
    );
    if ok == 0 {
        return Err("读取二维码读取器状态失败".to_string());
    }

    let raw = read_c_string(&buffer);
    let value: serde_json::Value = serde_json::from_str(&raw).unwrap_or_else(|_| serde_json::json!({}));
    println!("[c2-reader:rust] native status raw {}", raw);
    let running = value
        .get("running")
        .and_then(|item| item.as_bool())
        .unwrap_or_else(|| unsafe { (bridge.is_running)() != 0 });

    Ok(C2ReaderStatus {
        available: true,
        running,
        frames: value.get("frames").and_then(|item| item.as_u64()).unwrap_or(0),
        progress: value
            .get("progress")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        backlog: value
            .get("backlog")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        files_decoded: value
            .get("files_decoded")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        output_dir: value
            .get("output_dir")
            .and_then(|item| item.as_str())
            .unwrap_or_default()
            .to_string(),
        last_saved: value
            .get("last_saved")
            .and_then(|item| item.as_str())
            .unwrap_or_default()
            .to_string(),
        error: value
            .get("error")
            .and_then(|item| item.as_str())
            .unwrap_or_default()
            .to_string(),
        scanned: value
            .get("scanned")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        decoded: value
            .get("decoded")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        perfect: value
            .get("perfect")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        tracked: value
            .get("tracked")
            .and_then(|item| item.as_u64())
            .unwrap_or(0) as u32,
        mode: value.get("mode").and_then(|item| item.as_i64()).unwrap_or(0) as i32,
        detected_mode: value
            .get("detected_mode")
            .and_then(|item| item.as_i64())
            .unwrap_or(0) as i32,
        capture_left: value
            .get("capture_left")
            .and_then(|item| item.as_i64())
            .unwrap_or(0) as i32,
        capture_top: value
            .get("capture_top")
            .and_then(|item| item.as_i64())
            .unwrap_or(0) as i32,
        capture_width: value
            .get("capture_width")
            .and_then(|item| item.as_i64())
            .unwrap_or(0) as i32,
        capture_height: value
            .get("capture_height")
            .and_then(|item| item.as_i64())
            .unwrap_or(0) as i32,
        diagnostics: value
            .get("diagnostics")
            .and_then(|item| item.as_array())
            .map(|items| {
                items
                    .iter()
                    .filter_map(|item| item.as_str().map(ToString::to_string))
                    .collect()
            })
            .unwrap_or_default(),
    })
}
