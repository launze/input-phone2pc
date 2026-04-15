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
    const eventBanner = document.getElementById('event-banner');

    const statusIndicator = document.getElementById('status-indicator');
    const statusText = document.getElementById('status-text');
    const deviceNameEl = document.getElementById('device-name');
    const unpairBtn = document.getElementById('unpair-btn');

    const pairingSection = document.getElementById('pairing-section');
    const historySection = document.getElementById('history-section');
    const qrArea = document.getElementById('qr-area');
    const qrCodeImg = document.getElementById('qr-code-img');
    const refreshQrBtn = document.getElementById('refresh-qr-btn');
    const copyPairingBtn = document.getElementById('copy-pairing-btn');
    const copyServerUrlBtn = document.getElementById('copy-server-url-btn');
    const historyList = document.getElementById('history-list');
    const historyEmpty = document.getElementById('history-empty');
    const historyPagination = document.getElementById('history-pagination');
    const historyPageHint = document.getElementById('history-page-hint');
    const loadMoreHistoryBtn = document.getElementById('load-more-history-btn');
    const exportWeekBtn = document.getElementById('export-week-btn');
    const exportMonthBtn = document.getElementById('export-month-btn');
    const exportStartDate = document.getElementById('export-start-date');
    const exportEndDate = document.getElementById('export-end-date');
    const exportRangeBtn = document.getElementById('export-range-btn');
    const clearHistoryBtn = document.getElementById('clear-history-btn');
    const toggleAdvancedToolsBtn = document.getElementById('toggle-advanced-tools-btn');
    const advancedToolsPanel = document.getElementById('advanced-tools-panel');
    const showPairingBtn = document.getElementById('show-pairing-btn');
    const backToHistoryBtn = document.getElementById('back-to-history-btn');
    const toggleAiPanelBtn = document.getElementById('toggle-ai-panel-btn');
    const toggleAiSettingsBtn = document.getElementById('toggle-ai-settings-btn');
    const aiReportPanel = document.getElementById('ai-report-panel');
    const aiConfigCard = document.getElementById('ai-config-card');
    const aiSetupHint = document.getElementById('ai-setup-hint');
    const saveOpenAiConfigBtn = document.getElementById('save-openai-config-btn');
    const openaiApiKeyInput = document.getElementById('openai-api-key');
    const openaiApiUrlInput = document.getElementById('openai-api-url');
    const openaiModelNameInput = document.getElementById('openai-model-name');
    const weeklyPromptTemplateInput = document.getElementById('weekly-prompt-template');
    const monthlyPromptTemplateInput = document.getElementById('monthly-prompt-template');
    const generateWeeklyReportBtn = document.getElementById('generate-weekly-report-btn');
    const generateMonthlyReportBtn = document.getElementById('generate-monthly-report-btn');
    const aiReportStatus = document.getElementById('ai-report-status');
    const aiReportOutput = document.getElementById('ai-report-output');
    const copyAiReportBtn = document.getElementById('copy-ai-report-btn');
    const copyAiReportInlineBtn = document.getElementById('copy-ai-report-inline-btn');
    const exportAiReportWordBtn = document.getElementById('export-ai-report-word-btn');

    let connectedDeviceId = null;
    let pairedDeviceId = null;      // 配对设备 ID
    let pairedDeviceName = null;    // 配对设备名称
    let isPaired = false;        // 已配对（有配对设备记录）
    let isDeviceOnline = false;  // 配对设备在线
    let hasConnectedDeviceBefore = false;
    let serverStatus = 'disconnected';
    let serverReconnectTimer = null;
    let serverConnectInFlight = false;
    const HISTORY_PAGE_SIZE = 100;
    const HISTORY_TOP_THRESHOLD = 24;
    let historyRecords = [];
    let historyHasMore = false;
    let historyLoading = false;
    let historyInitialLoaded = false;
    let historyCursor = null;
    let aiReportBusy = false;
    let currentAiReport = null;
    let aiConfigured = false;
    let currentAiReportContent = '';
    let currentAiRequestId = null;
    let aiReportShouldAutoScroll = true;
    let aiReportIgnoreScrollEvent = false;
    const AI_REPORT_SCROLL_THRESHOLD = 24;

    function getUnpairedDetail() {
        if (!serverModeToggle.checked) {
            return '左侧二维码一直可扫，手机也可粘贴配对信息';
        }
        if (serverStatus === 'connected') {
            return '左侧二维码一直可扫，跨网络也能连接';
        }
        if (serverStatus === 'connecting') {
            return '远程连接中，同一 Wi-Fi 也可以先扫码';
        }
        return '左侧二维码一直可扫；跨网络前先打开远程连接';
    }

    function getHistoryEmptyMessage() {
        if (isDeviceOnline) {
            return '已连接，可直接从手机发送文字、语音或图片';
        }
        if (isPaired) {
            if (hasConnectedDeviceBefore) {
                return '手机暂时离线，重新打开 App 后内容会继续显示在这里';
            }
            return '已配对，打开手机 App 后发送的内容会显示在这里';
        }
        return '先扫码配对，接收记录会显示在这里';
    }

    function refreshHistoryEmptyState() {
        if (historyRecords.length > 0) {
            return;
        }
        historyList.innerHTML = `<div class="history-empty" id="history-empty">${escapeHtml(getHistoryEmptyMessage())}</div>`;
        historyList.scrollTop = 0;
        updateHistoryPagination();
    }

    function renderToolbarStatus() {
        const pairedName = pairedDeviceName || '已配对设备';
        let tone = 'idle';
        let title = '未配对';
        let detail = getUnpairedDetail();
        let showUnpair = false;

        if (isDeviceOnline) {
            tone = 'connected';
            title = '已连接，可直接输入';
            detail = [
                pairedDeviceName || '已连接设备',
                '手机发送后会立即显示在记录里'
            ].filter(Boolean).join(' · ');
            showUnpair = true;
        } else if (isPaired) {
            showUnpair = true;
            if (hasConnectedDeviceBefore) {
                tone = 'offline';
                title = '已配对，手机暂时离线';
                detail = `${pairedName} · 重新打开手机 App 后会自动恢复`;
            } else {
                tone = 'paired';
                title = '已配对，等待手机连接';
                detail = serverModeToggle.checked && serverStatus === 'connected'
                    ? `${pairedName} · 手机打开 App 后可直接跨网络输入`
                    : `${pairedName} · 打开手机 App 后即可开始输入`;
            }
        }

        statusIndicator.className = 'status-indicator';
        statusIndicator.classList.add(tone);
        statusText.textContent = title;
        deviceNameEl.textContent = detail;
        deviceNameEl.style.display = detail ? 'block' : 'none';
        unpairBtn.style.display = showUnpair ? 'inline-flex' : 'none';
        refreshHistoryEmptyState();
    }

    // ===== 视图切换 =====
    function switchToHistoryView() {
        pairingSection.style.display = 'none';
        historySection.style.display = 'flex';
        syncPairingActionVisibility();
    }

    function switchToPairingView() {
        pairingSection.style.display = 'flex';
        historySection.style.display = 'none';
        loadQrCode();
        syncPairingActionVisibility();
    }

    function syncPairingActionVisibility() {
        if (backToHistoryBtn) {
            backToHistoryBtn.style.display = isPaired ? 'inline-flex' : 'none';
        }
    }

    function setAdvancedToolsVisible(visible) {
        advancedToolsPanel.style.display = visible ? 'block' : 'none';
        toggleAdvancedToolsBtn.classList.toggle('active', visible);
        if (!visible) {
            toggleAiPanel(false);
        }
    }

    function showEventBanner(message, tone = '', durationMs = 4500) {
        if (!eventBanner) return;
        eventBanner.textContent = message || '';
        eventBanner.className = 'event-banner';
        if (tone) {
            eventBanner.classList.add(tone);
        }
        eventBanner.style.display = message ? 'block' : 'none';
        if (!message) return;

        window.clearTimeout(showEventBanner.timer);
        showEventBanner.timer = window.setTimeout(() => {
            eventBanner.style.display = 'none';
        }, durationMs);
    }

    async function copyTextToClipboard(content, successMessage) {
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(content);
            } else {
                const tempTextArea = document.createElement('textarea');
                tempTextArea.value = content;
                document.body.appendChild(tempTextArea);
                tempTextArea.select();
                document.execCommand('copy');
                document.body.removeChild(tempTextArea);
            }
            showEventBanner(successMessage);
        } catch (error) {
            console.error('❌ 复制失败:', error);
            showEventBanner('复制失败，请稍后重试', 'error');
        }
    }

    function normalizeOpenAiConfig(openai = {}) {
        return {
            api_key: (openai.api_key || openai.apiKey || '').trim(),
            api_url: (openai.api_url || openai.apiUrl || '').trim(),
            model_name: (openai.model_name || openai.modelName || '').trim(),
            weekly_prompt_template: openai.weekly_prompt_template || openai.weeklyPromptTemplate || '',
            monthly_prompt_template: openai.monthly_prompt_template || openai.monthlyPromptTemplate || ''
        };
    }

    function hasConfiguredOpenAi(openai = {}) {
        const normalized = normalizeOpenAiConfig(openai);
        return Boolean(
            normalized.api_key &&
            normalized.model_name &&
            normalized.weekly_prompt_template.trim() &&
            normalized.monthly_prompt_template.trim()
        );
    }

    function syncAiPanelUi() {
        const panelOpen = aiReportPanel.style.display !== 'none';
        const settingsOpen = aiConfigCard.style.display !== 'none';
        toggleAiPanelBtn.classList.toggle('active', panelOpen);
        toggleAiSettingsBtn.classList.toggle('active', panelOpen && settingsOpen);
        aiReportPanel.classList.toggle('settings-hidden', !settingsOpen);
        aiSetupHint.textContent = aiConfigured
            ? '支持流式生成与 Markdown 预览。'
            : '请先填写 AI 配置。';
    }

    function setAiSettingsVisible(visible) {
        aiConfigCard.style.display = visible ? 'flex' : 'none';
        syncAiPanelUi();
    }

    function toggleAiPanel(forceOpen, { revealSettings = null } = {}) {
        const shouldOpen = typeof forceOpen === 'boolean'
            ? forceOpen
            : aiReportPanel.style.display === 'none';
        if (shouldOpen) {
            setAdvancedToolsVisible(true);
        }
        aiReportPanel.style.display = shouldOpen ? 'grid' : 'none';
        if (!shouldOpen) {
            setAiSettingsVisible(false);
            syncAiPanelUi();
            return;
        }

        const nextRevealSettings = revealSettings ?? !aiConfigured;
        setAiSettingsVisible(nextRevealSettings);
        syncAiPanelUi();
    }

    function populateOpenAiConfig(openai = {}) {
        const normalized = normalizeOpenAiConfig(openai);
        openaiApiKeyInput.value = normalized.api_key;
        openaiApiUrlInput.value = normalized.api_url || 'https://api.openai.com/v1/responses';
        openaiModelNameInput.value = normalized.model_name || 'gpt-5-mini';
        weeklyPromptTemplateInput.value = normalized.weekly_prompt_template;
        monthlyPromptTemplateInput.value = normalized.monthly_prompt_template;
    }

    function collectOpenAiConfigFromForm() {
        return {
            api_key: openaiApiKeyInput.value.trim(),
            api_url: openaiApiUrlInput.value.trim(),
            model_name: openaiModelNameInput.value.trim(),
            weekly_prompt_template: weeklyPromptTemplateInput.value,
            monthly_prompt_template: monthlyPromptTemplateInput.value
        };
    }

    function setAiReportStatus(message, tone = '') {
        aiReportStatus.textContent = message || '';
        aiReportStatus.className = 'ai-report-status';
        if (tone) {
            aiReportStatus.classList.add(tone);
        }
    }

    function createAiReportRequestId(period) {
        return `${period}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }

    function updateAiReportActionState() {
        const hasContent = Boolean(currentAiReportContent.trim());
        copyAiReportBtn.disabled = !hasContent;
        copyAiReportInlineBtn.disabled = !hasContent;
        exportAiReportWordBtn.disabled = aiReportBusy || !hasContent;
    }

    function isAiReportNearBottom() {
        return aiReportOutput.scrollHeight - aiReportOutput.scrollTop - aiReportOutput.clientHeight <= AI_REPORT_SCROLL_THRESHOLD;
    }

    function scrollAiReportToBottom() {
        aiReportIgnoreScrollEvent = true;
        aiReportOutput.scrollTop = aiReportOutput.scrollHeight;
        requestAnimationFrame(() => {
            aiReportIgnoreScrollEvent = false;
        });
    }

    function setAiReportContent(content = '', { resetAutoScroll = false } = {}) {
        currentAiReportContent = content;
        if (resetAutoScroll) {
            aiReportShouldAutoScroll = true;
        }
        renderAiReportMarkdown(currentAiReportContent);
        updateAiReportActionState();
    }

    function appendAiReportDelta(delta = '') {
        if (!delta) return;
        currentAiReportContent += delta;
        renderAiReportMarkdown(currentAiReportContent);
        updateAiReportActionState();
    }

    function renderAiReportMarkdown(markdown = '') {
        const normalized = markdown.trim();
        if (!normalized) {
            aiReportOutput.innerHTML = '<div class="ai-report-placeholder">报告将在这里实时显示。</div>';
            aiReportShouldAutoScroll = true;
            scrollAiReportToBottom();
            return;
        }

        const previousScrollTop = aiReportOutput.scrollTop;
        aiReportOutput.innerHTML = markdownToHtml(markdown);
        if (aiReportShouldAutoScroll) {
            scrollAiReportToBottom();
        } else {
            aiReportIgnoreScrollEvent = true;
            aiReportOutput.scrollTop = previousScrollTop;
            requestAnimationFrame(() => {
                aiReportIgnoreScrollEvent = false;
            });
        }
    }

    function markdownToHtml(markdown) {
        const lines = String(markdown || '').replace(/\r\n/g, '\n').split('\n');
        const blocks = [];
        let paragraph = [];
        let listType = null;
        let listItems = [];

        function flushParagraph() {
            if (!paragraph.length) return;
            blocks.push(`<p>${paragraph.map(renderInlineMarkdown).join('<br>')}</p>`);
            paragraph = [];
        }

        function flushList() {
            if (!listType || !listItems.length) return;
            blocks.push(`<${listType}>${listItems.join('')}</${listType}>`);
            listType = null;
            listItems = [];
        }

        for (const rawLine of lines) {
            const line = rawLine.trim();

            if (!line) {
                flushParagraph();
                flushList();
                continue;
            }

            const headingMatch = line.match(/^(#{1,6})\s+(.*)$/);
            if (headingMatch) {
                flushParagraph();
                flushList();
                const level = Math.min(headingMatch[1].length, 6);
                blocks.push(`<h${level}>${renderInlineMarkdown(headingMatch[2])}</h${level}>`);
                continue;
            }

            if (/^---+$/.test(line) || /^\*\*\*+$/.test(line)) {
                flushParagraph();
                flushList();
                blocks.push('<hr>');
                continue;
            }

            const unorderedMatch = line.match(/^[-*+]\s+(.*)$/);
            if (unorderedMatch) {
                flushParagraph();
                if (listType !== 'ul') {
                    flushList();
                    listType = 'ul';
                }
                listItems.push(`<li>${renderInlineMarkdown(unorderedMatch[1])}</li>`);
                continue;
            }

            const orderedMatch = line.match(/^(\d+)([.)、）])\s+(.*)$/);
            if (orderedMatch) {
                flushParagraph();
                if (listType !== 'ol') {
                    flushList();
                    listType = 'ol';
                }
                listItems.push(`<li>${renderInlineMarkdown(orderedMatch[3])}</li>`);
                continue;
            }

            const quoteMatch = line.match(/^>\s?(.*)$/);
            if (quoteMatch) {
                flushParagraph();
                flushList();
                blocks.push(`<blockquote>${renderInlineMarkdown(quoteMatch[1])}</blockquote>`);
                continue;
            }

            paragraph.push(line);
        }

        flushParagraph();
        flushList();
        return blocks.join('');
    }

    function renderInlineMarkdown(text) {
        return escapeHtml(text)
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/\*([^*]+)\*/g, '<em>$1</em>');
    }

    function refreshAiConfigurationState(openai = collectOpenAiConfigFromForm()) {
        aiConfigured = hasConfiguredOpenAi(openai);
        syncAiPanelUi();
        return aiConfigured;
    }

    function setAiReportBusy(busy, actionLabel = '处理中') {
        aiReportBusy = busy;
        const busyText = `${actionLabel}...`;
        saveOpenAiConfigBtn.disabled = busy;
        generateWeeklyReportBtn.disabled = busy;
        generateMonthlyReportBtn.disabled = busy;
        updateAiReportActionState();
        if (busy) {
            generateWeeklyReportBtn.textContent = busyText;
            generateMonthlyReportBtn.textContent = busyText;
        } else {
            generateWeeklyReportBtn.textContent = '生成周报';
            generateMonthlyReportBtn.textContent = '生成月报';
        }
    }

    async function saveOpenAiConfig({ silent = false } = {}) {
        const openai = collectOpenAiConfigFromForm();
        await invoke('save_openai_report_config', { openai });
        refreshAiConfigurationState(openai);
        if (!silent) {
            setAiReportStatus('OpenAI 配置已保存', 'success');
        }
    }

    function ensureAiConfigured(actionLabel) {
        if (refreshAiConfigurationState()) {
            return true;
        }

        toggleAiPanel(true, { revealSettings: true });
        setAiReportStatus(`请先完成 AI 配置后再${actionLabel}`, 'error');
        alert(`请先完成 AI 配置后再${actionLabel}`);
        return false;
    }

    async function generateAiReport(period) {
        if (aiReportBusy) return;

        const isWeek = period === 'week';
        const actionLabel = isWeek ? '生成周报' : '生成月报';
        if (!ensureAiConfigured(actionLabel)) {
            return;
        }
        try {
            setAiReportBusy(true, actionLabel);
            setAiReportStatus(`${actionLabel}中，请稍候...`);
            toggleAiPanel(true, { revealSettings: false });
            setAiReportContent('', { resetAutoScroll: true });
            currentAiReport = null;
            const requestId = createAiReportRequestId(period);
            currentAiRequestId = requestId;
            await saveOpenAiConfig({ silent: true });

            const startAt = isWeek ? getStartOfWeek().getTime() : getStartOfMonth().getTime();
            const endAt = Date.now();
            const report = await invoke('generate_openai_report', {
                period,
                startAt,
                endAt,
                requestId
            });

            if (currentAiRequestId === requestId) {
                setAiReportContent(report?.content || currentAiReportContent);
                currentAiRequestId = null;
            }
            currentAiReport = {
                period,
                startAt,
                endAt,
                title: isWeek ? '工作周报' : '工作月报'
            };
            toggleAiPanel(true, { revealSettings: false });
            const total = report?.record_count ?? report?.recordCount ?? 0;
            const used = report?.used_record_count ?? report?.usedRecordCount ?? total;
            setAiReportStatus(
                used < total
                    ? `${actionLabel}已生成，使用 ${used}/${total} 条记录`
                    : `${actionLabel}已生成，共使用 ${total} 条记录`,
                'success'
            );
        } catch (error) {
            console.error(`❌ ${actionLabel}失败:`, error);
            const message = typeof error === 'string' ? error : error?.message || '生成失败';
            currentAiRequestId = null;
            setAiReportStatus(message, 'error');
            alert(`${actionLabel}失败：${message}`);
        } finally {
            setAiReportBusy(false);
        }
    }

    async function copyAiReport() {
        const content = currentAiReportContent.trim();
        if (!content) {
            setAiReportStatus('当前没有可复制的报告内容', 'error');
            return;
        }

        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(content);
            } else {
                const tempTextArea = document.createElement('textarea');
                tempTextArea.value = content;
                document.body.appendChild(tempTextArea);
                tempTextArea.select();
                document.execCommand('copy');
                document.body.removeChild(tempTextArea);
            }
            setAiReportStatus('报告内容已复制到剪贴板', 'success');
        } catch (error) {
            console.error('❌ 复制报告失败:', error);
            setAiReportStatus('复制失败，请手动选择文本复制', 'error');
        }
    }

    async function exportAiReportWord() {
        const content = currentAiReportContent.trim();
        if (!content) {
            setAiReportStatus('当前没有可导出的报告内容', 'error');
            return;
        }
        if (!currentAiReport) {
            setAiReportStatus('请先生成周报或月报，再导出 Word', 'error');
            return;
        }

        try {
            setAiReportBusy(true, '导出 Word');
            setAiReportStatus('正在导出 Word，请稍候...');
            const result = await invoke('export_openai_report_word', {
                period: currentAiReport.period,
                title: currentAiReport.title,
                startAt: currentAiReport.startAt,
                endAt: currentAiReport.endAt,
                content
            });
            const savedPath = result?.saved_path || result?.savedPath;
            setAiReportStatus(savedPath ? `Word 已导出到：${savedPath}` : 'Word 已导出', 'success');
            if (savedPath) {
                alert(`Word 导出完成：\n${savedPath}`);
            }
        } catch (error) {
            console.error('❌ 导出 Word 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出失败';
            setAiReportStatus(message, 'error');
            alert(`导出 Word 失败：${message}`);
        } finally {
            setAiReportBusy(false);
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

        if (status === 'connected') {
            clearServerReconnectTimer();
        }

        renderToolbarStatus();
    }

    function clearServerReconnectTimer() {
        if (serverReconnectTimer) {
            clearTimeout(serverReconnectTimer);
            serverReconnectTimer = null;
        }
    }

    function scheduleServerReconnect(delayMs = 3000) {
        if (!serverModeToggle.checked) return;
        if (serverConnectInFlight || serverStatus === 'connected' || serverStatus === 'connecting') return;

        const url = serverUrlInput.value.trim();
        if (!url) return;

        clearServerReconnectTimer();
        serverReconnectTimer = setTimeout(() => {
            serverReconnectTimer = null;
            if (!serverModeToggle.checked || serverConnectInFlight || serverStatus === 'connected') {
                return;
            }
            doConnectServer(url);
        }, delayMs);
    }

    // ===== 连接服务器 =====
    async function doConnectServer(url) {
        if (!url || serverConnectInFlight) return;
        try {
            serverConnectInFlight = true;
            clearServerReconnectTimer();
            setServerDot('connecting');
            await invoke('connect_server', { url });
            console.log('✅ 连接请求已发送');
            // 等待服务器注册完成后再标记为已连接
            // connect_server 是 await 的，成功返回说明已连接
            setServerDot('connected');
        } catch (error) {
            console.error('❌ 连接服务器失败:', error);
            setServerDot('error');
            scheduleServerReconnect();
        } finally {
            serverConnectInFlight = false;
        }
    }

    // ===== 设备连接/上线 =====
    function onDeviceConnected(name, devId) {
        isPaired = true;
        isDeviceOnline = true;
        hasConnectedDeviceBefore = true;
        if (name) pairedDeviceName = name;
        if (devId) {
            connectedDeviceId = devId;
            pairedDeviceId = devId;
        }
        renderToolbarStatus();
        switchToHistoryView();
    }

    function onDeviceDisconnected() {
        isDeviceOnline = false;

        invoke('get_config').then(config => {
            if (config.paired_devices && config.paired_devices.length > 0) {
                isPaired = true;
                const firstDevice = config.paired_devices[0];
                pairedDeviceId = firstDevice.device_id || pairedDeviceId;
                connectedDeviceId = pairedDeviceId || connectedDeviceId;
                pairedDeviceName = firstDevice.device_name || pairedDeviceName;
            } else {
                isPaired = false;
                connectedDeviceId = null;
                pairedDeviceId = null;
                pairedDeviceName = null;
                hasConnectedDeviceBefore = false;
                switchToPairingView();
            }
            renderToolbarStatus();
        }).catch(() => {
            isPaired = false;
            connectedDeviceId = null;
            pairedDeviceId = null;
            pairedDeviceName = null;
            hasConnectedDeviceBefore = false;
            renderToolbarStatus();
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
            showEventBanner(`设备已连接：${name}`);
        });

        tauriEvent.listen('device_disconnected', (event) => {
            console.log('📱 设备断开事件:', JSON.stringify(event));
            onDeviceDisconnected();
            showEventBanner('设备已离线，仍可继续查看记录', 'error');
        });

        tauriEvent.listen('text_received', (event) => {
            console.log('📝 收到文字事件:', JSON.stringify(event));
            const text = event.payload?.text || '';
            const deliveryMode = event.payload?.delivery_mode || 'live';
            if (text && deliveryMode !== 'offline_sync' && !isDeviceOnline) {
                onDeviceConnected(pairedDeviceName || 'Android设备', connectedDeviceId || pairedDeviceId);
            }
        });

        tauriEvent.listen('history_recorded', (event) => {
            console.log('🗂️ 历史记录已持久化:', JSON.stringify(event));
            if (event.payload) {
                addOrUpdateHistoryRecord(event.payload);
            }
        });

        tauriEvent.listen('relay_stored', (event) => {
            console.log('📥 服务器已暂存消息:', JSON.stringify(event));
            showEventBanner('设备离线，消息已暂存到服务器');
        });

        tauriEvent.listen('notification_received', (event) => {
            const notification = event.payload?.notification || {};
            const summary = [
                notification.app_name || notification.appName,
                notification.title,
                notification.text
            ].filter(Boolean).join(' | ');
            showEventBanner(summary || '收到一条通知');
        });

        tauriEvent.listen('openai_report_delta', (event) => {
            const payload = event.payload || {};
            if (!payload.request_id || payload.request_id !== currentAiRequestId) {
                return;
            }
            appendAiReportDelta(payload.delta || '');
        });

        // 监听配对成功事件
        tauriEvent.listen('device_paired', (event) => {
            console.log('✅ 配对成功事件:', JSON.stringify(event));
            const data = event.payload;
            pairedDeviceId = data.device_id;
            connectedDeviceId = data.device_id;
            pairedDeviceName = data.device_name;
            isPaired = true;
            isDeviceOnline = false;
            hasConnectedDeviceBefore = false;
            renderToolbarStatus();
            switchToHistoryView();
            showEventBanner(`配对成功：${data.device_name}`);
        });

        // 监听配对失败事件
        tauriEvent.listen('pair_failed', (event) => {
            console.log('❌ 配对失败事件:', JSON.stringify(event));
            const data = event.payload;
            showEventBanner(`配对失败：${data.message || '未知错误'}`, 'error');
            alert('配对失败：' + (data.message || '未知错误'));
        });

        console.log('✅ 事件监听器已注册（使用 tauriEvent API）');
    } else {
        console.error('❌ 无法获取 Tauri event API! window.__TAURI__:', typeof window.__TAURI__, 'window.__TAURI_INTERNALS__:', typeof window.__TAURI_INTERNALS__);
    }

    // ===== 2. 加载配置 =====
    try {
        await loadHistory();
        const config = await invoke('get_config');
        console.log('📋 配置已加载:', config);
        serverModeToggle.checked = config.server_mode_enabled;
        serverUrlInput.value = config.server_url;
        const openaiConfig = config.openai || {};
        populateOpenAiConfig(openaiConfig);
        refreshAiConfigurationState(openaiConfig);
        setAiReportContent('', { resetAutoScroll: true });
        if (aiConfigured) {
            setAiReportStatus('');
            toggleAiPanel(false);
        } else {
            toggleAiPanel(true, { revealSettings: true });
            setAiReportStatus(
                '请先填写 AI 配置后再生成报告。阿里云百炼兼容模式可直接填写 https://dashscope.aliyuncs.com/compatible-mode/v1',
                'error'
            );
        }

        // 检查是否已有配对设备
        if (config.paired_devices && config.paired_devices.length > 0) {
            isPaired = true;
            const firstDevice = config.paired_devices[0];
            pairedDeviceId = firstDevice.device_id;
            pairedDeviceName = firstDevice.device_name || null;
            connectedDeviceId = firstDevice.device_id;
            switchToHistoryView();
        } else {
            isPaired = false;
            pairedDeviceId = null;
            pairedDeviceName = null;
            connectedDeviceId = null;
            isDeviceOnline = false;
            hasConnectedDeviceBefore = false;
            switchToPairingView();
        }
        renderToolbarStatus();

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
                clearServerReconnectTimer();
                await invoke('disconnect_server');
                setServerDot('disconnected');
                urlBar.style.display = 'none';
                if (!isPaired) {
                    loadQrCode();
                }
            }
            renderToolbarStatus();
        } catch (error) {
            console.error('❌ 设置服务器模式失败:', error);
            e.target.checked = !enabled;
            renderToolbarStatus();
        }
    });

    // ===== 编辑服务器地址 =====
    editUrlBtn.addEventListener('click', () => {
        const showing = urlBar.style.display !== 'none';
        urlBar.style.display = showing ? 'none' : 'flex';
        if (!showing) serverUrlInput.focus();
    });

    toggleAdvancedToolsBtn.addEventListener('click', () => {
        const nextVisible = advancedToolsPanel.style.display === 'none';
        setAdvancedToolsVisible(nextVisible);
    });

    showPairingBtn.addEventListener('click', () => {
        switchToPairingView();
    });

    backToHistoryBtn.addEventListener('click', () => {
        switchToHistoryView();
    });

    toggleAiPanelBtn.addEventListener('click', () => {
        const panelOpen = aiReportPanel.style.display !== 'none';
        if (panelOpen) {
            toggleAiPanel(false);
            return;
        }
        toggleAiPanel(true, { revealSettings: !aiConfigured });
    });

    toggleAiSettingsBtn.addEventListener('click', () => {
        const panelOpen = aiReportPanel.style.display !== 'none';
        const settingsOpen = aiConfigCard.style.display !== 'none';
        if (!panelOpen) {
            toggleAiPanel(true, { revealSettings: true });
            return;
        }
        setAiSettingsVisible(!settingsOpen);
    });

    saveOpenAiConfigBtn.addEventListener('click', async () => {
        try {
            await saveOpenAiConfig();
        } catch (error) {
            console.error('❌ 保存 OpenAI 配置失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存失败';
            setAiReportStatus(message, 'error');
            alert(`保存 OpenAI 配置失败：${message}`);
        }
    });

    copyPairingBtn.addEventListener('click', async () => {
        try {
            const payload = await invoke('get_pairing_payload');
            await copyTextToClipboard(payload, '配对信息已复制，可直接在手机端导入');
        } catch (error) {
            console.error('❌ 复制配对信息失败:', error);
            showEventBanner('复制配对信息失败', 'error');
        }
    });

    copyServerUrlBtn.addEventListener('click', async () => {
        const url = serverUrlInput.value.trim();
        if (!url) {
            showEventBanner('当前没有可复制的服务器地址', 'error');
            return;
        }
        await copyTextToClipboard(url, '服务器地址已复制');
    });

    aiReportOutput.addEventListener('scroll', () => {
        if (aiReportIgnoreScrollEvent) return;
        aiReportShouldAutoScroll = isAiReportNearBottom();
    });

    [openaiApiKeyInput, openaiApiUrlInput, openaiModelNameInput, weeklyPromptTemplateInput, monthlyPromptTemplateInput]
        .forEach((element) => {
            element.addEventListener('input', () => {
                refreshAiConfigurationState();
            });
        });

    serverUrlInput.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') {
            const url = serverUrlInput.value.trim();
            if (!url) return;
            try {
                await invoke('set_server_url', { url });
                clearServerReconnectTimer();
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
        const targetDeviceId = pairedDeviceId || connectedDeviceId;
        if (!targetDeviceId) return;
        try {
            await invoke('unpair_device', { deviceId: targetDeviceId });
            isPaired = false;
            isDeviceOnline = false;
            hasConnectedDeviceBefore = false;
            connectedDeviceId = null;
            pairedDeviceId = null;
            pairedDeviceName = null;
            renderToolbarStatus();
            switchToPairingView();
            showEventBanner('已取消配对，可重新扫码连接');
        } catch (e) {
            console.error('❌ 取消配对失败:', e);
        }
    });

    // ===== 历史记录 =====
    async function loadHistory() {
        await loadHistoryPage({ reset: true });
    }

    async function loadHistoryPage({ reset = false } = {}) {
        if (historyLoading) return;

        try {
            historyLoading = true;
            updateHistoryPagination();
            const scrollSnapshot = captureHistoryScrollSnapshot();

            const page = await invoke('get_message_history_page', {
                limit: HISTORY_PAGE_SIZE,
                beforeReceivedAt: reset ? null : historyCursor?.received_at ?? null,
                beforeId: reset ? null : historyCursor?.id ?? null
            });
            const pageRecords = Array.isArray(page?.records) ? page.records : [];

            if (reset) {
                historyRecords = pageRecords;
            } else {
                const existingIds = new Set(historyRecords.map(item => item.id));
                historyRecords = historyRecords.concat(
                    pageRecords.filter(item => !existingIds.has(item.id))
                );
            }

            historyRecords.sort(compareHistoryRecord);
            historyHasMore = Boolean(page?.has_more);
            historyInitialLoaded = true;
            refreshHistoryCursor();
            renderHistory({
                mode: reset ? 'reset' : 'append',
                scrollSnapshot
            });
        } catch (error) {
            console.error('❌ 加载历史记录失败:', error);
        } finally {
            historyLoading = false;
            updateHistoryPagination();
        }
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function renderHistory({ mode = 'reset', scrollSnapshot = captureHistoryScrollSnapshot(), forceScrollTop = false } = {}) {
        if (!historyRecords.length) {
            historyList.innerHTML = `<div class="history-empty" id="history-empty">${escapeHtml(getHistoryEmptyMessage())}</div>`;
            historyList.scrollTop = 0;
            updateHistoryPagination();
            return;
        }

        historyList.innerHTML = historyRecords.map(renderHistoryItem).join('');
        restoreHistoryScroll(mode, scrollSnapshot, forceScrollTop);
        updateHistoryPagination();
    }

    function renderHistoryItem(record) {
        const sentAt = formatDateTime(record.sent_at);
        const receivedAt = formatDateTime(record.received_at);
        const offlineBadge = record.delivery_mode === 'offline_sync'
            ? '<span class="history-badge offline">离线补发</span>'
            : '';
        const itemClass = record.delivery_mode === 'offline_sync'
            ? 'history-item offline-sync'
            : 'history-item';

        return `
            <div class="${itemClass}" data-record-id="${escapeHtml(record.id)}">
                <div class="history-text">${escapeHtml(record.content)}</div>
                <div class="history-time">
                    <span>发送: ${escapeHtml(sentAt)}</span>
                    <span>接收: ${escapeHtml(receivedAt)}</span>
                </div>
                <div class="history-meta">
                    <span>来源: ${escapeHtml(record.from_device_name || record.from_device_id || '未知设备')}</span>
                    <span>通道: ${escapeHtml(record.via)}</span>
                    ${offlineBadge}
                </div>
            </div>
        `;
    }

    function addOrUpdateHistoryRecord(record) {
        const existingIndex = historyRecords.findIndex(item => item.id === record.id);
        const scrollSnapshot = captureHistoryScrollSnapshot();
        const shouldStickToTop = isHistoryNearTop();

        historyRecords = historyRecords.filter(item => item.id !== record.id);
        historyRecords.unshift(record);
        historyRecords.sort(compareHistoryRecord);
        refreshHistoryCursor();
        renderHistory({
            mode: existingIndex >= 0 ? 'update' : 'prepend',
            scrollSnapshot,
            forceScrollTop: shouldStickToTop
        });
    }

    function compareHistoryRecord(a, b) {
        const timeDiff = (b.received_at || 0) - (a.received_at || 0);
        if (timeDiff !== 0) return timeDiff;
        return String(b.id || '').localeCompare(String(a.id || ''));
    }

    function refreshHistoryCursor() {
        const oldestRecord = historyRecords[historyRecords.length - 1];
        historyCursor = oldestRecord ? {
            id: oldestRecord.id,
            received_at: oldestRecord.received_at
        } : null;
    }

    function updateHistoryPagination() {
        const hasRecords = historyRecords.length > 0;
        const shouldShow = historyLoading || historyHasMore || hasRecords;
        historyPagination.style.display = shouldShow ? 'flex' : 'none';

        if (!shouldShow) {
            historyPageHint.textContent = '';
            loadMoreHistoryBtn.style.display = 'none';
            loadMoreHistoryBtn.disabled = false;
            loadMoreHistoryBtn.textContent = '加载更多';
            return;
        }

        if (historyLoading) {
            historyPageHint.textContent = hasRecords ? `已加载 ${historyRecords.length} 条，正在继续加载...` : '正在加载历史记录...';
            loadMoreHistoryBtn.style.display = hasRecords ? 'inline-flex' : 'none';
            loadMoreHistoryBtn.disabled = true;
            loadMoreHistoryBtn.textContent = '加载中...';
            return;
        }

        loadMoreHistoryBtn.disabled = false;
        loadMoreHistoryBtn.textContent = '加载更多';
        if (historyHasMore) {
            historyPageHint.textContent = `已加载 ${historyRecords.length} 条记录`;
            loadMoreHistoryBtn.style.display = 'inline-flex';
        } else if (historyInitialLoaded && hasRecords) {
            historyPageHint.textContent = `已显示全部 ${historyRecords.length} 条记录`;
            loadMoreHistoryBtn.style.display = 'none';
        } else {
            historyPageHint.textContent = '';
            loadMoreHistoryBtn.style.display = 'none';
        }
    }

    function captureHistoryScrollSnapshot() {
        return {
            top: historyList.scrollTop,
            height: historyList.scrollHeight
        };
    }

    function restoreHistoryScroll(mode, scrollSnapshot, forceScrollTop) {
        if (forceScrollTop) {
            historyList.scrollTop = 0;
            return;
        }

        if (!scrollSnapshot) {
            return;
        }

        const nextHeight = historyList.scrollHeight;
        if (mode === 'append' || mode === 'update') {
            historyList.scrollTop = scrollSnapshot.top;
            return;
        }

        if (mode === 'prepend') {
            const delta = nextHeight - scrollSnapshot.height;
            historyList.scrollTop = scrollSnapshot.top + Math.max(0, delta);
            return;
        }

        historyList.scrollTop = 0;
    }

    function isHistoryNearTop() {
        return historyList.scrollTop <= HISTORY_TOP_THRESHOLD;
    }

    function formatDateTime(timestamp) {
        if (!timestamp) return '';
        return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
    }

    function getStartOfWeek() {
        const now = new Date();
        const day = now.getDay() || 7;
        const start = new Date(now);
        start.setHours(0, 0, 0, 0);
        start.setDate(now.getDate() - day + 1);
        return start;
    }

    function getStartOfMonth() {
        const now = new Date();
        return new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0, 0);
    }

    function parseDateInput(dateValue) {
        const [year, month, day] = dateValue.split('-').map(Number);
        return new Date(year, month - 1, day, 0, 0, 0, 0);
    }

    function toRangeStart(dateValue) {
        const date = parseDateInput(dateValue);
        date.setHours(0, 0, 0, 0);
        return date.getTime();
    }

    function toRangeEnd(dateValue) {
        const date = parseDateInput(dateValue);
        date.setHours(23, 59, 59, 999);
        return date.getTime();
    }

    async function exportHistory(startAt, endAt, label) {
        try {
            const result = await invoke('export_message_history', {
                startAt,
                endAt,
                label
            });
            const savedPath = result?.saved_path || result?.savedPath;
            showEventBanner(savedPath ? `导出完成：${savedPath}` : '导出完成');
            if (savedPath) {
                alert(`导出完成：\n${savedPath}`);
            } else {
                alert(`导出完成：${result?.filename || '文件已生成'}`);
            }
        } catch (error) {
            console.error('❌ 导出历史记录失败:', error);
            showEventBanner('导出失败，请查看控制台日志', 'error');
            alert('导出失败，请查看控制台日志');
        }
    }

    clearHistoryBtn.addEventListener('click', async () => {
        try {
            await invoke('clear_message_history');
            historyRecords = [];
            historyHasMore = false;
            historyInitialLoaded = true;
            historyCursor = null;
            renderHistory({ mode: 'reset' });
            showEventBanner('历史记录已清空');
        } catch (error) {
            console.error('❌ 清空历史记录失败:', error);
            showEventBanner('清空历史记录失败', 'error');
        }
    });

    loadMoreHistoryBtn.addEventListener('click', async () => {
        if (!historyHasMore || historyLoading) return;
        await loadHistoryPage();
    });

    historyList.addEventListener('scroll', () => {
        if (!historyHasMore || historyLoading) return;

        const nearBottom =
            historyList.scrollTop + historyList.clientHeight >= historyList.scrollHeight - 120;
        if (nearBottom) {
            loadHistoryPage();
        }
    });

    exportWeekBtn.addEventListener('click', async () => {
        const start = getStartOfWeek();
        await exportHistory(start.getTime(), Date.now(), 'week');
    });

    exportMonthBtn.addEventListener('click', async () => {
        const start = getStartOfMonth();
        await exportHistory(start.getTime(), Date.now(), 'month');
    });

    generateWeeklyReportBtn.addEventListener('click', async () => {
        await generateAiReport('week');
    });

    generateMonthlyReportBtn.addEventListener('click', async () => {
        await generateAiReport('month');
    });

    exportAiReportWordBtn.addEventListener('click', async () => {
        await exportAiReportWord();
    });

    copyAiReportBtn.addEventListener('click', async () => {
        await copyAiReport();
    });

    copyAiReportInlineBtn.addEventListener('click', async () => {
        await copyAiReport();
    });

    exportRangeBtn.addEventListener('click', async () => {
        if (!exportStartDate.value || !exportEndDate.value) {
            alert('请选择开始和结束日期');
            return;
        }

        const startAt = toRangeStart(exportStartDate.value);
        const endAt = toRangeEnd(exportEndDate.value);
        if (startAt > endAt) {
            alert('开始日期不能晚于结束日期');
            return;
        }

        await exportHistory(startAt, endAt, `${exportStartDate.value}_to_${exportEndDate.value}`);
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
                    scheduleServerReconnect();
                } else if (!status.connected && serverStatus !== 'connecting') {
                    setServerDot(status.error ? 'error' : 'disconnected');
                    scheduleServerReconnect();
                }
            } catch (e) { /* ignore */ }
        }
    }, 5000);

    console.log('✅ 前端加载完成');
});
