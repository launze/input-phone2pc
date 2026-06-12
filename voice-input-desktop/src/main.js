// 寮曞叆 Tauri API
const invoke = window.__TAURI_INTERNALS__.invoke;

document.addEventListener('DOMContentLoaded', async () => {
    console.log('app loaded');

    // 鍏冪礌寮曠敤
    const serverModeToggle = document.getElementById('server-mode-toggle');
    const serverDot = document.getElementById('server-dot');
    const editUrlBtn = document.getElementById('edit-url-btn');
    const urlBar = document.getElementById('url-bar');
    const serverUrlInput = document.getElementById('server-url');

    const statusIndicator = document.getElementById('status-indicator');
    const statusText = document.getElementById('status-text');
    const deviceNameEl = document.getElementById('device-name');
    const unpairBtn = document.getElementById('unpair-btn');
    const appVersionEl = document.getElementById('app-version');
    const checkUpdateBtn = document.getElementById('check-update-btn');
    const installUpdateBtn = document.getElementById('install-update-btn');
    const updateText = document.getElementById('update-text');

    const pairingSection = document.getElementById('pairing-section');
    const historySection = document.getElementById('history-section');
    const qrArea = document.getElementById('qr-area');
    const qrCodeImg = document.getElementById('qr-code-img');
    const refreshQrBtn = document.getElementById('refresh-qr-btn');
    const historyList = document.getElementById('history-list');
    const historyEmpty = document.getElementById('history-empty');
    const historyPagination = document.getElementById('history-pagination');
    const historyPageHint = document.getElementById('history-page-hint');
    const loadMoreHistoryBtn = document.getElementById('load-more-history-btn');
    const exportWeekBtn = document.getElementById('export-week-btn');
    const exportMonthBtn = document.getElementById('export-month-btn');
    const exportQuarterBtn = document.getElementById('export-quarter-btn');
    const exportHalfYearBtn = document.getElementById('export-half-year-btn');
    const exportYearBtn = document.getElementById('export-year-btn');
    const exportStartDate = document.getElementById('export-start-date');
    const exportEndDate = document.getElementById('export-end-date');
    const exportRangeBtn = document.getElementById('export-range-btn');
    const clearHistoryBtn = document.getElementById('clear-history-btn');
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
    const quarterlyPromptTemplateInput = document.getElementById('quarterly-prompt-template');
    const halfYearPromptTemplateInput = document.getElementById('half-year-prompt-template');
    const yearlyPromptTemplateInput = document.getElementById('yearly-prompt-template');
    const generateWeeklyReportBtn = document.getElementById('generate-weekly-report-btn');
    const generateMonthlyReportBtn = document.getElementById('generate-monthly-report-btn');
    const generateQuarterlyReportBtn = document.getElementById('generate-quarterly-report-btn');
    const generateHalfYearReportBtn = document.getElementById('generate-half-year-report-btn');
    const generateYearlyReportBtn = document.getElementById('generate-yearly-report-btn');
    const aiReportStatus = document.getElementById('ai-report-status');
    const aiReportOutput = document.getElementById('ai-report-output');
    const copyAiReportBtn = document.getElementById('copy-ai-report-btn');
    const copyAiReportInlineBtn = document.getElementById('copy-ai-report-inline-btn');
    const exportAiReportWordBtn = document.getElementById('export-ai-report-word-btn');
    const desktopTextEditor = document.getElementById('desktop-text-editor');
    const desktopTextStatus = document.getElementById('desktop-text-status');
    const saveDesktopTextBtn = document.getElementById('save-desktop-text-btn');
    const insertDesktopTextBtn = document.getElementById('insert-desktop-text-btn');
    const copyDesktopTextBtn = document.getElementById('copy-desktop-text-btn');
    const clearDesktopTextBtn = document.getElementById('clear-desktop-text-btn');
    const cancelEditTextBtn = document.getElementById('cancel-edit-text-btn');

    let connectedDeviceId = null;
    let pairedDeviceId = null;      // 閰嶅璁惧 ID
    let pairedDeviceName = null;    // 閰嶅璁惧鍚嶇О
    let isPaired = false;        // 宸查厤瀵癸紙鏈夐厤瀵硅澶囪褰曪級
    let isDeviceOnline = false;  // 閰嶅璁惧鍦ㄧ嚎
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
    let editingHistoryRecordId = null;
    let currentUpdateInfo = null;
    let updateBusy = false;

    const REPORT_PERIODS = {
        week: {
            label: '周报',
            actionLabel: '生成周报',
            title: '工作周报',
            exportLabel: 'week',
            getRangeStart: () => getStartOfWeek()
        },
        month: {
            label: '月报',
            actionLabel: '生成月报',
            title: '工作月报',
            exportLabel: 'month',
            getRangeStart: () => getStartOfMonth()
        },
        quarter: {
            label: '季报',
            actionLabel: '生成季报',
            title: '工作季报',
            exportLabel: 'quarter',
            getRangeStart: () => getStartOfQuarter()
        },
        half_year: {
            label: '半年报',
            actionLabel: '生成半年报',
            title: '工作半年报',
            exportLabel: 'half-year',
            getRangeStart: () => getStartOfHalfYear()
        },
        year: {
            label: '年报',
            actionLabel: '生成年报',
            title: '工作年报',
            exportLabel: 'year',
            getRangeStart: () => getStartOfYear()
        }
    };

    const aiReportButtons = [
        generateWeeklyReportBtn,
        generateMonthlyReportBtn,
        generateQuarterlyReportBtn,
        generateHalfYearReportBtn,
        generateYearlyReportBtn
    ];

    // ===== 瑙嗗浘鍒囨崲 =====
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

    function normalizeOpenAiConfig(openai = {}) {
        return {
            api_key: (openai.api_key || openai.apiKey || '').trim(),
            api_url: (openai.api_url || openai.apiUrl || '').trim(),
            model_name: (openai.model_name || openai.modelName || '').trim(),
            weekly_prompt_template: openai.weekly_prompt_template || openai.weeklyPromptTemplate || '',
            monthly_prompt_template: openai.monthly_prompt_template || openai.monthlyPromptTemplate || '',
            quarterly_prompt_template: openai.quarterly_prompt_template || openai.quarterlyPromptTemplate || '',
            half_year_prompt_template: openai.half_year_prompt_template || openai.halfYearPromptTemplate || '',
            yearly_prompt_template: openai.yearly_prompt_template || openai.yearlyPromptTemplate || ''
        };
    }

    function hasConfiguredOpenAi(openai = {}) {
        const normalized = normalizeOpenAiConfig(openai);
        return Boolean(
            normalized.api_key &&
            normalized.model_name &&
            normalized.weekly_prompt_template.trim() &&
            normalized.monthly_prompt_template.trim() &&
            normalized.quarterly_prompt_template.trim() &&
            normalized.half_year_prompt_template.trim() &&
            normalized.yearly_prompt_template.trim()
        );
    }

    function syncAiPanelUi() {
        const panelOpen = aiReportPanel.style.display !== 'none';
        const settingsOpen = aiConfigCard.style.display !== 'none';
        toggleAiPanelBtn.classList.toggle('active', panelOpen);
        toggleAiSettingsBtn.classList.toggle('active', panelOpen && settingsOpen);
        aiReportPanel.classList.toggle('settings-hidden', !settingsOpen);
        aiSetupHint.textContent = aiConfigured
            ? '支持流式生成和 Markdown 预览。'
            : '请先完成 AI 设置。';
    }

    function setAiSettingsVisible(visible) {
        aiConfigCard.style.display = visible ? 'flex' : 'none';
        syncAiPanelUi();
    }

    function toggleAiPanel(forceOpen, { revealSettings = null } = {}) {
        const shouldOpen = typeof forceOpen === 'boolean'
            ? forceOpen
            : aiReportPanel.style.display === 'none';
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
        quarterlyPromptTemplateInput.value = normalized.quarterly_prompt_template;
        halfYearPromptTemplateInput.value = normalized.half_year_prompt_template;
        yearlyPromptTemplateInput.value = normalized.yearly_prompt_template;
    }

    function setUpdateUi(message = '', { busy = false, updateInfo = currentUpdateInfo } = {}) {
        updateBusy = busy;
        currentUpdateInfo = updateInfo;
        updateText.textContent = message;
        checkUpdateBtn.disabled = busy;
        const hasUpdate = Boolean(currentUpdateInfo?.has_update || currentUpdateInfo?.hasUpdate);
        installUpdateBtn.disabled = busy || !hasUpdate;
        installUpdateBtn.style.display = hasUpdate ? 'inline-flex' : 'none';
    }

    async function loadAppVersion() {
        try {
            const version = await invoke('get_app_version');
            appVersionEl.textContent = `v${version}`;
        } catch (error) {
            console.error('读取版本失败:', error);
            appVersionEl.textContent = 'v--';
        }
    }

    async function checkForUpdate() {
        if (updateBusy) return;
        setUpdateUi('正在检查更新...', { busy: true, updateInfo: currentUpdateInfo });
        try {
            const info = await invoke('check_app_update');
            currentUpdateInfo = info;
            const latestVersion = info?.latest_version || info?.latestVersion || '';
            if (info?.has_update || info?.hasUpdate) {
                setUpdateUi(`发现 v${latestVersion}`, { busy: false, updateInfo: info });
            } else {
                setUpdateUi('当前已是最新版本', { busy: false, updateInfo: null });
            }
        } catch (error) {
            console.error('检查更新失败:', error);
            setUpdateUi(`检查失败: ${error}`, { busy: false, updateInfo: null });
        }
    }

    async function downloadAndOpenUpdate() {
        if (updateBusy || !currentUpdateInfo) return;
        setUpdateUi('正在下载更新...', { busy: true, updateInfo: currentUpdateInfo });
        try {
            const savedPath = await invoke('download_and_open_app_update', { info: currentUpdateInfo });
            setUpdateUi(`已下载: ${savedPath}`, { busy: false, updateInfo: null });
            alert(`安装包已下载并打开：\n${savedPath}`);
        } catch (error) {
            console.error('下载更新失败:', error);
            setUpdateUi(`下载失败: ${error}`, { busy: false, updateInfo: currentUpdateInfo });
        }
    }

    function collectOpenAiConfigFromForm() {
        return {
            api_key: openaiApiKeyInput.value.trim(),
            api_url: openaiApiUrlInput.value.trim(),
            model_name: openaiModelNameInput.value.trim(),
            weekly_prompt_template: weeklyPromptTemplateInput.value,
            monthly_prompt_template: monthlyPromptTemplateInput.value,
            quarterly_prompt_template: quarterlyPromptTemplateInput.value,
            half_year_prompt_template: halfYearPromptTemplateInput.value,
            yearly_prompt_template: yearlyPromptTemplateInput.value
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

            const orderedMatch = line.match(/^(\d+)([.)銆侊級])\s+(.*)$/);
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
        aiReportButtons.forEach((button) => {
            button.disabled = busy;
        });
        updateAiReportActionState();
        if (busy) {
            aiReportButtons.forEach((button) => {
                button.textContent = busyText;
            });
        } else {
            generateWeeklyReportBtn.textContent = '生成周报';
            generateMonthlyReportBtn.textContent = '生成月报';
            generateQuarterlyReportBtn.textContent = '生成季报';
            generateHalfYearReportBtn.textContent = '生成半年报';
            generateYearlyReportBtn.textContent = '生成年报';
        }
    }

    async function saveOpenAiConfig({ silent = false } = {}) {
        const openai = collectOpenAiConfigFromForm();
        await invoke('save_openai_report_config', { openai });
        refreshAiConfigurationState(openai);
        if (!silent) {
            setAiReportStatus('AI 配置已保存。', 'success');
        }
    }

    function ensureAiConfigured(actionLabel) {
        if (refreshAiConfigurationState()) {
            return true;
        }

        toggleAiPanel(true, { revealSettings: true });
        setAiReportStatus(`请先完成 AI 设置，再${actionLabel}。`, 'error');
        alert(`请先完成 AI 设置，再${actionLabel}。`);
        return false;
    }

    async function generateAiReport(period) {
        if (aiReportBusy) return;

        const periodConfig = REPORT_PERIODS[period] || REPORT_PERIODS.month;
        const actionLabel = periodConfig.actionLabel;
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

            const startAt = periodConfig.getRangeStart().getTime();
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
                title: periodConfig.title
            };
            toggleAiPanel(true, { revealSettings: false });
            const total = report?.record_count ?? report?.recordCount ?? 0;
            const used = report?.used_record_count ?? report?.usedRecordCount ?? total;
            setAiReportStatus(
                used < total
                    ? `${actionLabel}完成，使用 ${used}/${total} 条记录。`
                    : `${actionLabel}完成，使用 ${total} 条记录。`,
                'success'
            );
        } catch (error) {
            console.error(`${actionLabel}失败:`, error);
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
            setAiReportStatus('当前没有可复制的报告内容。', 'error');
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
            setAiReportStatus('报告已复制到剪贴板。', 'success');
        } catch (error) {
            console.error('复制报告失败:', error);
            setAiReportStatus('复制失败，请手动选择文本复制。', 'error');
        }
    }

    async function copyPlainText(content) {
        if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(content);
            return;
        }

        const tempTextArea = document.createElement('textarea');
        tempTextArea.value = content;
        document.body.appendChild(tempTextArea);
        tempTextArea.select();
        document.execCommand('copy');
        document.body.removeChild(tempTextArea);
    }

    function setDesktopTextStatus(message, tone = '') {
        desktopTextStatus.textContent = message || '';
        desktopTextStatus.className = 'desktop-text-status';
        if (tone) {
            desktopTextStatus.classList.add(tone);
        }
    }

    function syncDesktopEditorUi() {
        const isEditing = Boolean(editingHistoryRecordId);
        saveDesktopTextBtn.textContent = isEditing ? '保存修改' : '新增记录';
        cancelEditTextBtn.style.display = isEditing ? 'inline-flex' : 'none';
    }

    function resetDesktopEditor({ focus = false } = {}) {
        editingHistoryRecordId = null;
        desktopTextEditor.value = '';
        setDesktopTextStatus('可在电脑端新增、复制、修改、删除文字记录。');
        syncDesktopEditorUi();
        if (focus) {
            desktopTextEditor.focus();
        }
    }

    function findHistoryRecordById(recordId) {
        return historyRecords.find((item) => item.id === recordId) || null;
    }

    function startEditingHistoryRecord(recordId) {
        const record = findHistoryRecordById(recordId);
        if (!record) return;

        editingHistoryRecordId = record.id;
        desktopTextEditor.value = record.content || '';
        setDesktopTextStatus(`正在编辑：${record.from_device_name || record.from_device_id || '未知来源'}`);
        syncDesktopEditorUi();
        desktopTextEditor.focus();
        desktopTextEditor.select();
    }

    async function saveDesktopTextRecord() {
        const content = desktopTextEditor.value.trim();
        if (!content) {
            setDesktopTextStatus('请先输入文字内容。', 'error');
            desktopTextEditor.focus();
            return;
        }

        try {
            let record;
            if (editingHistoryRecordId) {
                record = await invoke('update_message_history_record', {
                    id: editingHistoryRecordId,
                    content
                });
            } else {
                record = await invoke('create_desktop_text_record', { content });
            }

            if (record) {
                addOrUpdateHistoryRecord(record);
            }
            const successMessage = editingHistoryRecordId ? '文字已更新。' : '文字已添加到历史记录。';
            resetDesktopEditor();
            setDesktopTextStatus(successMessage, 'success');
        } catch (error) {
            console.error('保存桌面文字失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function insertDesktopTextToCursor() {
        const content = desktopTextEditor.value.trim();
        if (!content) {
            setDesktopTextStatus('请先输入文字内容。', 'error');
            desktopTextEditor.focus();
            return;
        }

        try {
            await invoke('insert_text_to_cursor', { content });
            setDesktopTextStatus('文字已插入到当前光标位置。', 'success');
        } catch (error) {
            console.error('插入文字失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '插入失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function copyDesktopEditorText() {
        const content = desktopTextEditor.value.trim();
        if (!content) {
            setDesktopTextStatus('当前没有可复制的文字。', 'error');
            return;
        }

        try {
            await copyPlainText(content);
            setDesktopTextStatus('文字已复制到剪贴板。', 'success');
        } catch (error) {
            console.error('复制桌面文字失败:', error);
            setDesktopTextStatus('复制失败，请稍后重试。', 'error');
        }
    }

    async function copyHistoryRecord(recordId) {
        const record = findHistoryRecordById(recordId);
        if (!record?.content) return;

        try {
            if (record.content_type === 'image') {
                await invoke('copy_message_history_image_record', { id: recordId });
                setDesktopTextStatus('图片已复制到剪贴板。', 'success');
            } else {
                await copyPlainText(record.content);
                setDesktopTextStatus('记录已复制到剪贴板。', 'success');
            }
        } catch (error) {
            console.error('复制历史记录失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '复制失败，请稍后重试。';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function deleteHistoryRecord(recordId) {
        const record = findHistoryRecordById(recordId);
        if (!record) return;

        const recordTypeLabel = record.content_type === 'image' ? '图片' : '文字';
        const shouldDelete = window.confirm(`确定删除这条${recordTypeLabel}记录吗？`);
        if (!shouldDelete) return;

        try {
            await invoke('delete_message_history_record', { id: recordId });
            historyRecords = historyRecords.filter((item) => item.id !== recordId);
            refreshHistoryCursor();
            renderHistory({ mode: 'update' });

            if (editingHistoryRecordId === recordId) {
                resetDesktopEditor();
            }

            setDesktopTextStatus('记录已删除。', 'success');
        } catch (error) {
            console.error('删除历史记录失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '删除失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function exportAiReportWord() {
        const content = currentAiReportContent.trim();
        if (!content) {
            setAiReportStatus('当前没有可导出的报告内容。', 'error');
            return;
        }
        if (!currentAiReport) {
            setAiReportStatus('请先生成报告，再导出 Word。', 'error');
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
            setAiReportStatus(savedPath ? `Word 已导出到：${savedPath}` : 'Word 已导出。', 'success');
            if (savedPath) {
                alert(`Word 导出完成：\n${savedPath}`);
            }
        } catch (error) {
            console.error('导出 Word 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出失败';
            setAiReportStatus(message, 'error');
            alert(`导出 Word 失败：${message}`);
        } finally {
            setAiReportBusy(false);
        }
    }

    // ===== 鏈嶅姟鍣ㄧ姸鎬?=====
    function setServerDot(status) {
        serverStatus = status;
        serverDot.className = 'server-dot';
        if (status === 'connected') serverDot.classList.add('connected');
        else if (status === 'connecting') serverDot.classList.add('connecting');
        else if (status === 'error') serverDot.classList.add('error');

        if (status === 'connected' && !isPaired) {
            loadQrCode();
        }
        if (status !== 'connected') {
            qrArea.style.display = 'none';
        }

        if (status === 'connected') {
            clearServerReconnectTimer();
        }
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

    // ===== 杩炴帴鏈嶅姟鍣?=====
    async function doConnectServer(url) {
        if (!url || serverConnectInFlight) return;
        try {
            serverConnectInFlight = true;
            clearServerReconnectTimer();
            setServerDot('connecting');
            await invoke('connect_server', { url });
            console.log('connect request sent');
            setServerDot('connected');
        } catch (error) {
            console.error('连接服务器失败:', error);
            setServerDot('error');
            scheduleServerReconnect();
        } finally {
            serverConnectInFlight = false;
        }
    }

    // ===== 璁惧杩炴帴/涓婄嚎 =====
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

    // ===== 1. 鍏堟敞鍐?Tauri 浜嬩欢鐩戝惉锛堝繀椤诲湪杩炴帴鏈嶅姟鍣ㄤ箣鍓嶏級 =====
    // 浣跨敤 __TAURI_INTERNALS__ 鑾峰彇 event API锛堟瘮 window.__TAURI__ 鏇村彲闈狅級
    const tauriEvent = window.__TAURI__?.event || window.__TAURI_INTERNALS__?.event;
    if (tauriEvent && tauriEvent.listen) {
        tauriEvent.listen('device_connected', (event) => {
            console.log('设备连接事件:', JSON.stringify(event));
            const name = event.payload?.device_name || 'Android 设备';
            const devId = event.payload?.device_id || null;
            onDeviceConnected(name, devId);
        });

        tauriEvent.listen('device_disconnected', (event) => {
            console.log('设备断开事件:', JSON.stringify(event));
            onDeviceDisconnected();
        });

        tauriEvent.listen('text_received', (event) => {
            console.log('收到文字事件:', JSON.stringify(event));
            const text = event.payload?.text || '';
            const deliveryMode = event.payload?.delivery_mode || 'live';
            if (text && deliveryMode !== 'offline_sync' && !isDeviceOnline) {
                onDeviceConnected(deviceNameEl.textContent || 'Android 设备', connectedDeviceId);
            }
        });

        tauriEvent.listen('history_recorded', (event) => {
            console.log('历史记录已持久化:', JSON.stringify(event));
            if (event.payload) {
                addOrUpdateHistoryRecord(event.payload);
            }
        });

        tauriEvent.listen('relay_stored', (event) => {
            console.log('服务器已暂存消息:', JSON.stringify(event));
        });

        tauriEvent.listen('openai_report_delta', (event) => {
            const payload = event.payload || {};
            if (!payload.request_id || payload.request_id !== currentAiRequestId) {
                return;
            }
            appendAiReportDelta(payload.delta || '');
        });

        // 鐩戝惉閰嶅鎴愬姛浜嬩欢
        tauriEvent.listen('device_paired', (event) => {
            console.log('配对成功事件:', JSON.stringify(event));
            const data = event.payload;
            pairedDeviceId = data.device_id;
            pairedDeviceName = data.device_name;
            deviceNameEl.textContent = '已连接：' + data.device_name;
            deviceNameEl.style.display = 'block';
            unpairBtn.style.display = 'block';
            switchToHistoryView();
        });

        // 鐩戝惉閰嶅澶辫触浜嬩欢
        tauriEvent.listen('pair_failed', (event) => {
            console.log('配对失败事件:', JSON.stringify(event));
            const data = event.payload;
            alert('配对失败: ' + (data.message || '未知错误'));
        });

        console.log('event listeners registered');
    } else {
        console.error('无法获取 Tauri event API! window.__TAURI__:', typeof window.__TAURI__, 'window.__TAURI_INTERNALS__:', typeof window.__TAURI_INTERNALS__);
    }

    // ===== 2. 鍔犺浇閰嶇疆 =====
    try {
        await loadAppVersion();
        await loadHistory();
        const config = await invoke('get_config');
        console.log('配置已加载:', config);
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

        if (config.server_mode_enabled) {
            doConnectServer(config.server_url);
        }
    } catch (error) {
        console.error('加载配置失败:', error);
    }

    // ===== 鏈嶅姟鍣ㄦā寮忓紑鍏?=====
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
                qrArea.style.display = 'none';
            }
        } catch (error) {
            console.error('设置服务器模式失败:', error);
            e.target.checked = !enabled;
        }
    });

    // ===== 缂栬緫鏈嶅姟鍣ㄥ湴鍧€ =====
    editUrlBtn.addEventListener('click', () => {
        const showing = urlBar.style.display !== 'none';
        urlBar.style.display = showing ? 'none' : 'flex';
        if (!showing) serverUrlInput.focus();
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
            console.error('保存 AI 配置失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存失败';
            setAiReportStatus(message, 'error');
            alert(`保存 AI 配置失败：${message}`);
        }
    });

    aiReportOutput.addEventListener('scroll', () => {
        if (aiReportIgnoreScrollEvent) return;
        aiReportShouldAutoScroll = isAiReportNearBottom();
    });

    [
        openaiApiKeyInput,
        openaiApiUrlInput,
        openaiModelNameInput,
        weeklyPromptTemplateInput,
        monthlyPromptTemplateInput,
        quarterlyPromptTemplateInput,
        halfYearPromptTemplateInput,
        yearlyPromptTemplateInput
    ]
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
                console.error('连接失败:', error);
            }
        }
    });

    // ===== 浜岀淮鐮?=====
    async function loadQrCode() {
        try {
            const dataUrl = await invoke('generate_pairing_qr');
            qrCodeImg.src = dataUrl;
            qrArea.style.display = 'flex';
        } catch (e) {
            console.error('生成二维码失败:', e);
        }
    }
    refreshQrBtn.addEventListener('click', loadQrCode);

    checkUpdateBtn.addEventListener('click', async () => {
        await checkForUpdate();
    });

    installUpdateBtn.addEventListener('click', async () => {
        await downloadAndOpenUpdate();
    });

    // ===== 鍙栨秷閰嶅 =====
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
            console.error('取消配对失败:', e);
        }
    });

    // ===== 鍘嗗彶璁板綍 =====
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
            console.error('加载历史记录失败:', error);
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
            historyList.innerHTML = '<div class="history-empty" id="history-empty">等待手机发送文字，或在上方直接新增...</div>';
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


        const deleteButton = `<button class="history-action-btn danger" data-action="delete" data-record-id="${escapeHtml(record.id)}">删除</button>`;

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

    function getStartOfQuarter() {
        const now = new Date();
        const quarterStartMonth = Math.floor(now.getMonth() / 3) * 3;
        return new Date(now.getFullYear(), quarterStartMonth, 1, 0, 0, 0, 0);
    }

    function getStartOfHalfYear() {
        const now = new Date();
        const halfStartMonth = now.getMonth() < 6 ? 0 : 6;
        return new Date(now.getFullYear(), halfStartMonth, 1, 0, 0, 0, 0);
    }

    function getStartOfYear() {
        const now = new Date();
        return new Date(now.getFullYear(), 0, 1, 0, 0, 0, 0);
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
            if (savedPath) {
                alert(`导出完成：\n${savedPath}`);
            } else {
                alert(`导出完成：${result?.filename || '文件已生成'}`);
            }
        } catch (error) {
            console.error('导出历史记录失败:', error);
            alert('导出失败，请查看控制台日志');
        }
    }

    function renderHistory({ mode = 'reset', scrollSnapshot = captureHistoryScrollSnapshot(), forceScrollTop = false } = {}) {
        if (!historyRecords.length) {
            historyList.innerHTML = '<div class="history-empty" id="history-empty">等待手机发送文字，或在上方直接新增...</div>';
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
        const manualBadge = record.delivery_mode === 'manual'
            ? '<span class="history-badge">电脑新增</span>'
            : '';
        const itemClass = record.delivery_mode === 'offline_sync'
            ? 'history-item offline-sync'
            : 'history-item';
        const editButton = record.content_type === 'text'
            ? `<button class="history-action-btn" data-action="edit" data-record-id="${escapeHtml(record.id)}">编辑</button>`
            : '';
        const copyButtonLabel = record.content_type === 'image' ? '复制图片' : '复制';
        const deleteButton = `<button class="history-action-btn danger" data-action="delete" data-record-id="${escapeHtml(record.id)}">删除</button>`;

        return `
            <div class="${itemClass}" data-record-id="${escapeHtml(record.id)}">
                <div class="history-item-header">
                    <div class="history-text">${escapeHtml(record.content)}</div>
                    <div class="history-item-actions">
                        <button class="history-action-btn" data-action="copy" data-record-id="${escapeHtml(record.id)}">${copyButtonLabel}</button>
                        ${editButton}
                        ${deleteButton}
                    </div>
                </div>
                <div class="history-time">
                    <span>发送: ${escapeHtml(sentAt)}</span>
                    <span>接收: ${escapeHtml(receivedAt)}</span>
                </div>
                <div class="history-meta">
                    <span>来源: ${escapeHtml(record.from_device_name || record.from_device_id || '未知设备')}</span>
                    <span>通道: ${escapeHtml(record.via)}</span>
                    ${offlineBadge}
                    ${manualBadge}
                </div>
            </div>
        `;
    }

    saveDesktopTextBtn.addEventListener('click', async () => {
        await saveDesktopTextRecord();
    });

    insertDesktopTextBtn.addEventListener('click', async () => {
        await insertDesktopTextToCursor();
    });

    copyDesktopTextBtn.addEventListener('click', async () => {
        await copyDesktopEditorText();
    });

    clearDesktopTextBtn.addEventListener('click', () => {
        resetDesktopEditor({ focus: true });
    });

    cancelEditTextBtn.addEventListener('click', () => {
        resetDesktopEditor({ focus: true });
    });

    desktopTextEditor.addEventListener('keydown', async (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
            event.preventDefault();
            await saveDesktopTextRecord();
        }
    });

    historyList.addEventListener('click', async (event) => {
        const actionButton = event.target.closest('[data-action][data-record-id]');
        if (!actionButton) return;

        const { action, recordId } = actionButton.dataset;
        if (!recordId) return;

        if (action === 'copy') {
            await copyHistoryRecord(recordId);
            return;
        }

        if (action === 'edit') {
            startEditingHistoryRecord(recordId);
            return;
        }

        if (action === 'delete') {
            await deleteHistoryRecord(recordId);
        }
    });

    syncDesktopEditorUi();
    setDesktopTextStatus('可在电脑端新增、复制、修改、删除文字记录。');

    clearHistoryBtn.addEventListener('click', async () => {
        try {
            await invoke('clear_message_history');
            historyRecords = [];
            historyHasMore = false;
            historyInitialLoaded = true;
            historyCursor = null;
            renderHistory({ mode: 'reset' });
        } catch (error) {
            console.error('清空历史记录失败:', error);
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

    exportQuarterBtn.addEventListener('click', async () => {
        const start = getStartOfQuarter();
        await exportHistory(start.getTime(), Date.now(), 'quarter');
    });

    exportHalfYearBtn.addEventListener('click', async () => {
        const start = getStartOfHalfYear();
        await exportHistory(start.getTime(), Date.now(), 'half-year');
    });

    exportYearBtn.addEventListener('click', async () => {
        const start = getStartOfYear();
        await exportHistory(start.getTime(), Date.now(), 'year');
    });

    generateWeeklyReportBtn.addEventListener('click', async () => {
        await generateAiReport('week');
    });

    generateMonthlyReportBtn.addEventListener('click', async () => {
        await generateAiReport('month');
    });

    generateQuarterlyReportBtn.addEventListener('click', async () => {
        await generateAiReport('quarter');
    });

    generateHalfYearReportBtn.addEventListener('click', async () => {
        await generateAiReport('half_year');
    });

    generateYearlyReportBtn.addEventListener('click', async () => {
        await generateAiReport('year');
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

    // ===== 瀹氭湡妫€鏌ユ湇鍔″櫒鐘舵€?=====
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

    console.log('前端加载完成');
});
