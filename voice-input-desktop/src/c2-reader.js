const invoke = window.__TAURI_INTERNALS__?.invoke || window.__TAURI__?.core?.invoke;

function debugLog(message) {
    const text = `${new Date().toISOString()} ${message}`;
    console.log('[c2-reader:web]', text);
    if (!invoke) return;
    try {
        Promise.resolve(invoke('debug_c2_reader_log', { message: text })).catch((error) => {
            console.log('[c2-reader:web] debug command failed', error);
        });
    } catch (error) {
        console.log('[c2-reader:web] debug command threw', error);
    }
}

debugLog(`script loaded url=${window.location.href} readyState=${document.readyState}`);

const summaryEl = document.getElementById('reader-summary');
const stateEl = document.getElementById('reader-state');
const framesEl = document.getElementById('reader-frames');
const backlogEl = document.getElementById('reader-backlog');
const filesEl = document.getElementById('reader-files');
const progressEl = document.getElementById('reader-progress');
const outputEl = document.getElementById('reader-output');
const messageEl = document.getElementById('reader-message');
const toggleBtn = document.getElementById('reader-toggle-btn');
const startBtn = document.getElementById('start-reader-btn');
const stopBtn = document.getElementById('stop-reader-btn');
const openOutputBtn = document.getElementById('open-output-btn');
const closeBtn = document.getElementById('close-reader-btn');

let lastStatus = null;
let pollingTimer = null;
let hasStartedBackend = false;
let autoStopping = false;

const requiredElements = {
    summaryEl,
    stateEl,
    framesEl,
    backlogEl,
    filesEl,
    progressEl,
    outputEl,
    messageEl,
    toggleBtn,
    startBtn,
    stopBtn,
    openOutputBtn,
    closeBtn,
};
const missingElements = Object.entries(requiredElements)
    .filter(([, element]) => !element)
    .map(([name]) => name);
if (missingElements.length > 0) {
    debugLog(`missing DOM elements: ${missingElements.join(', ')}`);
} else {
    debugLog('all DOM elements found');
}

function setMessage(text = '', tone = '') {
    if (!messageEl) {
        debugLog(`setMessage skipped, messageEl missing: ${text}`);
        return;
    }
    messageEl.textContent = text;
    messageEl.className = 'message-text';
    if (tone) {
        messageEl.classList.add(tone);
    }
}

if (!invoke) {
    debugLog('Tauri invoke API unavailable');
    setMessage('读取栏初始化失败：无法获取 Tauri invoke API。', 'error');
    if (startBtn) startBtn.disabled = true;
    if (stopBtn) stopBtn.disabled = true;
    if (openOutputBtn) openOutputBtn.disabled = true;
    if (toggleBtn) toggleBtn.disabled = true;
    throw new Error('Tauri invoke API is unavailable');
}
debugLog('Tauri invoke API available');

function renderStatus(status) {
    lastStatus = status || {};
    const available = lastStatus.available !== false;
    const running = Boolean(lastStatus.running);
    const filesDecoded = Number(lastStatus.files_decoded || lastStatus.filesDecoded || 0);
    const hasSavedFile = filesDecoded > 0 || Boolean(lastStatus.last_saved || lastStatus.lastSaved);
    const progress = hasSavedFile ? 100 : Math.max(0, Math.min(100, Number(lastStatus.progress || 0)));

    debugLog(
        `renderStatus available=${available} running=${running} frames=${lastStatus.frames || 0} progress=${progress} decoded=${filesDecoded} saved=${lastStatus.last_saved || lastStatus.lastSaved || ''} error=${lastStatus.error || ''}`
    );

    document.documentElement.style.setProperty('--progress', `${progress}%`);
    progressEl.textContent = `${progress}%`;
    stateEl.textContent = !available ? '不可用' : running ? '截图中' : '待机';
    summaryEl.textContent = !available
        ? '内置读取模块未就绪'
        : running
            ? '正在读取桌面 C2 动态二维码'
            : '可点击开始截图读取';
    framesEl.textContent = String(lastStatus.frames || 0);
    backlogEl.textContent = String(lastStatus.backlog || 0);
    filesEl.textContent = String(filesDecoded);
    outputEl.textContent = lastStatus.output_dir ? `保存目录：${lastStatus.output_dir}` : '';

    startBtn.disabled = !available || running;
    stopBtn.disabled = !available || !running;
    openOutputBtn.disabled = !lastStatus.output_dir;
    toggleBtn.disabled = !available;
    toggleBtn.title = running ? '停止截图' : '开始截图';

    if (lastStatus.error) {
        setMessage(lastStatus.error, available ? 'error' : 'error');
    } else if (lastStatus.last_saved || lastStatus.lastSaved) {
        setMessage(`已保存：${lastStatus.last_saved || lastStatus.lastSaved}`, 'success');
    } else if (running) {
        setMessage('请保持动态二维码完整显示在桌面上。');
    } else if (available) {
        setMessage('');
    }
}

