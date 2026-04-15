use arboard::{Clipboard, ImageData};
use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use std::borrow::Cow;
use std::path::PathBuf;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

const PRE_PASTE_DELAY_MS: u64 = 80;
const POST_PASTE_RESTORE_DELAY_MS: u64 = 160;

lazy_static::lazy_static! {
    static ref CLIPBOARD_FLOW_LOCK: Mutex<()> = Mutex::new(());
}

enum ClipboardBackup {
    Text(String),
    Html {
        html: String,
        alt_text: Option<String>,
    },
    Image {
        width: usize,
        height: usize,
        bytes: Vec<u8>,
    },
    FileList(Vec<PathBuf>),
    Unavailable,
}

pub fn simulate_text_input(text: &str) {
    if with_preserved_clipboard(|clipboard| {
        clipboard
            .set_text(text.to_string())
            .map_err(|error| format!("failed to set clipboard text: {}", error))
    })
    .is_err()
    {
        let settings = Settings::default();
        if let Ok(mut enigo) = Enigo::new(&settings) {
            let _ = enigo.text(text);
        }
    }
}

pub fn simulate_image_input(image_bytes: &[u8]) -> Result<(), String> {
    let image = decode_image_for_clipboard(image_bytes)?;
    with_preserved_clipboard(move |clipboard| {
        clipboard
            .set_image(image.clone())
            .map_err(|error| format!("failed to set clipboard image: {}", error))
    })
}

fn paste_clipboard() -> Result<(), ()> {
    thread::sleep(Duration::from_millis(PRE_PASTE_DELAY_MS));

    let settings = Settings::default();
    let Ok(mut enigo) = Enigo::new(&settings) else {
        return Err(());
    };

    let _ = enigo.key(Key::Control, Direction::Press);
    let _ = enigo.key(Key::Unicode('v'), Direction::Click);
    let _ = enigo.key(Key::Control, Direction::Release);
    Ok(())
}

fn with_preserved_clipboard<F>(write_clipboard: F) -> Result<(), String>
where
    F: FnOnce(&mut Clipboard) -> Result<(), String>,
{
    let _guard = CLIPBOARD_FLOW_LOCK
        .lock()
        .map_err(|_| "failed to lock clipboard workflow".to_string())?;
    let mut clipboard =
        Clipboard::new().map_err(|error| format!("failed to open clipboard: {}", error))?;
    let backup = backup_clipboard(&mut clipboard);

    if let Err(error) = write_clipboard(&mut clipboard) {
        let _ = restore_clipboard(&mut clipboard, backup);
        return Err(error);
    }

    let paste_result = paste_clipboard().map_err(|_| "failed to paste clipboard".to_string());
    if paste_result.is_ok() {
        thread::sleep(Duration::from_millis(POST_PASTE_RESTORE_DELAY_MS));
    }

    if let Err(error) = restore_clipboard(&mut clipboard, backup) {
        eprintln!("failed to restore clipboard: {}", error);
    }

    paste_result
}

fn backup_clipboard(clipboard: &mut Clipboard) -> ClipboardBackup {
    if let Ok(paths) = clipboard.get().file_list() {
        return ClipboardBackup::FileList(paths);
    }

    if let Ok(html) = clipboard.get().html() {
        let alt_text = clipboard.get_text().ok();
        return ClipboardBackup::Html { html, alt_text };
    }

    if let Ok(image) = clipboard.get_image() {
        return ClipboardBackup::Image {
            width: image.width,
            height: image.height,
            bytes: image.bytes.into_owned(),
        };
    }

    if let Ok(text) = clipboard.get_text() {
        return ClipboardBackup::Text(text);
    }

    ClipboardBackup::Unavailable
}

fn restore_clipboard(clipboard: &mut Clipboard, backup: ClipboardBackup) -> Result<(), String> {
    match backup {
        ClipboardBackup::Text(text) => clipboard
            .set_text(text)
            .map_err(|error| format!("failed to restore clipboard text: {}", error)),
        ClipboardBackup::Html { html, alt_text } => clipboard
            .set_html(html, alt_text)
            .map_err(|error| format!("failed to restore clipboard html: {}", error)),
        ClipboardBackup::Image {
            width,
            height,
            bytes,
        } => clipboard
            .set_image(ImageData {
                width,
                height,
                bytes: Cow::Owned(bytes),
            })
            .map_err(|error| format!("failed to restore clipboard image: {}", error)),
        ClipboardBackup::FileList(paths) => clipboard
            .set()
            .file_list(paths.as_slice())
            .map_err(|error| format!("failed to restore clipboard file list: {}", error)),
        ClipboardBackup::Unavailable => Ok(()),
    }
}

fn decode_image_for_clipboard(image_bytes: &[u8]) -> Result<ImageData<'static>, String> {
    let image = image::load_from_memory(image_bytes)
        .map_err(|e| format!("failed to decode image: {}", e))?;
    let rgba = image.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(ImageData {
        width: width as usize,
        height: height as usize,
        bytes: Cow::Owned(rgba.into_raw()),
    })
}
