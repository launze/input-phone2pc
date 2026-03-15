use enigo::{Enigo, Settings, Keyboard, Key, Direction};

pub fn simulate_text_input(text: &str) {
    // 使用剪贴板粘贴方式，完美支持中文标点符号
    if set_clipboard(text) {
        let settings = Settings::default();
        if let Ok(mut enigo) = Enigo::new(&settings) {
            // 模拟 Ctrl+V 粘贴
            let _ = enigo.key(Key::Control, Direction::Press);
            let _ = enigo.key(Key::Unicode('v'), Direction::Click);
            let _ = enigo.key(Key::Control, Direction::Release);
        }
    } else {
        // 剪贴板失败时回退到逐字符输入
        let settings = Settings::default();
        if let Ok(mut enigo) = Enigo::new(&settings) {
            let _ = enigo.text(text);
        }
    }
}

/// 使用 Windows API 设置剪贴板文本
fn set_clipboard(text: &str) -> bool {
    #[cfg(target_os = "windows")]
    {
        use std::ptr;
        use std::mem;

        // Windows API 常量
        const CF_UNICODETEXT: u32 = 13;
        const GMEM_MOVEABLE: u32 = 0x0002;

        #[link(name = "user32")]
        extern "system" {
            fn OpenClipboard(hwnd: *mut std::ffi::c_void) -> i32;
            fn CloseClipboard() -> i32;
            fn EmptyClipboard() -> i32;
            fn SetClipboardData(format: u32, data: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
        }

        #[link(name = "kernel32")]
        extern "system" {
            fn GlobalAlloc(flags: u32, bytes: usize) -> *mut std::ffi::c_void;
            fn GlobalLock(hmem: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
            fn GlobalUnlock(hmem: *mut std::ffi::c_void) -> i32;
        }

        unsafe {
            // 转换为 UTF-16
            let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
            let byte_len = wide.len() * mem::size_of::<u16>();

            let hmem = GlobalAlloc(GMEM_MOVEABLE, byte_len);
            if hmem.is_null() {
                return false;
            }

            let locked = GlobalLock(hmem);
            if locked.is_null() {
                return false;
            }
            ptr::copy_nonoverlapping(wide.as_ptr() as *const u8, locked as *mut u8, byte_len);
            GlobalUnlock(hmem);

            if OpenClipboard(ptr::null_mut()) == 0 {
                return false;
            }
            EmptyClipboard();
            let result = SetClipboardData(CF_UNICODETEXT, hmem);
            CloseClipboard();

            !result.is_null()
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        // 非 Windows 平台回退到 enigo.text()
        false
    }
}