async function refreshStatus() {
    if (!hasStartedBackend) {
        debugLog('refreshStatus skipped before backend start');
        return null;
    }
    try {
        debugLog('invoke get_c2_screen_reader_status start');
        const status = await invoke('get_c2_screen_reader_status');
        debugLog('invoke get_c2_screen_reader_status ok');
        renderStatus(status);
        if (status && !status.running && pollingTimer) {
            window.clearInterval(pollingTimer);
            pollingTimer = null;
        }
        if (status?.running && !autoStopping) {
            const decodedCount = Number(status.files_decoded || status.filesDecoded || 0);
            if (decodedCount > 0 || status.last_saved || status.lastSaved) {
                autoStopping = true;
                debugLog('decoded file detected, auto stop requested');
                await stopReader();
                window.clearInterval(pollingTimer);
            }
        }
        return status;
    } catch (error) {
        debugLog(`invoke get_c2_screen_reader_status failed: ${String(error)}`);
        setMessage(typeof error === 'string' ? error : '读取状态失败', 'error');
        return null;
    }
}

async function startReader() {
    debugLog('start requested');
    toggleBtn.disabled = true;
    startBtn.disabled = true;
    hasStartedBackend = true;
    setMessage('');
    try {
        debugLog('invoke start_c2_screen_reader start');
        autoStopping = false;
        const status = await invoke('start_c2_screen_reader');
        debugLog('invoke start_c2_screen_reader ok');
        renderStatus(status);
        startPolling();
    } catch (error) {
        debugLog(`invoke start_c2_screen_reader failed: ${String(error)}`);
        setMessage(typeof error === 'string' ? error : '启动失败', 'error');
        await refreshStatus();
    } finally {
        toggleBtn.disabled = lastStatus?.available === false;
    }
}

async function stopReader() {
    debugLog('stop requested');
    toggleBtn.disabled = true;
    stopBtn.disabled = true;
    try {
        debugLog('invoke stop_c2_screen_reader start');
        const status = await invoke('stop_c2_screen_reader');
        debugLog('invoke stop_c2_screen_reader ok');
        renderStatus(status);
        setMessage('');
        window.clearInterval(pollingTimer);
    } catch (error) {
        debugLog(`invoke stop_c2_screen_reader failed: ${String(error)}`);
        setMessage(typeof error === 'string' ? error : '停止失败', 'error');
        await refreshStatus();
    } finally {
        toggleBtn.disabled = lastStatus?.available === false;
    }
}

function startPolling() {
    debugLog('startPolling interval=250ms');
    window.clearInterval(pollingTimer);
    pollingTimer = window.setInterval(refreshStatus, 250);
}

toggleBtn.addEventListener('click', async () => {
    debugLog('toggle ring clicked');
    if (lastStatus?.running) {
        await stopReader();
    } else {
        await startReader();
    }
});

startBtn.addEventListener('click', async () => {
    debugLog('start button clicked');
    await startReader();
});

stopBtn.addEventListener('click', async () => {
    debugLog('stop button clicked');
    await stopReader();
});

openOutputBtn.addEventListener('click', async () => {
    debugLog('open output button clicked');
    if (!lastStatus?.output_dir) return;
    try {
        debugLog(`invoke open_path start path=${lastStatus.output_dir}`);
        await invoke('open_path', { path: lastStatus.output_dir });
        debugLog('invoke open_path ok');
    } catch (error) {
        debugLog(`invoke open_path failed: ${String(error)}`);
        setMessage(typeof error === 'string' ? error : '打开目录失败', 'error');
    }
});

closeBtn.addEventListener('click', async () => {
    debugLog('close button clicked');
    try {
        if (lastStatus?.running) {
            await stopReader();
        }
        debugLog('invoke close_c2_screen_reader start');
        await invoke('close_c2_screen_reader');
        debugLog('invoke close_c2_screen_reader ok');
    } catch (error) {
        debugLog(`invoke close_c2_screen_reader failed: ${String(error)}`);
        setMessage(typeof error === 'string' ? error : '关闭窗口失败', 'error');
    }
});

window.addEventListener('beforeunload', () => {
    debugLog('beforeunload');
    window.clearInterval(pollingTimer);
});

debugLog('render initial status');
renderStatus({
    available: true,
    running: false,
    frames: 0,
    progress: 0,
    backlog: 0,
    files_decoded: 0,
    output_dir: '',
    last_saved: '',
    error: ''
});
debugLog('script initialized');
