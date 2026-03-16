// 引入 Tauri API
const invoke = window.__TAURI_INTERNALS__.invoke;

document.addEventListener('DOMContentLoaded', async () => {
    console.log('🚀 应用已加载');

    // 元素引用
    const serverModeToggle = document.getElementById('server-mode-toggle');
    const serverDot = document.getElementById('server-dot');
    const editUrlBtn = document.getElementById('edit-url-btn');
    const urlBar = document.getElementById('url-bar');
    const serverUrlInput = document.getElementById('server-url');

    const statusIndicator = document.getElementById('status-indicator');
    const statusText = document.getElementById('status-text');
    const deviceNameEl = document.getElementById('device-name');
    const unpairBtn = document.getElementById('unpair-btn');

    const pairingSection = document.getElementById('pairing-section');
    const historySection = document.getElementById('history-section');
    const qrArea = document.getElementById('qr-area');
    const qrCodeImg = document.getElementById('qr-code-img');
    const refreshQrBtn = document.getElementById('refresh-qr-btn');
    const historyList = document.getElementById('history-list');
    const historyEmpty = document.getElementById('history-empty');
    const clearHistoryBtn = document.getElementById('clear-history-btn');

    let connectedDeviceId = null;
    let pairedDeviceId = null;      // 配对设备 ID
    let pairedDeviceName = null;    // 配对设备名称
    let isPaired = false;        // 已配对（有配对设备记录）
    let isDeviceOnline = false;  // 配对设备在线
    let serverStatus = 'disconnected';

    // ===== 视图切换 =====
    function switchToHistoryView() {
        pairingSection.style.display = 'none';
        historySection.style.display = 'flex';
    }

    function switchToPairingView() {
        pairingSection.style.display = 'flex';
        historySection.style.display = 'none';
        if (serverStatus === 'connected') {
            loadQrCode();
        }
    }

    // ===== 服务器状态 =====
    function setServerDot(status) {
        serverStatus = status;
        serverDot.className = 'server-dot';
        if (status === 'connected') serverDot.classList.add('connected');
        else if (status === 'connecting') serverDot.classList.add('connecting');
        else if (status === 'error') serverDot.classList.add('error');

        // 服务器连接后：如果没有配对设备，显示二维码
        if (status === 'connected' && !isPaired) {
            loadQrCode();
        }
        if (status !== 'connected') {
            qrArea.style.display = 'none';
        }
    }

    // ===== 连接服务器 =====
    async function doConnectServer(url) {
        if (!url) return;
        try {
            setServerDot('connecting');
            await invoke('connect_server', { url });
            console.log('✅ 连接请求已发送');
            // 等待服务器注册完成后再标记为已连接
            // connect_server 是 await 的，成功返回说明已连接
            setServerDot('connected');
        } catch (error) {
            console.error('❌ 连接服务器失败:', error);
            setServerDot('error');
        }
    }

    // ===== 设备连接/上线 =====
    function onDeviceConnected(name, devId) {
        isPaired = true;
        isDeviceOnline = true;
        statusIndicator.classList.add('connected');
        statusText.textContent = '已连接';
        deviceNameEl.textContent = name || '';
        deviceNameEl.style.display = name ? 'inline' : 'none';
        unpairBtn.style.display = 'inline';
        if (devId) connectedDeviceId = devId;
        switchToHistoryView();
    }

    function onDeviceDisconnected() {
        isDeviceOnline = false;
        statusIndicator.classList.remove('connected');

        invoke('get_config').then(config => {
            if (config.paired_devices && config.paired_devices.length > 0) {
                isPaired = true;
                statusText.textContent = '设备离线';
            } else {
                isPaired = false;
                connectedDeviceId = null;
                statusText.textContent = '等待连接';
                deviceNameEl.style.display = 'none';
                unpairBtn.style.display = 'none';
                switchToPairingView();
            }
        }).catch(() => {
            isPaired = false;
            connectedDeviceId = null;
            statusText.textContent = '等待连接';
            deviceNameEl.style.display = 'none';
            unpairBtn.style.display = 'none';
            switchToPairingView();
        });
    }

    // ===== 1. 先注册 Tauri 事件监听（必须在连接服务器之前） =====
    // 使用 __TAURI_INTERNALS__ 获取 event API（比 window.__TAURI__ 更可靠）
    const tauriEvent = window.__TAURI__?.event || window.__TAURI_INTERNALS__?.event;
    if (tauriEvent && tauriEvent.listen) {
        tauriEvent.listen('device_connected', (event) => {
            console.log('📱 设备连接事件:', JSON.stringify(event));
            const name = event.payload?.device_name || 'Android设备';
            const devId = event.payload?.device_id || null;
            onDeviceConnected(name, devId);
        });

        tauriEvent.listen('device_disconnected', (event) => {
            console.log('📱 设备断开事件:', JSON.stringify(event));
            onDeviceDisconnected();
        });

        tauriEvent.listen('text_received', (event) => {
            console.log('📝 收到文字事件:', JSON.stringify(event));
            const text = event.payload?.text || '';
            if (text) {
                addHistoryItem(text);
                // 收到文字说明设备在线，如果状态还没更新就补上
                if (!isDeviceOnline) {
                    onDeviceConnected(deviceNameEl.textContent || 'Android设备', connectedDeviceId);
                }
            }
        });

        // 监听配对成功事件
        tauriEvent.listen('device_paired', (event) => {
            console.log('✅ 配对成功事件:', JSON.stringify(event));
            const data = event.payload;
            pairedDeviceId = data.device_id;
            pairedDeviceName = data.device_name;
            deviceNameEl.textContent = '已连接：' + data.device_name;
            deviceNameEl.style.display = 'block';
            unpairBtn.style.display = 'block';
            switchToHistoryView();
        });

        // 监听配对失败事件
        tauriEvent.listen('pair_failed', (event) => {
            console.log('❌ 配对失败事件:', JSON.stringify(event));
            const data = event.payload;
            alert('配对失败：' + (data.message || '未知错误'));
        });

        console.log('✅ 事件监听器已注册（使用 tauriEvent API）');
    } else {
        console.error('❌ 无法获取 Tauri event API! window.__TAURI__:', typeof window.__TAURI__, 'window.__TAURI_INTERNALS__:', typeof window.__TAURI_INTERNALS__);
    }

    // ===== 2. 加载配置 =====
    try {
        const config = await invoke('get_config');
        console.log('📋 配置已加载:', config);
        serverModeToggle.checked = config.server_mode_enabled;
        serverUrlInput.value = config.server_url;

        // 检查是否已有配对设备
        if (config.paired_devices && config.paired_devices.length > 0) {
            isPaired = true;
            const firstDevice = config.paired_devices[0];
            connectedDeviceId = firstDevice.device_id;
            switchToHistoryView();
            statusText.textContent = '等待设备上线';
            deviceNameEl.textContent = firstDevice.device_name || '';
            deviceNameEl.style.display = firstDevice.device_name ? 'inline' : 'none';
            unpairBtn.style.display = 'inline';
        }

        // 3. 连接服务器（事件监听器已就绪，不会丢失 PAIRED_DEVICE_ONLINE）
        if (config.server_mode_enabled) {
            doConnectServer(config.server_url);
        }
    } catch (error) {
        console.error('❌ 加载配置失败:', error);
    }

    // ===== 服务器模式开关 =====
    serverModeToggle.addEventListener('change', async (e) => {
        const enabled = e.target.checked;
        try {
            await invoke('set_server_mode', { enabled });
            if (enabled) {
                const url = serverUrlInput.value.trim();
                if (url) doConnectServer(url);
            } else {
                await invoke('disconnect_server');
                setServerDot('disconnected');
                urlBar.style.display = 'none';
                qrArea.style.display = 'none';
            }
        } catch (error) {
            console.error('❌ 设置服务器模式失败:', error);
            e.target.checked = !enabled;
        }
    });

    // ===== 编辑服务器地址 =====
    editUrlBtn.addEventListener('click', () => {
        const showing = urlBar.style.display !== 'none';
        urlBar.style.display = showing ? 'none' : 'flex';
        if (!showing) serverUrlInput.focus();
    });

    serverUrlInput.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') {
            const url = serverUrlInput.value.trim();
            if (!url) return;
            try {
                await invoke('set_server_url', { url });
                await invoke('disconnect_server');
                doConnectServer(url);
                urlBar.style.display = 'none';
            } catch (error) {
                console.error('❌ 连接失败:', error);
            }
        }
    });

    // ===== 二维码 =====
    async function loadQrCode() {
        try {
            const dataUrl = await invoke('generate_pairing_qr');
            qrCodeImg.src = dataUrl;
            qrArea.style.display = 'flex';
        } catch (e) {
            console.error('❌ 生成二维码失败:', e);
        }
    }
    refreshQrBtn.addEventListener('click', loadQrCode);

    // ===== 取消配对 =====
    unpairBtn.addEventListener('click', async () => {
        if (!connectedDeviceId) return;
        try {
            await invoke('unpair_device', { deviceId: connectedDeviceId });
            isPaired = false;
            isDeviceOnline = false;
            connectedDeviceId = null;
            statusIndicator.classList.remove('connected');
            statusText.textContent = '等待连接';
            deviceNameEl.style.display = 'none';
            unpairBtn.style.display = 'none';
            switchToPairingView();
        } catch (e) {
            console.error('❌ 取消配对失败:', e);
        }
    });

    // ===== 历史记录 =====
    function addHistoryItem(text) {
        historyEmpty.style.display = 'none';
        const item = document.createElement('div');
        item.className = 'history-item';
        const time = new Date().toLocaleTimeString('zh-CN');
        item.innerHTML = `<div class="history-text">${escapeHtml(text)}</div><div class="history-time">${time}</div>`;
        if (historyList.firstChild) {
            historyList.insertBefore(item, historyList.firstChild);
        } else {
            historyList.appendChild(item);
        }
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    clearHistoryBtn.addEventListener('click', () => {
        historyList.innerHTML = '<div class="history-empty" id="history-empty">等待手机发送文字...</div>';
    });

    // ===== 定期检查服务器状态 =====
    setInterval(async () => {
        if (serverModeToggle.checked) {
            try {
                const status = await invoke('get_server_status');
                if (status.connected && serverStatus !== 'connected') {
                    setServerDot('connected');
                } else if (!status.connected && serverStatus === 'connected') {
                    setServerDot(status.error ? 'error' : 'disconnected');
                }
            } catch (e) { /* ignore */ }
        }
    }, 5000);

    console.log('✅ 前端加载完成');
});
