use arboard::{Clipboard, ImageData};
use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use std::borrow::Cow;
use std::thread;
use std::time::Duration;

pub fn simulate_text_input(text: &str) {
    if set_text_clipboard(text) {
        let _ = paste_clipboard();
    } else {
        let settings = Settings::default();
        if let Ok(mut enigo) = Enigo::new(&settings) {
            let _ = enigo.text(text);
        }
    }
}

pub fn simulate_image_input(image_bytes: &[u8]) -> Result<(), String> {
    set_image_clipboard(image_bytes)?;
    paste_clipboard().map_err(|_| "failed to paste clipboard image".to_string())
}

fn paste_clipboard() -> Result<(), ()> {
    thread::sleep(Duration::from_millis(80));

    let settings = Settings::default();
    let Ok(mut enigo) = Enigo::new(&settings) else {
        return Err(());
    };

    let _ = enigo.key(Key::Control, Direction::Press);
    let _ = enigo.key(Key::Unicode('v'), Direction::Click);
    let _ = enigo.key(Key::Control, Direction::Release);
    Ok(())
}

fn set_text_clipboard(text: &str) -> bool {
    match Clipboard::new() {
        Ok(mut clipboard) => clipboard.set_text(text.to_string()).is_ok(),
        Err(_) => false,
    }
}

fn set_image_clipboard(image_bytes: &[u8]) -> Result<(), String> {
    let image = image::load_from_memory(image_bytes)
        .map_err(|e| format!("failed to decode image: {}", e))?;
    let rgba = image.to_rgba8();
    let (width, height) = rgba.dimensions();
    let data = ImageData {
        width: width as usize,
        height: height as usize,
        bytes: Cow::Owned(rgba.into_raw()),
    };

    let mut clipboard = Clipboard::new().map_err(|e| format!("failed to open clipboard: {}", e))?;
    clipboard
        .set_image(data)
        .map_err(|e| format!("failed to set clipboard image: {}", e))
}
