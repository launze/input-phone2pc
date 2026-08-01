// 寮曞叆 Tauri API
const invoke = window.__TAURI_INTERNALS__.invoke;

function debugC2Main(message) {
    const text = `${new Date().toISOString()} ${message}`;
    console.log('[c2-reader:main]', text);
    try {
        Promise.resolve(invoke('debug_c2_reader_log', { message: `[main] ${text}` })).catch(() => {});
    } catch (_) {}
}

import {
    escapeHtml,
    formatDateTime,
    markdownToHtml,
    parseJsonSafe
} from './ui-state.js';
import {
    buildAiAssistantFilters as buildAiAssistantFiltersModel,
    buildAiAssistantScopeText as buildAiAssistantScopeTextModel,
    buildAiSessionScopeLabel,
    buildClearHistoryDialog,
    compareHistoryRecord,
    currentHistoryContentTypeFilter as currentHistoryContentTypeFilterModel,
    currentHistoryDeliveryStatusFilter as currentHistoryDeliveryStatusFilterModel,
    currentHistoryFavoriteFilter as currentHistoryFavoriteFilterModel,
    currentHistoryPinnedFilter as currentHistoryPinnedFilterModel,
    filterHistoryRecords,
    getHistoryTypeLabel,
    normalizedHistoryFilterValue,
    parseRecordTags,
    recordDeviceKey,
    recordSourceAppKey,
    renderHistoryItem,
    renderNotificationTimeline,
    workspaceContentTypeScope,
    workspaceContentTypes
} from './history.js';
import {
    getStartOfHalfYear,
    getStartOfMonth,
    getStartOfQuarter,
    getStartOfWeek,
    getStartOfYear,
    toRangeEnd,
    toRangeStart
} from './reports.js';
import {
    applyOpenAiProviderPreset,
    hasConfiguredOpenAi,
    inputModeLabel,
    normalizeInputMode,
    normalizeOpenAiConfig
} from './connection.js';
import { createMenuVisibilityController } from './settings.js';
import {
    aiAssistantPlaceholder,
    applyAiToolEvent,
    collectAiReferences as collectAiReferencesModel,
    renderAiExportedFileItems,
    renderAiMessageItem,
    renderAiReferenceItems,
    renderAiToolCallItem
} from './ai-assistant.js';

function initializeC2ReaderWindow() {
    document.body.className = 'c2-reader-window-body';
    document.body.innerHTML = `
        <main class="c2-mini-panel">
            <header class="c2-mini-head">
                <div>
                    <div class="c2-mini-title">二维码读取</div>
                    <div id="c2-mini-summary" class="c2-mini-summary">可点击开始截图读取</div>
                </div>
                <button id="c2-mini-close-btn" class="c2-mini-close" type="button" title="退出">×</button>
            </header>
            <section class="c2-mini-stats">
                <div class="c2-mini-progress" id="c2-mini-progress-ring"><span id="c2-mini-progress">0%</span></div>
                <div class="c2-mini-lines">
                    <div><span>状态</span><strong id="c2-mini-state">待机</strong></div>
                    <div><span>帧数</span><strong id="c2-mini-frames">0</strong></div>
                    <div><span>完成</span><strong id="c2-mini-files">0</strong></div>
                </div>
            </section>
            <section class="c2-mini-actions">
                <button id="c2-mini-start-btn" class="btn-small btn-primary" type="button">开始截图</button>
                <button id="c2-mini-stop-btn" class="btn-small" type="button">停止</button>
            </section>
            <div id="c2-mini-message" class="c2-mini-message"></div>
        </main>
    `;

    const summaryEl = document.getElementById('c2-mini-summary');
    const stateEl = document.getElementById('c2-mini-state');
    const framesEl = document.getElementById('c2-mini-frames');
    const filesEl = document.getElementById('c2-mini-files');
    const progressEl = document.getElementById('c2-mini-progress');
    const progressRing = document.getElementById('c2-mini-progress-ring');
    const messageEl = document.getElementById('c2-mini-message');
    const startBtn = document.getElementById('c2-mini-start-btn');
    const stopBtn = document.getElementById('c2-mini-stop-btn');
    const closeBtn = document.getElementById('c2-mini-close-btn');
    let timer = null;
    let lastStatus = null;

    function setMessage(message = '', tone = '') {
        messageEl.textContent = message;
        messageEl.className = 'c2-mini-message';
        if (tone) messageEl.classList.add(tone);
    }

    function renderStatus(status = {}) {
        lastStatus = status;
        const available = status.available !== false;
        const running = Boolean(status.running);
        const progress = Math.max(0, Math.min(100, Number(status.progress || 0)));
        const filesDecoded = status.files_decoded || status.filesDecoded || 0;
        const lastSaved = status.last_saved || status.lastSaved || '';

        progressRing.style.setProperty('--progress', `${progress}%`);
        progressEl.textContent = `${progress}%`;
        stateEl.textContent = !available ? '不可用' : running ? '截图中' : '待机';
        summaryEl.textContent = !available
            ? '内置模块未就绪'
            : running
                ? '正在读取桌面 C2 动态二维码'
                : '可点击开始截图读取';
        framesEl.textContent = String(status.frames || 0);
        filesEl.textContent = String(filesDecoded);
        startBtn.disabled = !available || running;
        stopBtn.disabled = !available || !running;

        if (status.error) {
            setMessage(status.error, 'error');
        } else if (lastSaved) {
            setMessage(`已保存：${lastSaved}`, 'success');
        } else if (running) {
            setMessage('保持二维码完整显示在屏幕上。');
        } else if (available) {
            setMessage('');
        }
    }

    async function refreshStatus() {
        try {
            renderStatus(await invoke('get_c2_screen_reader_status'));
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '读取状态失败';
            setMessage(message, 'error');
        }
    }

    function startPolling() {
        window.clearInterval(timer);
        timer = window.setInterval(refreshStatus, 800);
    }

    startBtn.addEventListener('click', async () => {
        startBtn.disabled = true;
        setMessage('正在启动截图读取...');
        try {
            renderStatus(await invoke('start_c2_screen_reader'));
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '启动失败';
            setMessage(message, 'error');
            await refreshStatus();
        }
    });

    stopBtn.addEventListener('click', async () => {
        stopBtn.disabled = true;
        try {
            renderStatus(await invoke('stop_c2_screen_reader'));
            setMessage('已停止截图读取。');
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '停止失败';
            setMessage(message, 'error');
            await refreshStatus();
        }
    });

    closeBtn.addEventListener('click', async () => {
        try {
            if (lastStatus?.running) {
                await invoke('stop_c2_screen_reader');
            }
        } finally {
            window.close();
        }
    });

    window.addEventListener('beforeunload', () => {
        window.clearInterval(timer);
    });

    refreshStatus();
    startPolling();
}

document.addEventListener('DOMContentLoaded', async () => {
    console.log('app loaded');
    debugC2Main(`DOMContentLoaded url=${window.location.href}`);
    const isC2ReaderWindow = new URLSearchParams(window.location.search).get('reader') === '1';
    if (isC2ReaderWindow) {
        debugC2Main('legacy reader query window detected');
        initializeC2ReaderWindow();
        return;
    }

    // 鍏冪礌寮曠敤
    const serverModeToggle = document.getElementById('server-mode-toggle');
    const serverDot = document.getElementById('server-dot');
    const editUrlBtn = document.getElementById('edit-url-btn');
    const openC2ReaderBtn = document.getElementById('open-c2-reader-btn');
    debugC2Main(`open-c2-reader button found=${Boolean(openC2ReaderBtn)}`);
    const toolbarSettingsBtn = document.getElementById('toolbar-settings-btn');
    const toolbarSettingsMenu = document.getElementById('toolbar-settings-menu');
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
    const qrPairingModeLabel = document.getElementById('qr-pairing-mode-label');
    const toggleQrModeBtn = document.getElementById('toggle-qr-mode-btn');
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
    const historyExportMenuBtn = document.getElementById('history-export-menu-btn');
    const historyExportMenu = document.getElementById('history-export-menu');
    const historyMoreMenuBtn = document.getElementById('history-more-menu-btn');
    const historyMoreMenu = document.getElementById('history-more-menu');
    const clearHistoryBtn = document.getElementById('clear-history-btn');
    const historySearchInput = document.getElementById('history-search-input');
    const historyTypeFilter = document.getElementById('history-type-filter');
    const historyChannelFilter = document.getElementById('history-channel-filter');
    const historyDeviceFilter = document.getElementById('history-device-filter');
    const historyStatusFilter = document.getElementById('history-status-filter');
    const historySourceAppFilter = document.getElementById('history-source-app-filter');
    const historyTagFilter = document.getElementById('history-tag-filter');
    const historyCategoryFilter = document.getElementById('history-category-filter');
    const exportFilteredHistoryBtn = document.getElementById('export-filtered-history-btn');
    const exportFilteredHistoryTxtBtn = document.getElementById('export-filtered-history-txt-btn');
    const exportFilteredHistoryMdBtn = document.getElementById('export-filtered-history-md-btn');
    const exportFilteredHistoryWordBtn = document.getElementById('export-filtered-history-word-btn');
    const toggleHistorySelectionBtn = document.getElementById('toggle-history-selection-btn');
    const favoriteSelectedHistoryBtn = document.getElementById('favorite-selected-history-btn');
    const unfavoriteSelectedHistoryBtn = document.getElementById('unfavorite-selected-history-btn');
    const pinSelectedHistoryBtn = document.getElementById('pin-selected-history-btn');
    const unpinSelectedHistoryBtn = document.getElementById('unpin-selected-history-btn');
    const exportSelectedHistoryCsvBtn = document.getElementById('export-selected-history-csv-btn');
    const exportSelectedHistoryTxtBtn = document.getElementById('export-selected-history-txt-btn');
    const exportSelectedHistoryMdBtn = document.getElementById('export-selected-history-md-btn');
    const exportSelectedHistoryBtn = document.getElementById('export-selected-history-btn');
    const deleteSelectedHistoryBtn = document.getElementById('delete-selected-history-btn');
    const resetHistoryFiltersBtn = document.getElementById('reset-history-filters-btn');
    const historyFilterSummary = document.getElementById('history-filter-summary');
    const workspaceTabs = Array.from(document.querySelectorAll('[data-workspace-tab]'));
    const historyOnlyPanels = Array.from(document.querySelectorAll('[data-history-only]'));
    const historyToolPanels = Array.from(document.querySelectorAll('[data-history-tool]'));
    const toggleAiPanelBtn = document.getElementById('toggle-ai-panel-btn');
    const toggleAiSettingsBtn = document.getElementById('toggle-ai-settings-btn');
    const aiReportPanel = document.getElementById('ai-report-panel');
    const aiConfigCard = document.getElementById('ai-config-card');
    const aiSetupHint = document.getElementById('ai-setup-hint');
    const saveOpenAiConfigBtn = document.getElementById('save-openai-config-btn');
    const openaiProviderSelect = document.getElementById('openai-provider');
    const openaiApiKeyInput = document.getElementById('openai-api-key');
    const openaiApiUrlInput = document.getElementById('openai-api-url');
    const openaiModelNameInput = document.getElementById('openai-model-name');
    const aiReportStatus = document.getElementById('ai-report-status');
    const aiReportOutput = document.getElementById('ai-report-output');
    const refreshAiSkillsBtn = document.getElementById('refresh-ai-skills-btn');
    const aiSkillsList = document.getElementById('ai-skills-list');
    const aiSessionList = document.getElementById('ai-session-list');
    const aiChatMessages = document.getElementById('ai-chat-messages');
    const aiToolCallList = document.getElementById('ai-tool-call-list');
    const aiSuggestionList = document.getElementById('ai-suggestion-list');
    const aiChatInput = document.getElementById('ai-chat-input');
    const createAiSessionBtn = document.getElementById('create-ai-session-btn');
    const stopAiSessionBtn = document.getElementById('stop-ai-session-btn');
    const newAiSessionBtn = document.getElementById('new-ai-session-btn');
    const renameAiSessionBtn = document.getElementById('rename-ai-session-btn');
    const deleteAiSessionBtn = document.getElementById('delete-ai-session-btn');
    const exportAiSessionWordBtn = document.getElementById('export-ai-session-word-btn');
    const aiReferenceList = document.getElementById('ai-reference-list');
    const aiExportedFileList = document.getElementById('ai-exported-file-list');
    const aiSideTabs = Array.from(document.querySelectorAll('[data-ai-side-tab]'));
    const aiSidePanels = Array.from(document.querySelectorAll('[data-ai-side-panel]'));
    const aiSkillNameInput = document.getElementById('ai-skill-name');
    const aiSkillDescriptionInput = document.getElementById('ai-skill-description');
    const aiSkillInputSchemaInput = document.getElementById('ai-skill-input-schema');
    const aiSkillTemplateInput = document.getElementById('ai-skill-template');
    const aiSkillPeriodSelect = document.getElementById('ai-skill-period');
    const aiSkillDefaultFiltersInput = document.getElementById('ai-skill-default-filters');
    const aiSkillOutputFormatInput = document.getElementById('ai-skill-output-format');
    const aiSkillEnabledInput = document.getElementById('ai-skill-enabled');
    const saveAiSkillBtn = document.getElementById('save-ai-skill-btn');
    const exportAiSkillsBtn = document.getElementById('export-ai-skills-btn');
    const importAiSkillsBtn = document.getElementById('import-ai-skills-btn');
    const aiSkillImportBox = document.getElementById('ai-skill-import-box');
    const aiSkillImportText = document.getElementById('ai-skill-import-text');
    const confirmImportAiSkillsBtn = document.getElementById('confirm-import-ai-skills-btn');
    const cancelImportAiSkillsBtn = document.getElementById('cancel-import-ai-skills-btn');
    const aiSessionStatus = document.getElementById('ai-session-status');
    const aiErrorDetails = document.getElementById('ai-error-details');
    const aiErrorContent = document.getElementById('ai-error-content');
    const copyAiErrorBtn = document.getElementById('copy-ai-error-btn');
    const copyAiReportBtn = document.getElementById('copy-ai-report-btn');
    const copyAiReportInlineBtn = document.getElementById('copy-ai-report-inline-btn');
    const desktopTextEditor = document.getElementById('desktop-text-editor');
    const desktopTextStatus = document.getElementById('desktop-text-status');
    const saveDesktopTextBtn = document.getElementById('save-desktop-text-btn');
    const insertDesktopTextBtn = document.getElementById('insert-desktop-text-btn');
    const copyDesktopTextBtn = document.getElementById('copy-desktop-text-btn');
    const clearDesktopTextBtn = document.getElementById('clear-desktop-text-btn');
    const cancelEditTextBtn = document.getElementById('cancel-edit-text-btn');
    const desktopCategorySelect = document.getElementById('desktop-category-select');
    const addDesktopCategoryBtn = document.getElementById('add-desktop-category-btn');
    const renameDesktopCategoryBtn = document.getElementById('rename-desktop-category-btn');
    const deleteDesktopCategoryBtn = document.getElementById('delete-desktop-category-btn');
    const inputModeButtons = Array.from(document.querySelectorAll('[data-input-mode]'));
    const lastInputStatus = document.getElementById('last-input-status');
    const toastContainer = document.getElementById('toast-container');
    const confirmModal = document.getElementById('confirm-modal');
    const confirmModalTitle = document.getElementById('confirm-modal-title');
    const confirmModalMessage = document.getElementById('confirm-modal-message');
    const confirmCancelBtn = document.getElementById('confirm-cancel-btn');
    const confirmOkBtn = document.getElementById('confirm-ok-btn');

    let connectedDeviceId = null;
    let pairedDeviceId = null;      // 閰嶅璁惧 ID
    let pairedDeviceName = null;    // 閰嶅璁惧鍚嶇О
    let isPaired = false;        // 宸查厤瀵癸紙鏈夐厤瀵硅澶囪褰曪級
    let isDeviceOnline = false;  // 閰嶅璁惧鍦ㄧ嚎
    let serverStatus = 'disconnected';
    let serverReconnectTimer = null;
    let serverConnectInFlight = false;
    let qrPairingMode = 'relay';
    const HISTORY_PAGE_SIZE = 100;
    const HISTORY_TOP_THRESHOLD = 24;
    let historyRecords = [];
    let historyHasMore = false;
    let historyLoading = false;
    let historyInitialLoaded = false;
    let historyCursor = null;
    let aiReportBusy = false;
    let aiConfigured = false;
    let currentAiReportContent = '';
    let currentAssistantRequestId = null;
    let stoppedAssistantRequestIds = new Set();
    const remoteAiTargets = new Map();
    let aiReportShouldAutoScroll = true;
    let aiReportIgnoreScrollEvent = false;
    let aiChatShouldAutoScroll = true;
    let aiChatIgnoreScrollEvent = false;
    const AI_REPORT_SCROLL_THRESHOLD = 24;
    let editingHistoryRecordId = null;
    let currentUpdateInfo = null;
    let updateBusy = false;
    let aiSkills = [];
    let aiSessions = [];
    let aiMessages = [];
    let aiToolCalls = [];
    let aiExportedFiles = [];
    let editingAiMessageId = null;
    let selectedAiSkillId = null;
    let currentAiSessionId = null;
    let activeWorkspaceTab = 'history';
    let inputMode = 'direct';
    const DEFAULT_HISTORY_CATEGORY = '语音输入';
    let historyCategories = [DEFAULT_HISTORY_CATEGORY];
    let selectedHistoryCategory = DEFAULT_HISTORY_CATEGORY;
    let historyFilters = {
        query: '',
        type: 'all',
        channel: 'all',
        device: 'all',
        status: 'all',
        sourceApp: 'all',
        tag: '',
        category: DEFAULT_HISTORY_CATEGORY
    };
    let historySelectionMode = false;
    const selectedHistoryIds = new Set();
    let confirmResolver = null;
    const toolbarSettingsMenuController = createMenuVisibilityController(
        toolbarSettingsMenu,
        toolbarSettingsBtn,
        'block'
    );
    const historyExportMenuController = createMenuVisibilityController(
        historyExportMenu,
        historyExportMenuBtn,
        'grid'
    );
    const historyMoreMenuController = createMenuVisibilityController(
        historyMoreMenu,
        historyMoreMenuBtn,
        'flex'
    );

    // ===== 瑙嗗浘鍒囨崲 =====
    function switchToHistoryView() {
        pairingSection.style.display = 'none';
        historySection.style.display = 'flex';
    }

    function switchToPairingView() {
        pairingSection.style.display = 'flex';
        historySection.style.display = 'none';
        updateQrModeUi();
        if (qrPairingMode === 'lan' || serverStatus === 'connected') {
            loadQrCode();
        }
    }

    function updateQrModeUi() {
        if (!qrPairingModeLabel || !toggleQrModeBtn) return;
        const relayMode = qrPairingMode === 'relay';
        qrPairingModeLabel.textContent = relayMode ? '服务器中转扫码' : '局域网扫码配置';
        toggleQrModeBtn.textContent = relayMode ? '切换为局域网扫码配置' : '切换为服务器中转扫码';
    }

    function syncAiPanelUi() {
        const panelOpen = aiReportPanel.style.display !== 'none';
        const settingsOpen = aiConfigCard.style.display !== 'none';
        toggleAiPanelBtn?.classList.toggle('active', panelOpen);
        toggleAiSettingsBtn?.classList.toggle('active', panelOpen && settingsOpen);
        aiReportPanel.classList.toggle('settings-hidden', !settingsOpen);
        if (aiSetupHint) aiSetupHint.textContent = '';
    }

    function setAiSettingsVisible(visible) {
        aiConfigCard.style.display = visible ? 'flex' : 'none';
        syncAiPanelUi();
    }

    function toggleAiPanel(forceOpen, { revealSettings = null } = {}) {
        const shouldOpen = typeof forceOpen === 'boolean'
            ? forceOpen
            : aiReportPanel.style.display === 'none';
        aiReportPanel.style.display = shouldOpen ? 'flex' : 'none';
        if (!shouldOpen) {
            setAiSettingsVisible(false);
            syncAiPanelUi();
            return;
        }

        const nextRevealSettings = revealSettings ?? !aiConfigured;
        setAiSettingsVisible(nextRevealSettings);
        syncAiPanelUi();
    }

    function syncHistoryTypeFilterOptions() {
        if (!historyTypeFilter) return;
        const options = activeWorkspaceTab === 'notifications'
            ? [['notification', '通知']]
            : [
                ['all', '全部类型'],
                ['text', '文本'],
                ['image', '图片'],
                ['file', '文件']
            ];
        historyTypeFilter.innerHTML = options
            .map(([value, label]) => `<option value="${value}">${label}</option>`)
            .join('');
        historyTypeFilter.disabled = activeWorkspaceTab === 'notifications';
        historyTypeFilter.value = activeWorkspaceTab === 'notifications' ? 'notification' : 'all';
    }

    function switchWorkspaceTab(tab) {
        activeWorkspaceTab = ['history', 'assistant', 'notifications'].includes(tab) ? tab : 'history';
        workspaceTabs.forEach(button => {
            button.classList.toggle('active', button.dataset.workspaceTab === activeWorkspaceTab);
        });

        const showAssistant = activeWorkspaceTab === 'assistant';
        const showHistoryTools = activeWorkspaceTab === 'history';
        aiReportPanel.style.display = showAssistant ? 'flex' : 'none';
        historyOnlyPanels.forEach(panel => {
            panel.style.display = showAssistant ? 'none' : '';
        });
        historyToolPanels.forEach(panel => {
            panel.style.display = showHistoryTools ? '' : 'none';
        });

        syncHistoryTypeFilterOptions();
        if (historySourceAppFilter) {
            historySourceAppFilter.disabled = activeWorkspaceTab !== 'notifications';
            if (activeWorkspaceTab !== 'notifications') {
                historySourceAppFilter.value = 'all';
            }
        }
        if (historyCategoryFilter) {
            historyCategoryFilter.disabled = activeWorkspaceTab === 'notifications';
            if (activeWorkspaceTab === 'notifications') {
                historyCategoryFilter.value = 'all';
            } else if (historyCategoryFilter.value === 'all' && selectedHistoryCategory) {
                historyCategoryFilter.value = selectedHistoryCategory;
            }
        }
        if (clearHistoryBtn) {
            clearHistoryBtn.textContent = activeWorkspaceTab === 'notifications' ? '清空全部通知' : '清空全部历史';
        }
        syncHistoryFilterStateFromControls();
        if (activeWorkspaceTab === 'history' && historyFilters.type === 'notification') {
            historyFilters.type = 'all';
            if (historyTypeFilter) historyTypeFilter.value = 'all';
        }
        if (activeWorkspaceTab === 'notifications') {
            historyFilters.type = 'notification';
        }
        if (!showAssistant) {
            loadHistoryPage({ reset: true });
        } else {
            renderHistory({ mode: 'reset' });
        }
        syncAiPanelUi();
    }

    function populateOpenAiConfig(openai = {}) {
        const normalized = normalizeOpenAiConfig(openai);
        if (openaiProviderSelect) openaiProviderSelect.value = normalized.provider;
        if (openaiApiKeyInput) openaiApiKeyInput.value = normalized.api_key;
        if (openaiApiUrlInput) openaiApiUrlInput.value = normalized.api_url;
        if (openaiModelNameInput) openaiModelNameInput.value = normalized.model_name;
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

    function setInputModeUi(mode = 'direct') {
        inputMode = normalizeInputMode(mode);
        inputModeButtons.forEach(button => {
            button.classList.toggle('active', button.dataset.inputMode === inputMode);
        });
    }

    function setLastInputStatus(message, tone = '') {
        if (!lastInputStatus) return;
        lastInputStatus.textContent = message || '最近插入：暂无';
        lastInputStatus.className = 'last-input-status';
        if (tone) {
            lastInputStatus.classList.add(tone);
        }
    }

    function showToast(message, tone = 'info', duration = 3600, actions = []) {
        if (!toastContainer || !message) return;
        const toast = document.createElement('div');
        toast.className = `toast ${tone}`;
        const text = document.createElement('span');
        text.textContent = message;
        toast.appendChild(text);
        if (Array.isArray(actions) && actions.length) {
            const actionWrap = document.createElement('div');
            actionWrap.className = 'toast-actions';
            actions.forEach(action => {
                const button = document.createElement('button');
                button.type = 'button';
                button.textContent = action.label;
                button.addEventListener('click', async () => {
                    try {
                        await action.onClick?.();
                    } finally {
                        toast.remove();
                    }
                });
                actionWrap.appendChild(button);
            });
            toast.appendChild(actionWrap);
        }
        toastContainer.appendChild(toast);
        window.setTimeout(() => {
            toast.remove();
        }, duration);
    }

    async function openC2Reader() {
        debugC2Main('openC2Reader called');
        if (!openC2ReaderBtn) {
            debugC2Main('openC2Reader skipped: button missing');
            return;
        }
        openC2ReaderBtn.disabled = true;
        try {
            debugC2Main('invoke open_c2_screen_reader start');
            await invoke('open_c2_screen_reader');
            debugC2Main('invoke open_c2_screen_reader ok');
            showToast('二维码读取小窗已打开，可点击开始截图读取。', 'success', 3200);
        } catch (error) {
            debugC2Main(`invoke open_c2_screen_reader failed: ${String(error)}`);
            console.error('打开二维码读取栏失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '打开二维码读取栏失败';
            showToast(message, 'error', 6200);
        } finally {
            debugC2Main('openC2Reader finished');
            openC2ReaderBtn.disabled = false;
        }
    }

    function buildExportOpenActions(savedPath) {
        if (!savedPath) return [];
        return [
            {
                label: '打开文件',
                onClick: async () => invoke('open_path', { path: savedPath })
            },
            {
                label: '打开目录',
                onClick: async () => invoke('open_parent_folder', { path: savedPath })
            }
        ];
    }

    function showExportCompletedToast(savedPath, fallbackMessage = '导出完成。') {
        if (savedPath) {
            showToast(`导出完成：${savedPath}`, 'success', 8000, buildExportOpenActions(savedPath));
        } else {
            showToast(fallbackMessage, 'success', 4200);
        }
    }

    function handleNotificationReceived(payload = {}) {
        const appName = payload.app_name || payload.appName || '手机通知';
        const title = payload.title || '';
        const forwardMode = payload.forward_mode || payload.forwardMode || 'pc_center';
        const silent = Boolean(payload.silent) || forwardMode === 'ai_silent';
        const copied = Boolean(payload.copy_to_clipboard || payload.copyToClipboard);
        const label = title ? `${appName}：${title}` : appName;

        if (copied) {
            showToast(`通知已复制到剪贴板：${label}`, 'success', 4200);
        } else if (!silent) {
            showToast(`收到通知：${label}`, 'info', 5200, [
                {
                    label: '查看通知',
                    onClick: () => switchWorkspaceTab('notifications')
                }
            ]);
        }

        if (activeWorkspaceTab === 'notifications') {
            setDesktopTextStatus('通知记录已更新。', 'success');
        }
    }

    function confirmDialog({ title = '确认操作', message = '', okText = '确认', cancelText = '取消' } = {}) {
        if (!confirmModal || !confirmOkBtn || !confirmCancelBtn) {
            return Promise.resolve(false);
        }

        confirmModalTitle.textContent = title;
        confirmModalMessage.textContent = message;
        confirmOkBtn.textContent = okText;
        confirmCancelBtn.textContent = cancelText;
        confirmModal.style.display = 'flex';
        confirmOkBtn.focus();

        return new Promise(resolve => {
            confirmResolver = resolve;
        });
    }

    function closeConfirmDialog(result) {
        if (!confirmResolver) return;
        const resolve = confirmResolver;
        confirmResolver = null;
        confirmModal.style.display = 'none';
        resolve(result);
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

    async function checkForUpdate({ silent = false } = {}) {
        if (updateBusy) return;
        if (!silent) {
            setUpdateUi('正在检查更新...', { busy: true, updateInfo: currentUpdateInfo });
        }
        try {
            const info = await invoke('check_app_update');
            currentUpdateInfo = info;
            const latestVersion = info?.latest_version || info?.latestVersion || '';
            if (info?.has_update || info?.hasUpdate) {
                setUpdateUi(`发现 v${latestVersion}`, { busy: false, updateInfo: info });
            } else if (!silent) {
                setUpdateUi('当前已是最新版本', { busy: false, updateInfo: null });
            }
        } catch (error) {
            console.error('检查更新失败:', error);
            if (!silent) {
                setUpdateUi(`检查失败: ${error}`, { busy: false, updateInfo: null });
            }
        }
    }

    async function downloadAndOpenUpdate() {
        if (updateBusy || !currentUpdateInfo) return;
        setUpdateUi('正在下载更新...', { busy: true, updateInfo: currentUpdateInfo });
        try {
            const savedPath = await invoke('download_and_open_app_update', { info: currentUpdateInfo });
            setUpdateUi(`已下载: ${savedPath}`, { busy: false, updateInfo: null });
            showToast(`安装包已下载并打开：${savedPath}`, 'success', 5200);
        } catch (error) {
            console.error('下载更新失败:', error);
            setUpdateUi(`下载失败: ${error}`, { busy: false, updateInfo: currentUpdateInfo });
        }
    }

    function collectOpenAiConfigFromForm() {
        return {
            provider: openaiProviderSelect?.value || 'custom',
            api_key: (openaiApiKeyInput?.value || '').trim(),
            api_url: (openaiApiUrlInput?.value || '').trim(),
            model_name: (openaiModelNameInput?.value || '').trim()
        };
    }

    function setAiReportStatus(message, tone = '') {
        if (!aiReportStatus) return;
        aiReportStatus.textContent = message || '';
        aiReportStatus.className = 'ai-report-status';
        if (tone) {
            aiReportStatus.classList.add(tone);
        }
    }

    function setAiSessionStatus(message, tone = '') {
        if (!aiSessionStatus) return;
        aiSessionStatus.textContent = message || '';
        aiSessionStatus.className = 'ai-report-status';
        if (tone) {
            aiSessionStatus.classList.add(tone);
        }
    }

    function clearAiErrorDetails() {
        if (aiErrorDetails) {
            aiErrorDetails.style.display = 'none';
            aiErrorDetails.open = false;
        }
        if (aiErrorContent) aiErrorContent.textContent = '';
    }

    function showAiErrorDetails(error, { requestId = currentAssistantRequestId, source = '电脑端' } = {}) {
        if (!aiErrorDetails || !aiErrorContent) return;
        const message = typeof error === 'string' ? error : error?.message || String(error || '未知错误');
        const lines = [
            `时间: ${formatDateTime(Date.now())}`,
            `来源: ${source}`,
            `请求 ID: ${requestId || '未提供'}`,
            `错误: ${message}`
        ];
        aiErrorContent.textContent = lines.join('\n');
        aiErrorDetails.style.display = 'block';
        aiErrorDetails.open = true;
    }

    async function loadAiSkills() {
        try {
            aiSkills = await invoke('list_ai_skills');
            renderAiSkills();
        if (!selectedAiSkillId) {
            selectedAiSkillId = aiSkills.find(skill => skill.enabled)?.id || aiSkills[0]?.id || null;
        }
        populateSelectedSkillEditor();
            setAiSessionStatus(aiSkills.length ? `已加载 ${aiSkills.length} 个 Skill。` : '暂无可用 Skill。');
        } catch (error) {
            console.error('加载 AI Skills 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '加载 Skills 失败';
            setAiSessionStatus(message, 'error');
        }
    }

    function renderAiSkills() {
        if (!aiSkillsList) return;
        if (!aiSkills.length) {
            aiSkillsList.innerHTML = aiAssistantPlaceholder('skills');
            return;
        }
        aiSkillsList.innerHTML = aiSkills.map(skill => `
            <button class="ai-skill-chip ${skill.id === selectedAiSkillId ? 'active' : ''}" data-skill-id="${escapeHtml(skill.id)}" title="${escapeHtml(skill.description || '')}">
                <strong>${escapeHtml(skill.name || skill.id)}</strong>
                <span>${escapeHtml(skill.description || '')}</span>
                <span>${escapeHtml(skill.id === selectedAiSkillId ? '正在编辑' : (skill.enabled === false ? '已停用' : '可由 LLM 选择'))}</span>
            </button>
        `).join('');
    }

    async function loadAiSessions({ selectLatest = false } = {}) {
        try {
            aiSessions = await invoke('list_ai_sessions', { limit: 30 });
            if (!currentAiSessionId && (selectLatest || aiSessions.length)) {
                currentAiSessionId = aiSessions[0]?.id || null;
            }
            renderAiSessions();
            if (currentAiSessionId) {
                await loadAiSessionDetail(currentAiSessionId);
            } else {
                aiMessages = [];
                aiToolCalls = [];
                renderAiMessages();
                renderAiToolCalls();
            }
        } catch (error) {
            console.error('加载 AI 会话失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '加载会话失败';
            setAiSessionStatus(message, 'error');
        }
    }

    function renderAiSessions() {
        if (!aiSessionList) return;
        const hasCurrentSession = Boolean(currentAiSessionId && aiSessions.some(session => session.id === currentAiSessionId));
        if (renameAiSessionBtn) renameAiSessionBtn.disabled = !hasCurrentSession;
        if (deleteAiSessionBtn) deleteAiSessionBtn.disabled = !hasCurrentSession;
        if (!aiSessions.length) {
            aiSessionList.innerHTML = aiAssistantPlaceholder('sessions');
            return;
        }
        aiSessionList.innerHTML = aiSessions.map(session => {
            return `
                <button class="ai-session-item ${session.id === currentAiSessionId ? 'active' : ''}" data-session-id="${escapeHtml(session.id)}">
                    <strong>${escapeHtml(session.title || '新会话')}</strong>
                    <span>${escapeHtml(formatDateTime(session.updated_at || session.updatedAt || Date.now()))}</span>
                </button>
            `;
        }).join('');
    }

    function setAiSideTab(tab = 'tools') {
        const normalized = ['tools', 'references', 'exports'].includes(tab) ? tab : 'tools';
        aiSideTabs.forEach(button => {
            button.classList.toggle('active', button.dataset.aiSideTab === normalized);
        });
        aiSidePanels.forEach(panel => {
            panel.classList.toggle('active', panel.dataset.aiSidePanel === normalized);
        });
    }

    async function loadAiSessionDetail(sessionId) {
        if (!sessionId) return;
        editingAiMessageId = null;
        try {
            currentAiSessionId = sessionId;
            const [messages, toolCalls, exportedFiles] = await Promise.all([
                invoke('list_ai_messages', { sessionId }),
                invoke('list_ai_tool_calls', { sessionId }),
                invoke('list_ai_exported_files', { sessionId })
            ]);
            aiMessages = messages || [];
            aiToolCalls = toolCalls || [];
            aiExportedFiles = exportedFiles || [];
            aiSessions = aiSessions.map(session => {
                if (session.id !== sessionId) return session;
                return {
                    ...session,
                    exported_files: aiExportedFiles,
                    exportedFiles: aiExportedFiles
                };
            });
            renderAiSessions();
            renderAiMessages();
            renderAiToolCalls();
            renderAiExportedFiles();
            const lastAssistant = [...aiMessages].reverse().find(message => message.role === 'assistant');
            setAiReportContent(lastAssistant?.content || '', { resetAutoScroll: true });
        } catch (error) {
            console.error('加载 AI 会话详情失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '加载会话详情失败';
            setAiSessionStatus(message, 'error');
        }
    }

    function renderAiMessages({ streamingText = null } = {}) {
        if (!aiChatMessages) return;
        const previousScrollTop = aiChatMessages.scrollTop;
        const messages = [...aiMessages];
        if (streamingText !== null) {
            messages.push({
                id: 'streaming',
                role: 'assistant',
                content: streamingText || '正在调用工具并等待模型流式输出...',
                streaming: true,
                created_at: Date.now()
            });
        }
        if (editingAiMessageId && !messages.some(message => message.id === editingAiMessageId)) {
            editingAiMessageId = null;
        }
        if (!messages.length) {
            aiChatMessages.innerHTML = aiAssistantPlaceholder('chat');
            aiChatShouldAutoScroll = true;
            scrollAiChatToBottom();
            return;
        }
        aiChatMessages.innerHTML = messages
            .map(message => renderAiMessageItem({
                ...message,
                editing: message.id === editingAiMessageId && !message.streaming
            }, markdownToHtml))
            .join('');
        if (aiChatShouldAutoScroll) {
            scrollAiChatToBottom();
        } else {
            aiChatIgnoreScrollEvent = true;
            aiChatMessages.scrollTop = previousScrollTop;
            requestAnimationFrame(() => {
                aiChatIgnoreScrollEvent = false;
            });
        }
    }

    function findAiMessageElement(messageId) {
        return Array.from(aiChatMessages?.querySelectorAll('.ai-message') || [])
            .find(element => element.dataset.messageId === messageId) || null;
    }

    function resizeAiMessageEditor(textarea) {
        if (!textarea) return;
        const minHeight = 96;
        const maxHeight = Math.max(minHeight, Math.floor(window.innerHeight * 0.62));
        textarea.style.height = 'auto';
        const height = Math.min(Math.max(textarea.scrollHeight, minHeight), maxHeight);
        textarea.style.height = `${height}px`;
        textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
    }

    function focusAiMessageEditor(messageId) {
        const article = findAiMessageElement(messageId);
        const textarea = article?.querySelector('.ai-message-editor');
        if (!textarea) return;
        resizeAiMessageEditor(textarea);
        textarea.focus();
        textarea.setSelectionRange(textarea.value.length, textarea.value.length);
    }

    function startAiMessageEdit(messageId) {
        const message = aiMessages.find(item => item.id === messageId);
        if (!message || message.streaming) return;
        editingAiMessageId = messageId;
        renderAiMessages();
        requestAnimationFrame(() => focusAiMessageEditor(messageId));
    }

    function cancelAiMessageEdit() {
        editingAiMessageId = null;
        renderAiMessages();
    }

    function renderAiToolCalls() {
        if (!aiToolCallList) return;
        if (!aiToolCalls.length) {
            aiToolCallList.innerHTML = aiAssistantPlaceholder('tools');
            return;
        }
        aiToolCallList.innerHTML = aiToolCalls.map(renderAiToolCallItem).join('');
        renderAiReferences();
        renderAiExportedFiles();
    }

    function renderAiReferences() {
        if (!aiReferenceList) return;
        const references = collectAiReferences();
        if (!references.length) {
            aiReferenceList.innerHTML = aiAssistantPlaceholder('references');
            return;
        }
        aiReferenceList.innerHTML = renderAiReferenceItems(references);
    }

    function collectAiReferences() {
        return collectAiReferencesModel(aiToolCalls, aiMessages, findHistoryRecordById);
    }

    function renderAiExportedFiles() {
        if (!aiExportedFileList) return;
        if (!aiExportedFiles.length) {
            aiExportedFileList.innerHTML = aiAssistantPlaceholder('exports');
            return;
        }
        aiExportedFileList.innerHTML = renderAiExportedFileItems(aiExportedFiles);
    }

    async function handleAiReferenceAction(action, recordId) {
        const record = collectAiReferences().find(item => item.id === recordId) || findHistoryRecordById(recordId);
        if (!record) {
            setAiSessionStatus('引用记录不存在或尚未加载。', 'error');
            return;
        }
        if (action === 'copy') {
            try {
                await invoke('copy_text_to_clipboard', { content: record.content || '' });
                setAiSessionStatus('引用内容已复制。', 'success');
            } catch (error) {
                console.error('复制引用失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '复制引用失败';
                setAiSessionStatus(message, 'error');
            }
            return;
        }
        if (action === 'insert') {
            try {
                await invoke('insert_text_to_cursor', { content: record.content || '' });
                setLastInputStatus('最近插入：AI 引用记录已直接插入。', 'success');
                setAiSessionStatus('引用记录已插入到光标。', 'success');
            } catch (error) {
                console.error('插入引用失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '插入引用失败';
                setAiSessionStatus(message, 'error');
            }
        }
    }

    async function handleAiMessageEditAction(action, messageId) {
        const message = aiMessages.find(item => item.id === messageId);
        if (!message || message.streaming || editingAiMessageId !== messageId) return;

        if (action === 'cancel') {
            cancelAiMessageEdit();
            return;
        }

        if (action !== 'save') return;
        const article = findAiMessageElement(messageId);
        const textarea = article?.querySelector('.ai-message-editor');
        const content = textarea?.value ?? '';
        if (!content.trim()) {
            setAiSessionStatus('消息内容不能为空。', 'error');
            textarea?.focus();
            return;
        }

        const saveButton = article?.querySelector('[data-ai-message-edit-action="save"]');
        const cancelButton = article?.querySelector('[data-ai-message-edit-action="cancel"]');
        if (saveButton) saveButton.disabled = true;
        if (cancelButton) cancelButton.disabled = true;
        try {
            const updated = await invoke('update_ai_message', {
                messageId,
                content
            });
            aiMessages = aiMessages.map(item => item.id === messageId ? updated : item);
            editingAiMessageId = null;
            renderAiMessages();
            const lastAssistant = [...aiMessages].reverse().find(item => item.role === 'assistant');
            setAiReportContent(lastAssistant?.content || '', { resetAutoScroll: false });
            setAiSessionStatus('消息已更新。', 'success');
        } catch (error) {
            console.error('编辑 AI 消息失败:', error);
            if (saveButton) saveButton.disabled = false;
            if (cancelButton) cancelButton.disabled = false;
            const text = typeof error === 'string' ? error : error?.message || '编辑消息失败';
            setAiSessionStatus(text, 'error');
            textarea?.focus();
        }
    }

    async function handleAiMessageAction(action, messageId, button = null) {
        const message = aiMessages.find(item => item.id === messageId);
        if (!message || message.streaming) return;

        if (action === 'copy') {
            try {
                await invoke('copy_text_to_clipboard', { content: message.content || '' });
                if (button) {
                    button.textContent = '✔';
                    button.title = '已复制';
                    button.setAttribute('aria-label', '已复制');
                    button.classList.add('copied');
                }
                setAiSessionStatus('消息已复制。', 'success');
            } catch (error) {
                console.error('复制 AI 消息失败:', error);
                const text = typeof error === 'string' ? error : error?.message || '复制消息失败';
                setAiSessionStatus(text, 'error');
            }
            return;
        }

        if (action === 'edit') {
            startAiMessageEdit(messageId);
            return;
        }

        if (action === 'delete') {
            const confirmed = await confirmDialog({
                title: '删除 AI 消息',
                message: '确定删除这条消息吗？关联的工具调用和导出记录也会一并删除。',
                okText: '删除'
            });
            if (!confirmed) return;
            try {
                await invoke('delete_ai_message', { messageId });
                await loadAiSessionDetail(currentAiSessionId);
                setAiSessionStatus('消息已删除。', 'success');
            } catch (error) {
                console.error('删除 AI 消息失败:', error);
                const text = typeof error === 'string' ? error : error?.message || '删除消息失败';
                setAiSessionStatus(text, 'error');
            }
        }
    }

    function parseMetadata(metadata) {
        if (!metadata) return null;
        if (typeof metadata === 'object') return metadata;
        return parseJsonSafe(metadata);
    }

    function appendAiToolEvent(payload = {}) {
        if (!payload || payload.request_id !== currentAssistantRequestId) return;
        if (stoppedAssistantRequestIds.has(payload.request_id)) return;
        const result = applyAiToolEvent(aiToolCalls, payload);
        if (!result.handled) return;
        aiToolCalls = result.toolCalls;
        renderAiToolCalls();
        if (result.statusMessage) {
            setAiSessionStatus(result.statusMessage, result.statusTone || '');
        }
        if (payload.event === 'assistant_error') {
            showAiErrorDetails(payload.message || 'AI 助手执行失败。', { requestId: payload.request_id });
        }
    }

    async function forwardRemoteAiEvent(payload = {}) {
        const requestId = payload.request_id || payload.requestId;
        const toDeviceId = remoteAiTargets.get(requestId);
        if (!toDeviceId || !requestId) return;
        try {
            await invoke('send_relay_payload', {
                toDeviceId,
                payload: {
                    type: 'AI_ASSISTANT_EVENT',
                    request_id: requestId,
                    session_id: payload.session_id || payload.sessionId || null,
                    event: payload.event || '',
                    tool_name: payload.tool_name || payload.toolName || '',
                    tool_call_id: payload.tool_call_id || payload.toolCallId || null,
                    message: payload.message || '',
                    data: payload.data || null,
                    timestamp: Date.now()
                }
            });
        } catch (error) {
            console.error('转发手机 AI 工具事件失败:', error);
        }
    }

    function populateSelectedSkillEditor() {
        const skill = aiSkills.find(item => item.id === selectedAiSkillId) || aiSkills[0];
        if (!skill) return;
        selectedAiSkillId = skill.id;
        if (aiSkillNameInput) aiSkillNameInput.value = skill.name || '';
        if (aiSkillDescriptionInput) aiSkillDescriptionInput.value = skill.description || '';
        if (aiSkillInputSchemaInput) aiSkillInputSchemaInput.value = skill.input_schema || skill.inputSchema || '{}';
        if (aiSkillTemplateInput) aiSkillTemplateInput.value = skill.prompt_template || skill.promptTemplate || '';
        if (aiSkillPeriodSelect) aiSkillPeriodSelect.value = skill.default_period || skill.defaultPeriod || 'custom';
        if (aiSkillDefaultFiltersInput) aiSkillDefaultFiltersInput.value = skill.default_filters || skill.defaultFilters || '{}';
        if (aiSkillOutputFormatInput) aiSkillOutputFormatInput.value = skill.output_format || skill.outputFormat || '';
        if (aiSkillEnabledInput) aiSkillEnabledInput.checked = skill.enabled !== false;
        renderAiSkills();
    }

    async function saveSelectedAiSkill() {
        const existing = aiSkills.find(item => item.id === selectedAiSkillId);
        if (!existing) {
            setAiSessionStatus('请先选择 Skill。', 'error');
            return;
        }
        const inputSchema = aiSkillInputSchemaInput?.value.trim() || '{}';
        const parsedInputSchema = parseJsonSafe(inputSchema);
        if (!parsedInputSchema || Array.isArray(parsedInputSchema) || typeof parsedInputSchema !== 'object') {
            setAiSessionStatus('输入参数必须是 JSON 对象，例如 {"required":["search"]}。', 'error');
            return;
        }
        const defaultFilters = aiSkillDefaultFiltersInput?.value.trim() || '{}';
        const parsedFilters = parseJsonSafe(defaultFilters);
        if (!parsedFilters || Array.isArray(parsedFilters) || typeof parsedFilters !== 'object') {
            setAiSessionStatus('默认筛选必须是 JSON 对象，例如 {"content_type":"notification"}。', 'error');
            return;
        }
        const skill = {
            ...existing,
            name: aiSkillNameInput.value.trim() || existing.name,
            description: aiSkillDescriptionInput.value.trim(),
            input_schema: JSON.stringify(parsedInputSchema),
            prompt_template: aiSkillTemplateInput.value,
            default_period: aiSkillPeriodSelect?.value || existing.default_period || existing.defaultPeriod || 'custom',
            default_filters: JSON.stringify(parsedFilters),
            output_format: aiSkillOutputFormatInput?.value.trim() || existing.output_format || existing.outputFormat || '',
            enabled: aiSkillEnabledInput ? aiSkillEnabledInput.checked : existing.enabled !== false
        };
        try {
            const saved = await invoke('save_ai_skill', { skill });
            aiSkills = aiSkills.map(item => item.id === saved.id ? saved : item);
            selectedAiSkillId = saved.id;
            renderAiSkills();
            setAiSessionStatus('Skill 已保存。', 'success');
        } catch (error) {
            console.error('保存 Skill 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存 Skill 失败';
            setAiSessionStatus(message, 'error');
        }
    }

    async function exportAiSkills() {
        try {
            const result = await invoke('export_ai_skills_json');
            const savedPath = result.saved_path || result.savedPath;
            setAiSessionStatus(`Skills 已导出：${savedPath || result.filename}`, 'success');
            showExportCompletedToast(savedPath, `Skills 已导出：${result.filename || '文件已生成'}`);
        } catch (error) {
            console.error('导出 Skills 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出 Skills 失败';
            setAiSessionStatus(message, 'error');
        }
    }

    function setAiSkillImportBoxVisible(visible) {
        if (!aiSkillImportBox) return;
        aiSkillImportBox.style.display = visible ? 'flex' : 'none';
        if (visible) {
            aiSkillImportText?.focus();
        } else if (aiSkillImportText) {
            aiSkillImportText.value = '';
        }
    }

    async function importAiSkillsFromText() {
        const jsonText = aiSkillImportText?.value || '';
        if (!jsonText.trim()) {
            setAiSessionStatus('请先粘贴 Skills JSON。', 'error');
            aiSkillImportText?.focus();
            return;
        }
        try {
            const saved = await invoke('import_ai_skills_json', { jsonText });
            await loadAiSkills();
            selectedAiSkillId = saved?.[0]?.id || selectedAiSkillId;
            populateSelectedSkillEditor();
            setAiSkillImportBoxVisible(false);
            setAiSessionStatus(`已导入 ${saved?.length || 0} 个 Skill。`, 'success');
        } catch (error) {
            console.error('导入 Skills 失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导入 Skills 失败';
            setAiSessionStatus(message, 'error');
        }
    }

    function resetAiConversationDraft() {
        currentAiSessionId = null;
        aiMessages = [];
        editingAiMessageId = null;
        aiToolCalls = [];
        aiExportedFiles = [];
        aiChatShouldAutoScroll = true;
        aiChatInput.value = '';
        setAiReportContent('', { resetAutoScroll: true });
        renderAiSessions();
        renderAiMessages();
        renderAiToolCalls();
        renderAiExportedFiles();
        clearAiErrorDetails();
        setAiSessionStatus('已准备新会话。');
    }

    async function createAiSessionFromInput() {
        const question = (aiChatInput?.value || '').trim();
        if (!question) {
            setAiSessionStatus('请先输入问题。', 'error');
            aiChatInput?.focus();
            return;
        }

        try {
            clearAiErrorDetails();
            currentAssistantRequestId = createAiReportRequestId('assistant');
            stoppedAssistantRequestIds.delete(currentAssistantRequestId);
            aiChatShouldAutoScroll = true;
            const pendingUserMessage = {
                id: `pending-${Date.now()}`,
                role: 'user',
                content: question,
                created_at: Date.now()
            };
            aiMessages = [...aiMessages, pendingUserMessage];
            setAiReportContent('', { resetAutoScroll: true });
            renderAiMessages({ streamingText: '' });
            const filters = buildAiAssistantFilters();
            setAiSessionStatus(`${buildAiAssistantScopeText(filters)} 正在由 LLM 选择。`);
            createAiSessionBtn.disabled = true;
            stopAiSessionBtn.style.display = 'inline-flex';
            const result = await invoke('run_ai_assistant', {
                sessionId: currentAiSessionId,
                question,
                skillId: null,
                filters,
                requestId: currentAssistantRequestId
            });
            if (stoppedAssistantRequestIds.has(currentAssistantRequestId)) {
                setAiSessionStatus('已停止显示本次生成。');
                return;
            }
            currentAiSessionId = result?.session_id || result?.sessionId || currentAiSessionId;
            aiChatInput.value = '';
            if (result?.content && !currentAiReportContent.trim()) {
                setAiReportContent(result.content);
            }
            await loadAiSessions();
            await loadAiSessionDetail(currentAiSessionId);
            const exportedFile = result?.exported_file || result?.exportedFile;
            const savedPath = exportedFile?.saved_path || exportedFile?.savedPath;
            setAiSessionStatus(
                savedPath
                    ? `回答完成，引用 ${result?.record_count ?? result?.recordCount ?? 0} 条记录，已导出：${savedPath}`
                    : `回答完成，引用 ${result?.record_count ?? result?.recordCount ?? 0} 条记录。`,
                'success'
            );
            if (savedPath) {
                showExportCompletedToast(savedPath, '回答已导出。');
            }
        } catch (error) {
            console.error('创建 AI 会话失败:', error);
            aiMessages = aiMessages.filter(message => !String(message.id).startsWith('pending-'));
            renderAiMessages();
            const message = typeof error === 'string' ? error : error?.message || '创建会话失败';
            setAiSessionStatus(message, 'error');
            showAiErrorDetails(error, { requestId: currentAssistantRequestId });
        } finally {
            stopAiSessionBtn.style.display = 'none';
            currentAssistantRequestId = null;
            createAiSessionBtn.disabled = false;
        }
    }

    async function handleRemoteAiAssistantRequest(payload = {}) {
        const question = String(payload.question || '').trim();
        const fromDeviceId = payload.from_device_id || payload.fromDeviceId;
        const requestId = payload.request_id || payload.requestId || createAiReportRequestId('remote-assistant');
        if (!question || !fromDeviceId) return;

        switchWorkspaceTab('assistant');
        toggleAiPanel(true);
        currentAssistantRequestId = requestId;
        stoppedAssistantRequestIds.delete(requestId);
        aiMessages = [
            ...aiMessages,
            {
                id: `remote-pending-${Date.now()}`,
                role: 'user',
                content: question,
                created_at: Date.now()
            }
        ];
        aiToolCalls = [];
        setAiReportContent('', { resetAutoScroll: true });
        renderAiMessages({ streamingText: '' });
        renderAiToolCalls();
        setAiSessionStatus(`来自 ${payload.from_device_name || payload.fromDeviceName || '手机'} 的 AI 请求，正在由 LLM 选择工具...`);
        createAiSessionBtn.disabled = true;
        stopAiSessionBtn.style.display = 'inline-flex';
        remoteAiTargets.set(requestId, fromDeviceId);

        try {
            const remoteFilters = payload.filters && typeof payload.filters === 'object'
                ? { ...payload.filters, limit: payload.filters.limit || 120 }
                : { limit: 120 };
            const result = await invoke('run_ai_assistant', {
                sessionId: payload.session_id || payload.sessionId || null,
                question,
                skillId: null,
                filters: remoteFilters,
                requestId
            });
            if (stoppedAssistantRequestIds.has(requestId)) {
                setAiSessionStatus('已停止显示本次手机 AI 请求。');
                return;
            }
            await loadAiSessions();
            await loadAiSessionDetail(result?.session_id || result?.sessionId);
            await invoke('send_relay_payload', {
                toDeviceId: fromDeviceId,
                payload: {
                    type: 'AI_ASSISTANT_RESPONSE',
                    request_id: requestId,
                    success: true,
                    session_id: result?.session_id || result?.sessionId,
                    content: result?.content || '',
                    record_count: result?.record_count ?? result?.recordCount ?? 0,
                    exported_file: result?.exported_file || result?.exportedFile || null,
                    timestamp: Date.now()
                }
            });
            if (currentAssistantRequestId === requestId) {
                setAiSessionStatus('手机 AI 请求已完成并返回。', 'success');
            }
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || 'AI 请求失败';
            console.error('处理手机 AI 请求失败:', error);
            if (currentAssistantRequestId === requestId) {
                setAiSessionStatus(message, 'error');
            }
            try {
                await invoke('send_relay_payload', {
                    toDeviceId: fromDeviceId,
                    payload: {
                        type: 'AI_ASSISTANT_RESPONSE',
                        request_id: requestId,
                        success: false,
                        error: message,
                        timestamp: Date.now()
                    }
                });
            } catch (sendError) {
                console.error('返回手机 AI 错误失败:', sendError);
            }
        } finally {
            remoteAiTargets.delete(requestId);
            if (currentAssistantRequestId === requestId) {
                currentAssistantRequestId = null;
                stopAiSessionBtn.style.display = 'none';
                createAiSessionBtn.disabled = false;
            }
        }
    }

    async function forwardRemoteAiDelta(requestId, delta) {
        const toDeviceId = remoteAiTargets.get(requestId);
        if (!toDeviceId || !delta) return;
        try {
            await invoke('send_relay_payload', {
                toDeviceId,
                payload: {
                    type: 'AI_ASSISTANT_DELTA',
                    request_id: requestId,
                    delta,
                    timestamp: Date.now()
                }
            });
        } catch (error) {
            console.error('转发手机 AI 增量失败:', error);
        }
    }

    async function handleRemoteAiAssistantCancel(payload = {}) {
        const requestId = payload.request_id || payload.requestId;
        const fromDeviceId = payload.from_device_id || payload.fromDeviceId || remoteAiTargets.get(requestId);
        if (!requestId) return;

        stoppedAssistantRequestIds.add(requestId);
        try {
            await invoke('cancel_ai_assistant_request', { requestId });
        } catch (error) {
            console.error('取消手机 AI 请求失败:', error);
        }

        if (currentAssistantRequestId === requestId) {
            setAiSessionStatus('手机端已停止本次 AI 生成。');
            stopAiSessionBtn.style.display = 'none';
            createAiSessionBtn.disabled = false;
            currentAssistantRequestId = null;
        }

        if (fromDeviceId) {
            try {
                await invoke('send_relay_payload', {
                    toDeviceId: fromDeviceId,
                    payload: {
                        type: 'AI_ASSISTANT_EVENT',
                        request_id: requestId,
                        event: 'assistant_error',
                        message: 'AI 生成已取消',
                        timestamp: Date.now()
                    }
                });
            } catch (error) {
                console.error('返回手机 AI 取消状态失败:', error);
            }
        }
    }

    function createAiReportRequestId(period) {
        return `${period}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }

    function updateAiReportActionState() {
        const hasContent = Boolean(currentAiReportContent.trim());
        if (copyAiReportBtn) copyAiReportBtn.disabled = !hasContent;
        if (copyAiReportInlineBtn) copyAiReportInlineBtn.disabled = !hasContent;
    }

    function isAiReportNearBottom() {
        if (!aiReportOutput) return true;
        return aiReportOutput.scrollHeight - aiReportOutput.scrollTop - aiReportOutput.clientHeight <= AI_REPORT_SCROLL_THRESHOLD;
    }

    function isAiChatNearBottom() {
        if (!aiChatMessages) return true;
        return aiChatMessages.scrollHeight - aiChatMessages.scrollTop - aiChatMessages.clientHeight <= AI_REPORT_SCROLL_THRESHOLD;
    }

    function scrollAiReportToBottom() {
        if (!aiReportOutput) return;
        aiReportIgnoreScrollEvent = true;
        aiReportOutput.scrollTop = aiReportOutput.scrollHeight;
        requestAnimationFrame(() => {
            aiReportIgnoreScrollEvent = false;
        });
    }

    function scrollAiChatToBottom() {
        if (!aiChatMessages) return;
        aiChatIgnoreScrollEvent = true;
        aiChatMessages.scrollTop = aiChatMessages.scrollHeight;
        requestAnimationFrame(() => {
            aiChatIgnoreScrollEvent = false;
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
        if (!aiReportOutput) return;
        const normalized = markdown.trim();
        if (!normalized) {
            aiReportOutput.innerHTML = aiAssistantPlaceholder('output');
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

    function refreshAiConfigurationState(openai = collectOpenAiConfigFromForm()) {
        aiConfigured = hasConfiguredOpenAi(openai);
        syncAiPanelUi();
        return aiConfigured;
    }

    function setAiReportBusy(busy, actionLabel = '处理中') {
        aiReportBusy = busy;
        if (saveOpenAiConfigBtn) saveOpenAiConfigBtn.disabled = busy;
        updateAiReportActionState();
    }

    async function saveOpenAiConfig({ silent = false } = {}) {
        const openai = collectOpenAiConfigFromForm();
        await invoke('save_ai_model_config', { openai });
        refreshAiConfigurationState(openai);
        if (!silent) {
            setAiReportStatus('AI 配置已保存。', 'success');
        }
    }

    function ensureAiConfigured(actionLabel) {
        if (refreshAiConfigurationState()) {
            return true;
        }

        switchWorkspaceTab('assistant');
        setAiSettingsVisible(true);
        setAiReportStatus(`请先完成 AI 设置，再${actionLabel}。`, 'error');
        return false;
    }

    async function copyAiReport() {
        const content = currentAiReportContent.trim();
        if (!content) {
            setAiReportStatus('当前没有可复制的回答内容。', 'error');
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
            setAiReportStatus('回答已复制到剪贴板。', 'success');
        } catch (error) {
            console.error('复制 AI 回答失败:', error);
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

    function getSelectLabel(selectEl, fallback = '全部') {
        if (!selectEl) return fallback;
        return selectEl.selectedOptions?.[0]?.textContent?.trim() || fallback;
    }

    function buildAiAssistantFilters() {
        return buildAiAssistantFiltersModel(historyFilters, activeWorkspaceTab, 120);
    }

    function buildAiAssistantScopeText(filters = buildAiAssistantFilters()) {
        return buildAiAssistantScopeTextModel(filters, activeWorkspaceTab, (key, fallback) => {
            switch (key) {
                case 'type':
                    return getSelectLabel(historyTypeFilter, fallback);
                case 'channel':
                    return getSelectLabel(historyChannelFilter, fallback);
                case 'device':
                    return getSelectLabel(historyDeviceFilter, fallback);
                case 'status':
                    return getSelectLabel(historyStatusFilter, fallback);
                case 'sourceApp':
                    return getSelectLabel(historySourceAppFilter, fallback);
                case 'category':
                    return getSelectLabel(historyCategoryFilter, fallback);
                default:
                    return fallback;
            }
        });
    }

    function currentHistoryDeliveryStatusFilter() {
        return currentHistoryDeliveryStatusFilterModel(historyFilters);
    }

    function currentHistoryFavoriteFilter() {
        return currentHistoryFavoriteFilterModel(historyFilters);
    }

    function currentHistoryPinnedFilter() {
        return currentHistoryPinnedFilterModel(historyFilters);
    }

    function currentWorkspaceContentTypeScope() {
        return workspaceContentTypeScope(activeWorkspaceTab);
    }

    function currentHistoryContentTypeFilter() {
        return currentHistoryContentTypeFilterModel(historyFilters, activeWorkspaceTab);
    }

    function getFilteredHistoryRecords() {
        return filterHistoryRecords(historyRecords, historyFilters, activeWorkspaceTab);
    }

    function updateHistoryFilterOptions() {
        syncSelectOptions(
            historyChannelFilter,
            historyFilters.channel,
            '全部通道',
            Array.from(new Set(historyRecords.map(item => item.via).filter(Boolean))).sort()
        );
        syncSelectOptions(
            historyDeviceFilter,
            historyFilters.device,
            '全部来源',
            Array.from(new Set(historyRecords.map(recordDeviceKey))).sort()
        );
        syncSelectOptions(
            historySourceAppFilter,
            historyFilters.sourceApp,
            '全部 App',
            Array.from(new Set(historyRecords.map(recordSourceAppKey).filter(Boolean))).sort()
        );
        syncSelectOptions(
            historyCategoryFilter,
            historyFilters.category,
            '全部目录',
            historyCategories
        );
        syncDesktopCategoryOptions();
    }

    function syncSelectOptions(selectEl, currentValue, allLabel, values) {
        if (!selectEl) return;
        const normalizedValues = new Set(values);
        if (currentValue && currentValue !== 'all') {
            normalizedValues.add(currentValue);
        }
        const nextValues = ['all', ...Array.from(normalizedValues).sort()];
        selectEl.innerHTML = nextValues.map(value => {
            const label = value === 'all' ? allLabel : value;
            return `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`;
        }).join('');
        selectEl.value = nextValues.includes(currentValue) ? currentValue : 'all';
    }

    function syncDesktopCategoryOptions() {
        if (!desktopCategorySelect) return;
        const nextValues = Array.from(new Set([DEFAULT_HISTORY_CATEGORY, ...historyCategories])).filter(Boolean);
        if (!nextValues.includes(selectedHistoryCategory)) {
            selectedHistoryCategory = DEFAULT_HISTORY_CATEGORY;
        }
        desktopCategorySelect.innerHTML = nextValues.map(value => {
            return `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`;
        }).join('');
        desktopCategorySelect.value = selectedHistoryCategory;
        const canEdit = selectedHistoryCategory !== DEFAULT_HISTORY_CATEGORY;
        if (renameDesktopCategoryBtn) renameDesktopCategoryBtn.disabled = !canEdit;
        if (deleteDesktopCategoryBtn) deleteDesktopCategoryBtn.disabled = !canEdit;
    }

    async function refreshHistoryCategories() {
        try {
            const categories = await invoke('list_history_categories');
            historyCategories = Array.isArray(categories) && categories.length
                ? categories
                : [DEFAULT_HISTORY_CATEGORY];
        } catch (error) {
            console.error('加载目录失败:', error);
            historyCategories = [DEFAULT_HISTORY_CATEGORY];
        }
        syncDesktopCategoryOptions();
        if (historyCategoryFilter) {
            syncSelectOptions(historyCategoryFilter, historyFilters.category, '全部目录', historyCategories);
        }
    }

    async function createHistoryCategory() {
        const name = window.prompt('新建目录名称', '');
        const normalized = String(name || '').trim();
        if (!normalized) return;
        try {
            historyCategories = await invoke('add_history_category', { category: normalized });
            selectedHistoryCategory = normalized;
            await refreshHistoryCategories();
            if (historyCategoryFilter) historyCategoryFilter.value = normalized;
            syncHistoryFilterStateFromControls();
            await loadHistoryPage({ reset: true });
            setDesktopTextStatus(`已新建目录：${normalized}`, 'success');
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '新建目录失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function renameHistoryCategory() {
        if (selectedHistoryCategory === DEFAULT_HISTORY_CATEGORY) return;
        const name = window.prompt('修改目录名称', selectedHistoryCategory);
        const normalized = String(name || '').trim();
        if (!normalized || normalized === selectedHistoryCategory) return;
        try {
            historyCategories = await invoke('rename_history_category', {
                oldName: selectedHistoryCategory,
                newName: normalized
            });
            selectedHistoryCategory = normalized;
            historyFilters.category = normalized;
            await refreshHistoryCategories();
            if (historyCategoryFilter) historyCategoryFilter.value = normalized;
            syncHistoryFilterStateFromControls();
            await loadHistoryPage({ reset: true });
            setDesktopTextStatus(`目录已修改为：${normalized}`, 'success');
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '修改目录失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function deleteHistoryCategory() {
        if (selectedHistoryCategory === DEFAULT_HISTORY_CATEGORY) return;
        const category = selectedHistoryCategory;
        const shouldDelete = await confirmDialog({
            title: '删除目录',
            message: `确定删除“${category}”吗？该目录下的历史记录会移到“${DEFAULT_HISTORY_CATEGORY}”。`,
            okText: '删除'
        });
        if (!shouldDelete) return;
        try {
            historyCategories = await invoke('delete_history_category', { category });
            selectedHistoryCategory = DEFAULT_HISTORY_CATEGORY;
            historyFilters.category = DEFAULT_HISTORY_CATEGORY;
            await refreshHistoryCategories();
            if (historyCategoryFilter) historyCategoryFilter.value = DEFAULT_HISTORY_CATEGORY;
            syncHistoryFilterStateFromControls();
            await loadHistoryPage({ reset: true });
            setDesktopTextStatus(`已删除目录：${category}`, 'success');
        } catch (error) {
            const message = typeof error === 'string' ? error : error?.message || '删除目录失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    function syncHistoryFilterStateFromControls() {
        historyFilters = {
            query: historySearchInput?.value || '',
            type: activeWorkspaceTab === 'notifications' ? 'notification' : (historyTypeFilter?.value || 'all'),
            channel: historyChannelFilter?.value || 'all',
            device: historyDeviceFilter?.value || 'all',
            status: historyStatusFilter?.value || 'all',
            sourceApp: historySourceAppFilter?.value || 'all',
            tag: historyTagFilter?.value || '',
            category: activeWorkspaceTab === 'notifications'
                ? 'all'
                : (historyCategoryFilter?.value || selectedHistoryCategory || DEFAULT_HISTORY_CATEGORY)
        };
    }

    function resetHistoryFilters() {
        if (historySearchInput) historySearchInput.value = '';
        if (historyTypeFilter) historyTypeFilter.value = 'all';
        if (historyChannelFilter) historyChannelFilter.value = 'all';
        if (historyDeviceFilter) historyDeviceFilter.value = 'all';
        if (historyStatusFilter) historyStatusFilter.value = 'all';
        if (historySourceAppFilter) historySourceAppFilter.value = 'all';
        if (historyTagFilter) historyTagFilter.value = '';
        if (historyCategoryFilter) historyCategoryFilter.value = 'all';
        syncHistoryFilterStateFromControls();
        renderHistory({ mode: 'reset' });
    }

    function updateHistoryFilterSummary(filteredCount = getFilteredHistoryRecords().length) {
        if (!historyFilterSummary) return;
        const total = historyRecords.length;
        const activeCount = [
            historyFilters.query.trim(),
            historyFilters.type !== 'all',
            historyFilters.channel !== 'all',
            historyFilters.device !== 'all',
            historyFilters.status !== 'all',
            historyFilters.sourceApp !== 'all',
            historyFilters.tag.trim(),
            historyFilters.category !== 'all'
        ].filter(Boolean).length;
        historyFilterSummary.textContent = activeCount
            ? `已筛选 ${filteredCount} / ${total} 条`
            : `共 ${total} 条`;
        if (exportFilteredHistoryBtn) {
            exportFilteredHistoryBtn.disabled = filteredCount === 0;
        }
        if (exportFilteredHistoryTxtBtn) {
            exportFilteredHistoryTxtBtn.disabled = filteredCount === 0;
        }
        if (exportFilteredHistoryMdBtn) {
            exportFilteredHistoryMdBtn.disabled = filteredCount === 0;
        }
        if (exportFilteredHistoryWordBtn) {
            exportFilteredHistoryWordBtn.disabled = filteredCount === 0;
        }
    }

    function syncHistorySelectionUi() {
        if (toggleHistorySelectionBtn) {
            toggleHistorySelectionBtn.textContent = historySelectionMode ? '退出多选' : '多选';
            toggleHistorySelectionBtn.classList.toggle('active', historySelectionMode);
        }
        if (deleteSelectedHistoryBtn) {
            deleteSelectedHistoryBtn.style.display = historySelectionMode ? 'inline-flex' : 'none';
            deleteSelectedHistoryBtn.disabled = selectedHistoryIds.size === 0;
            deleteSelectedHistoryBtn.textContent = selectedHistoryIds.size
                ? `删除选中 (${selectedHistoryIds.size})`
                : '删除选中';
        }
        [
            favoriteSelectedHistoryBtn,
            unfavoriteSelectedHistoryBtn,
            pinSelectedHistoryBtn,
            unpinSelectedHistoryBtn,
            exportSelectedHistoryCsvBtn,
            exportSelectedHistoryTxtBtn,
            exportSelectedHistoryMdBtn,
            exportSelectedHistoryBtn
        ].forEach(button => {
            if (!button) return;
            button.style.display = historySelectionMode ? 'inline-flex' : 'none';
            button.disabled = selectedHistoryIds.size === 0;
        });
    }

    function setHistorySelectionMode(enabled) {
        historySelectionMode = enabled;
        if (!enabled) {
            selectedHistoryIds.clear();
        }
        syncHistorySelectionUi();
        renderHistory({ mode: 'update' });
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
                record = await invoke('create_desktop_text_record', {
                    content,
                    category: selectedHistoryCategory
                });
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

        const recordTypeLabel = getHistoryTypeLabel(record.content_type);
        const shouldDelete = await confirmDialog({
            title: '删除记录',
            message: `确定删除这条${recordTypeLabel}记录吗？`,
            okText: '删除'
        });
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

    async function toggleHistoryFavorite(recordId) {
        const record = findHistoryRecordById(recordId);
        if (!record) return;

        try {
            const updated = await invoke('set_message_history_favorite', {
                id: recordId,
                favorite: !record.favorite
            });
            if (updated) {
                addOrUpdateHistoryRecord(updated);
            }
            setDesktopTextStatus(updated?.favorite ? '已收藏记录。' : '已取消收藏。', 'success');
        } catch (error) {
            console.error('切换收藏失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '操作失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function toggleHistoryPinned(recordId) {
        const record = findHistoryRecordById(recordId);
        if (!record) return;

        try {
            const updated = await invoke('set_message_history_pinned', {
                id: recordId,
                pinned: !record.pinned
            });
            if (updated) {
                addOrUpdateHistoryRecord(updated);
            }
            setDesktopTextStatus(updated?.pinned ? '已置顶记录。' : '已取消置顶。', 'success');
        } catch (error) {
            console.error('切换置顶失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '操作失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    function setHistoryTagsEditorVisible(recordId, visible) {
        const editor = Array.from(historyList.querySelectorAll('[data-tags-editor-for]'))
            .find(item => item.dataset.tagsEditorFor === recordId);
        if (!editor) return;
        editor.style.display = visible ? 'flex' : 'none';
        if (visible) {
            editor.querySelector('.history-tags-input')?.focus();
        }
    }

    async function saveHistoryTags(recordId) {
        const editor = Array.from(historyList.querySelectorAll('[data-tags-editor-for]'))
            .find(item => item.dataset.tagsEditorFor === recordId);
        const input = editor?.querySelector('.history-tags-input');
        if (!input) return;

        try {
            const updated = await invoke('set_message_history_tags', {
                id: recordId,
                tags: input.value || ''
            });
            if (updated) {
                addOrUpdateHistoryRecord(updated);
            }
            setDesktopTextStatus('标签已保存。', 'success');
        } catch (error) {
            console.error('保存标签失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存标签失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function deleteSelectedHistoryRecords() {
        const ids = Array.from(selectedHistoryIds);
        if (!ids.length) return;

        const shouldDelete = await confirmDialog({
            title: '删除选中记录',
            message: `确定删除选中的 ${ids.length} 条记录吗？`,
            okText: '删除'
        });
        if (!shouldDelete) return;

        try {
            const deletedCount = await invoke('delete_message_history_records_by_ids', { ids });

            historyRecords = historyRecords.filter(item => !selectedHistoryIds.has(item.id));
            selectedHistoryIds.clear();
            refreshHistoryCursor();
            syncHistorySelectionUi();
            renderHistory({ mode: 'update' });
            setDesktopTextStatus(`已删除 ${deletedCount} 条记录。`, 'success');
        } catch (error) {
            console.error('批量删除历史记录失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '批量删除失败';
            setDesktopTextStatus(message, 'error');
            await loadHistoryPage({ reset: true });
        }
    }

    async function setSelectedHistoryFavorite(favorite) {
        const ids = Array.from(selectedHistoryIds);
        if (!ids.length) return;

        try {
            const changedCount = await invoke('set_message_history_favorite_by_ids', { ids, favorite });
            await loadHistoryPage({ reset: true });
            setDesktopTextStatus(
                favorite ? `已收藏 ${changedCount} 条记录。` : `已取消收藏 ${changedCount} 条记录。`,
                'success'
            );
        } catch (error) {
            console.error('批量收藏操作失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '批量收藏操作失败';
            setDesktopTextStatus(message, 'error');
            await loadHistoryPage({ reset: true });
        } finally {
            syncHistorySelectionUi();
        }
    }

    async function setSelectedHistoryPinned(pinned) {
        const ids = Array.from(selectedHistoryIds);
        if (!ids.length) return;

        try {
            const changedCount = await invoke('set_message_history_pinned_by_ids', { ids, pinned });
            await loadHistoryPage({ reset: true });
            setDesktopTextStatus(
                pinned ? `已置顶 ${changedCount} 条记录。` : `已取消置顶 ${changedCount} 条记录。`,
                'success'
            );
        } catch (error) {
            console.error('批量置顶操作失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '批量置顶操作失败';
            setDesktopTextStatus(message, 'error');
            await loadHistoryPage({ reset: true });
        } finally {
            syncHistorySelectionUi();
        }
    }

    async function exportSelectedHistoryRecords(format = 'word') {
        const ids = Array.from(selectedHistoryIds);
        if (!ids.length) {
            setDesktopTextStatus('请先选择要导出的记录。', 'error');
            return;
        }

        try {
            const result = await invoke('export_message_history', {
                startAt: null,
                endAt: null,
                label: 'selected',
                format,
                ids,
                search: null,
                contentType: null,
                via: null,
                fromDevice: null,
                sourceApp: null,
                deliveryStatus: null,
                favorite: null,
                pinned: null,
                tag: null,
                category: null
            });
            const savedPath = result?.saved_path || result?.savedPath;
            const formatLabel = String(format || 'word').toUpperCase();
            setDesktopTextStatus(
                savedPath ? `已导出选中 ${formatLabel}：${savedPath}` : `已导出选中 ${formatLabel}。`,
                'success'
            );
            showExportCompletedToast(savedPath, `已导出选中 ${formatLabel}：${result?.filename || '文件已生成'}`);
        } catch (error) {
            console.error('导出选中历史失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出选中失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    // ===== 鏈嶅姟鍣ㄧ姸鎬?=====
    function setServerDot(status) {
        serverStatus = status;
        serverDot.className = 'server-dot';
        if (status === 'connected') serverDot.classList.add('connected');
        else if (status === 'connecting') serverDot.classList.add('connecting');
        else if (status === 'error') serverDot.classList.add('error');

        if (status === 'connected' && !isPaired && qrPairingMode === 'relay') {
            loadQrCode();
        }
        if (status !== 'connected' && qrPairingMode === 'relay') {
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

        tauriEvent.listen('notification_received', (event) => {
            console.log('收到通知事件:', JSON.stringify(event));
            handleNotificationReceived(event.payload || {});
        });

        tauriEvent.listen('input_result', (event) => {
            const payload = event.payload || {};
            const modeLabel = inputModeLabel(payload.mode);
            const message = payload.message || '已处理接收文字。';
            setLastInputStatus(`最近插入：${modeLabel}，${message}`, payload.success === false ? 'error' : 'success');
        });

        tauriEvent.listen('relay_stored', (event) => {
            console.log('服务器已暂存消息:', JSON.stringify(event));
        });

        tauriEvent.listen('remote_ai_assistant_request', async (event) => {
            await handleRemoteAiAssistantRequest(event.payload || {});
        });

        tauriEvent.listen('remote_ai_assistant_cancel', async (event) => {
            await handleRemoteAiAssistantCancel(event.payload || {});
        });

        tauriEvent.listen('ai_assistant_delta', (event) => {
            const payload = event.payload || {};
            if (remoteAiTargets.has(payload.request_id)) {
                forwardRemoteAiDelta(payload.request_id, payload.delta || '');
            }
            if (!payload.request_id || payload.request_id !== currentAssistantRequestId) {
                return;
            }
            if (stoppedAssistantRequestIds.has(payload.request_id)) {
                return;
            }
            appendAiReportDelta(payload.delta || '');
            renderAiMessages({ streamingText: currentAiReportContent });
        });

        tauriEvent.listen('ai_assistant_event', (event) => {
            const payload = event.payload || {};
            forwardRemoteAiEvent(payload);
            appendAiToolEvent(payload);
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
            showToast('配对失败: ' + (data.message || '未知错误'), 'error', 5200);
        });

        console.log('event listeners registered');
    } else {
        console.error('无法获取 Tauri event API! window.__TAURI__:', typeof window.__TAURI__, 'window.__TAURI_INTERNALS__:', typeof window.__TAURI_INTERNALS__);
    }

    // ===== 2. 鍔犺浇閰嶇疆 =====
    try {
        await loadAppVersion();
        checkForUpdate({ silent: true });
        await refreshHistoryCategories();
        await loadHistory();
        const config = await invoke('get_config');
        console.log('配置已加载:', config);
        const hasPairedDevices = Array.isArray(config.paired_devices) && config.paired_devices.length > 0;
        if (!hasPairedDevices && !config.server_mode_enabled) {
            await invoke('set_server_mode', { enabled: true });
            config.server_mode_enabled = true;
        }
        serverModeToggle.checked = config.server_mode_enabled;
        serverUrlInput.value = config.server_url;
        setInputModeUi(config.input_mode || 'direct');
        const openaiConfig = config.openai || {};
        populateOpenAiConfig(openaiConfig);
        refreshAiConfigurationState(openaiConfig);
        setAiReportContent('', { resetAutoScroll: true });
        await loadAiSkills();
        await loadAiSessions({ selectLatest: true });
        if (aiConfigured) {
            setAiReportStatus('');
        } else {
            setAiReportStatus('请先填写 AI 助手模型配置。阿里云百炼兼容模式可直接填写 https://dashscope.aliyuncs.com/compatible-mode/v1', 'error');
        }
        switchWorkspaceTab('history');

        if (hasPairedDevices) {
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
    confirmCancelBtn?.addEventListener('click', () => closeConfirmDialog(false));
    confirmOkBtn?.addEventListener('click', () => closeConfirmDialog(true));
    confirmModal?.addEventListener('click', (event) => {
        if (event.target === confirmModal) {
            closeConfirmDialog(false);
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && confirmResolver) {
            closeConfirmDialog(false);
        }
        if (event.key === 'Escape') {
            setToolbarSettingsMenuVisible(false);
            setHistoryExportMenuVisible(false);
            setHistoryMoreMenuVisible(false);
        }
    });

    function setToolbarSettingsMenuVisible(visible) {
        toolbarSettingsMenuController.setVisible(visible);
    }

    function setHistoryExportMenuVisible(visible) {
        historyExportMenuController.setVisible(visible);
    }

    function setHistoryMoreMenuVisible(visible) {
        historyMoreMenuController.setVisible(visible);
    }

    toolbarSettingsBtn?.addEventListener('click', (event) => {
        event.stopPropagation();
        const visible = toolbarSettingsMenuController.isVisible();
        setToolbarSettingsMenuVisible(!visible);
        setHistoryExportMenuVisible(false);
    });

    openC2ReaderBtn?.addEventListener('click', async (event) => {
        debugC2Main('open-c2-reader button click handler entered');
        event.stopPropagation();
        setToolbarSettingsMenuVisible(false);
        setHistoryExportMenuVisible(false);
        setHistoryMoreMenuVisible(false);
        await openC2Reader();
    });

    historyExportMenuBtn?.addEventListener('click', (event) => {
        event.stopPropagation();
        const visible = historyExportMenuController.isVisible();
        setHistoryExportMenuVisible(!visible);
        setToolbarSettingsMenuVisible(false);
        setHistoryMoreMenuVisible(false);
    });

    historyMoreMenuBtn?.addEventListener('click', (event) => {
        event.stopPropagation();
        const visible = historyMoreMenuController.isVisible();
        setHistoryMoreMenuVisible(!visible);
        setHistoryExportMenuVisible(false);
        setToolbarSettingsMenuVisible(false);
    });

    toolbarSettingsMenu?.addEventListener('click', (event) => {
        event.stopPropagation();
    });

    historyExportMenu?.addEventListener('click', (event) => {
        event.stopPropagation();
    });

    historyMoreMenu?.addEventListener('click', (event) => {
        event.stopPropagation();
    });

    document.addEventListener('click', () => {
        setToolbarSettingsMenuVisible(false);
        setHistoryExportMenuVisible(false);
        setHistoryMoreMenuVisible(false);
    });

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

    toggleAiPanelBtn?.addEventListener('click', () => {
        const panelOpen = aiReportPanel.style.display !== 'none';
        if (panelOpen) {
            toggleAiPanel(false);
            return;
        }
        toggleAiPanel(true, { revealSettings: !aiConfigured });
    });

    toggleAiSettingsBtn.addEventListener('click', () => {
        switchWorkspaceTab('assistant');
        const panelOpen = aiReportPanel.style.display !== 'none';
        const settingsOpen = aiConfigCard.style.display !== 'none';
        if (!panelOpen) {
            toggleAiPanel(true, { revealSettings: true });
            return;
        }
        setAiSettingsVisible(!settingsOpen);
    });

    workspaceTabs.forEach(button => {
        button.addEventListener('click', () => {
            switchWorkspaceTab(button.dataset.workspaceTab || 'history');
        });
    });

    refreshAiSkillsBtn?.addEventListener('click', async () => {
        await loadAiSkills();
        await loadAiSessions();
    });

    createAiSessionBtn?.addEventListener('click', async () => {
        await createAiSessionFromInput();
    });

    aiSuggestionList?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-ai-suggestion]');
        if (!button || !aiChatInput) return;
        aiChatInput.value = button.dataset.aiSuggestion || '';
        aiChatInput.focus();
        aiChatInput.setSelectionRange(aiChatInput.value.length, aiChatInput.value.length);
    });

    stopAiSessionBtn?.addEventListener('click', async () => {
        if (!currentAssistantRequestId) return;
        const requestId = currentAssistantRequestId;
        stoppedAssistantRequestIds.add(requestId);
        currentAssistantRequestId = null;
        createAiSessionBtn.disabled = false;
        stopAiSessionBtn.style.display = 'none';
        setAiSessionStatus('正在停止本次生成...');
        try {
            await invoke('cancel_ai_assistant_request', { requestId });
            setAiSessionStatus('已停止本次生成。');
        } catch (error) {
            console.error('停止 AI 生成失败:', error);
            setAiSessionStatus('已停止显示本次生成，后端取消失败。', 'error');
        }
    });

    newAiSessionBtn?.addEventListener('click', () => {
        resetAiConversationDraft();
    });

    renameAiSessionBtn?.addEventListener('click', async () => {
        const session = aiSessions.find(item => item.id === currentAiSessionId);
        if (!session) return;
        const title = window.prompt('输入新的会话名称', session.title || '新会话');
        if (title === null) return;
        if (!title.trim()) {
            setAiSessionStatus('会话名称不能为空。', 'error');
            return;
        }
        try {
            await invoke('rename_ai_session', { sessionId: session.id, title: title.trim() });
            await loadAiSessions();
            setAiSessionStatus('会话已重命名。', 'success');
        } catch (error) {
            console.error('重命名 AI 会话失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '重命名失败';
            setAiSessionStatus(message, 'error');
        }
    });

    deleteAiSessionBtn?.addEventListener('click', async () => {
        const session = aiSessions.find(item => item.id === currentAiSessionId);
        if (!session) return;
        const confirmed = await confirmDialog({
            title: '删除 AI 会话',
            message: `确定删除“${session.title || '新会话'}”吗？会话中的消息和工具调用记录也会一并删除。`,
            okText: '删除'
        });
        if (!confirmed) return;
        try {
            await invoke('delete_ai_session', { sessionId: session.id });
            currentAiSessionId = null;
            aiMessages = [];
            aiToolCalls = [];
            aiExportedFiles = [];
            clearAiErrorDetails();
            await loadAiSessions({ selectLatest: true });
            setAiSessionStatus('会话已删除。', 'success');
        } catch (error) {
            console.error('删除 AI 会话失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '删除失败';
            setAiSessionStatus(message, 'error');
        }
    });

    copyAiErrorBtn?.addEventListener('click', async () => {
        const content = aiErrorContent?.textContent || '';
        if (!content) return;
        try {
            await invoke('copy_text_to_clipboard', { content });
            setAiSessionStatus('错误详情已复制。', 'success');
        } catch (error) {
            console.error('复制 AI 错误详情失败:', error);
            setAiSessionStatus('复制错误详情失败。', 'error');
        }
    });

    exportAiSessionWordBtn?.addEventListener('click', async () => {
        if (!currentAiSessionId) {
            setAiSessionStatus('当前没有可导出的会话。', 'error');
            return;
        }
        try {
            const result = await invoke('export_ai_session_word', { sessionId: currentAiSessionId });
            const savedPath = result.saved_path || result.savedPath;
            setAiSessionStatus(`会话已导出：${savedPath || result.filename}`, 'success');
            showExportCompletedToast(savedPath, `会话已导出：${result.filename || '文件已生成'}`);
            await loadAiSessionDetail(currentAiSessionId);
        } catch (error) {
            console.error('导出 AI 会话失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出失败';
            setAiSessionStatus(message, 'error');
        }
    });

    saveAiSkillBtn?.addEventListener('click', async () => {
        await saveSelectedAiSkill();
    });

    exportAiSkillsBtn?.addEventListener('click', async () => {
        await exportAiSkills();
    });

    importAiSkillsBtn?.addEventListener('click', () => {
        setAiSkillImportBoxVisible(true);
    });

    confirmImportAiSkillsBtn?.addEventListener('click', async () => {
        await importAiSkillsFromText();
    });

    cancelImportAiSkillsBtn?.addEventListener('click', () => {
        setAiSkillImportBoxVisible(false);
    });

    aiSkillsList?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-skill-id]');
        if (!button) return;
        selectedAiSkillId = button.dataset.skillId;
        populateSelectedSkillEditor();
    });

    aiReferenceList?.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-ai-reference-action]');
        if (!button) return;
        await handleAiReferenceAction(button.dataset.aiReferenceAction, button.dataset.recordId);
    });

    aiExportedFileList?.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-ai-export-action]');
        if (!button) return;
        const path = button.dataset.path;
        if (!path) return;
        try {
            if (button.dataset.aiExportAction === 'folder') {
                await invoke('open_parent_folder', { path });
            } else {
                await invoke('open_path', { path });
            }
        } catch (error) {
            console.error('打开 AI 导出文件失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '打开导出文件失败';
            setAiSessionStatus(message, 'error');
            showToast(message, 'error', 5200);
        }
    });

    aiSideTabs.forEach(button => {
        button.addEventListener('click', () => {
            setAiSideTab(button.dataset.aiSideTab || 'tools');
        });
    });

    aiSessionList?.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-session-id]');
        if (!button) return;
        await loadAiSessionDetail(button.dataset.sessionId);
    });

    aiChatInput?.addEventListener('keydown', async (event) => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            await createAiSessionFromInput();
        }
    });

    aiChatMessages?.addEventListener('scroll', () => {
        if (aiChatIgnoreScrollEvent) return;
        aiChatShouldAutoScroll = isAiChatNearBottom();
    });

    aiChatMessages?.addEventListener('input', event => {
        const textarea = event.target.closest('.ai-message-editor');
        if (textarea) resizeAiMessageEditor(textarea);
    });

    aiChatMessages?.addEventListener('keydown', async event => {
        const textarea = event.target.closest('.ai-message-editor');
        if (!textarea) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            cancelAiMessageEdit();
            return;
        }
        if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            await handleAiMessageEditAction('save', textarea.dataset.messageId);
        }
    });

    aiChatMessages?.addEventListener('click', async (event) => {
        const editButton = event.target.closest('[data-ai-message-edit-action]');
        if (editButton) {
            event.stopPropagation();
            await handleAiMessageEditAction(
                editButton.dataset.aiMessageEditAction,
                editButton.dataset.messageId
            );
            return;
        }
        const button = event.target.closest('[data-ai-message-action]');
        if (!button) return;
        event.stopPropagation();
        await handleAiMessageAction(button.dataset.aiMessageAction, button.dataset.messageId, button);
    });

    inputModeButtons.forEach(button => {
        button.addEventListener('click', async () => {
            const mode = button.dataset.inputMode || 'direct';
            try {
                await invoke('set_input_mode', { mode });
                setInputModeUi(mode);
                setLastInputStatus(`最近插入：已切换为${inputModeLabel(mode)}模式。`);
            } catch (error) {
                console.error('保存接收文字处理方式失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '保存失败';
                setLastInputStatus(`最近插入：${message}`, 'error');
            }
        });
    });

    saveOpenAiConfigBtn?.addEventListener('click', async () => {
        try {
            await saveOpenAiConfig();
        } catch (error) {
            console.error('保存 AI 配置失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '保存失败';
            setAiReportStatus(message, 'error');
            showToast(`保存 AI 配置失败：${message}`, 'error', 5200);
        }
    });

    aiReportOutput?.addEventListener('scroll', () => {
        if (aiReportIgnoreScrollEvent) return;
        aiReportShouldAutoScroll = isAiReportNearBottom();
    });

    [
        openaiApiKeyInput,
        openaiApiUrlInput,
        openaiModelNameInput
    ]
        .forEach((element) => {
            element?.addEventListener('input', () => {
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
        updateQrModeUi();
        if (qrPairingMode === 'relay' && serverStatus !== 'connected') {
            qrArea.style.display = 'none';
            const url = serverUrlInput.value.trim();
            if (serverModeToggle.checked && url) {
                doConnectServer(url);
            }
            return;
        }
        try {
            const dataUrl = await invoke('generate_pairing_qr', { mode: qrPairingMode });
            qrCodeImg.src = dataUrl;
            qrArea.style.display = 'flex';
        } catch (e) {
            console.error('生成二维码失败:', e);
        }
    }
    refreshQrBtn.addEventListener('click', loadQrCode);
    toggleQrModeBtn?.addEventListener('click', () => {
        qrPairingMode = qrPairingMode === 'relay' ? 'lan' : 'relay';
        loadQrCode();
    });

    checkUpdateBtn.addEventListener('click', async () => {
        await checkForUpdate();
    });

    installUpdateBtn.addEventListener('click', async () => {
        await downloadAndOpenUpdate();
    });

    // ===== 鍙栨秷閰嶅 =====
    unpairBtn.addEventListener('click', async () => {
        if (!connectedDeviceId) return;
        const shouldUnpair = await confirmDialog({
            title: '取消配对',
            message: '确定取消当前手机与电脑的配对吗？取消后需要重新扫码配对。',
            okText: '取消配对'
        });
        if (!shouldUnpair) return;

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
            showToast('已取消配对。', 'success');
        } catch (e) {
            console.error('取消配对失败:', e);
            showToast('取消配对失败。', 'error');
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
                beforeId: reset ? null : historyCursor?.id ?? null,
                search: normalizedHistoryFilterValue(historyFilters.query),
                contentType: currentHistoryContentTypeFilter(),
                via: normalizedHistoryFilterValue(historyFilters.channel),
                fromDevice: normalizedHistoryFilterValue(historyFilters.device),
                sourceApp: normalizedHistoryFilterValue(historyFilters.sourceApp),
                deliveryStatus: currentHistoryDeliveryStatusFilter(),
                favorite: currentHistoryFavoriteFilter(),
                pinned: currentHistoryPinnedFilter(),
                tag: normalizedHistoryFilterValue(historyFilters.tag),
                category: normalizedHistoryFilterValue(historyFilters.category)
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
        historyPagination.style.display = 'none';

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

    async function exportHistory(startAt, endAt, label) {
        const notificationTab = activeWorkspaceTab === 'notifications';
        const contentType = notificationTab ? 'notification' : 'text,image,file';
        try {
            const result = await invoke('export_message_history', {
                startAt,
                endAt,
                label,
                format: 'csv',
                ids: null,
                search: null,
                contentType,
                via: null,
                fromDevice: null,
                sourceApp: null,
                deliveryStatus: null,
                favorite: null,
                pinned: null,
                tag: null,
                category: notificationTab ? null : normalizedHistoryFilterValue(historyFilters.category)
            });
            const savedPath = result?.saved_path || result?.savedPath;
            setDesktopTextStatus(
                notificationTab ? '通知周期导出完成。' : '历史周期导出完成。',
                'success'
            );
            showExportCompletedToast(savedPath, `导出完成：${result?.filename || '文件已生成'}`);
        } catch (error) {
            console.error('导出历史记录失败:', error);
            showToast('导出失败，请查看控制台日志', 'error', 5200);
        }
    }

    function exportFilteredHistoryRecords(format = 'csv') {
        const records = getFilteredHistoryRecords();
        if (!records.length) {
            setDesktopTextStatus('当前筛选结果为空，无法导出。', 'error');
            return;
        }

        exportHistoryWithFilters({
            label: 'filtered',
            format,
            search: normalizedHistoryFilterValue(historyFilters.query),
            contentType: currentHistoryContentTypeFilter(),
            via: normalizedHistoryFilterValue(historyFilters.channel),
            fromDevice: normalizedHistoryFilterValue(historyFilters.device),
            sourceApp: normalizedHistoryFilterValue(historyFilters.sourceApp),
            deliveryStatus: currentHistoryDeliveryStatusFilter(),
            favorite: currentHistoryFavoriteFilter(),
            pinned: currentHistoryPinnedFilter(),
            tag: normalizedHistoryFilterValue(historyFilters.tag),
            category: normalizedHistoryFilterValue(historyFilters.category)
        });
    }

    async function exportHistoryWithFilters({ label, format = 'csv', search, contentType, via, fromDevice, sourceApp, deliveryStatus, favorite, pinned, tag, category }) {
        try {
            const result = await invoke('export_message_history', {
                startAt: null,
                endAt: null,
                label,
                format,
                ids: null,
                search,
                contentType,
                via,
                fromDevice,
                sourceApp,
                deliveryStatus,
                favorite,
                pinned,
                tag,
                category
            });
            const savedPath = result?.saved_path || result?.savedPath;
            const formatLabel = String(format || 'csv').toUpperCase();
            setDesktopTextStatus(
                savedPath ? `已导出当前筛选 ${formatLabel}：${savedPath}` : `已导出当前筛选 ${formatLabel}。`,
                'success'
            );
            showExportCompletedToast(savedPath, `已导出当前筛选 ${formatLabel}：${result?.filename || '文件已生成'}`);
        } catch (error) {
            console.error('导出筛选历史失败:', error);
            const message = typeof error === 'string' ? error : error?.message || '导出失败';
            setDesktopTextStatus(message, 'error');
        }
    }

    async function deleteRecordsByContentType(contentType) {
        return invoke('delete_message_history_records', {
            startAt: null,
            endAt: null,
            search: null,
            contentType,
            via: null,
            fromDevice: null,
            sourceApp: null,
            deliveryStatus: null,
            favorite: null,
            pinned: null,
            tag: null,
            category: null
        });
    }

    function renderHistory({ mode = 'reset', scrollSnapshot = captureHistoryScrollSnapshot(), forceScrollTop = false } = {}) {
        updateHistoryFilterOptions();
        const filteredRecords = getFilteredHistoryRecords();
        updateHistoryFilterSummary(filteredRecords.length);
        const emptyText = activeWorkspaceTab === 'notifications'
            ? '暂无通知记录。开启 App 通知转发后，微信等通知会出现在这里。'
            : '等待手机发送文字，或在上方直接新增...';
        const noMatchText = activeWorkspaceTab === 'notifications'
            ? '没有匹配的通知，试试调整搜索或筛选条件。'
            : '没有匹配的记录，试试调整搜索或筛选条件。';

        if (!historyRecords.length) {
            historyList.innerHTML = `<div class="history-empty" id="history-empty">${escapeHtml(emptyText)}</div>`;
            historyList.scrollTop = 0;
            updateHistoryPagination();
            return;
        }

        if (!filteredRecords.length) {
            historyList.innerHTML = `<div class="history-empty" id="history-empty">${escapeHtml(noMatchText)}</div>`;
            historyList.scrollTop = 0;
            updateHistoryPagination();
            return;
        }

        const renderItem = (record) => renderHistoryItem(record, {
            selectionMode: historySelectionMode,
            selectedIds: selectedHistoryIds
        });
        historyList.innerHTML = activeWorkspaceTab === 'notifications'
            ? renderNotificationTimeline(filteredRecords, renderItem)
            : filteredRecords.map(renderItem).join('');
        restoreHistoryScroll(mode, scrollSnapshot, forceScrollTop);
        updateHistoryPagination();
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

        if (action === 'select') {
            if (actionButton.checked) {
                selectedHistoryIds.add(recordId);
            } else {
                selectedHistoryIds.delete(recordId);
            }
            syncHistorySelectionUi();
            return;
        }

        if (action === 'copy') {
            await copyHistoryRecord(recordId);
            return;
        }

        if (action === 'favorite') {
            await toggleHistoryFavorite(recordId);
            return;
        }

        if (action === 'pin') {
            await toggleHistoryPinned(recordId);
            return;
        }

        if (action === 'edit-tags') {
            setHistoryTagsEditorVisible(recordId, true);
            return;
        }

        if (action === 'save-tags') {
            await saveHistoryTags(recordId);
            return;
        }

        if (action === 'cancel-tags') {
            setHistoryTagsEditorVisible(recordId, false);
            return;
        }

        if (action === 'edit') {
            startEditingHistoryRecord(recordId);
            return;
        }

        if (action === 'insert') {
            const record = findHistoryRecordById(recordId);
            if (!record?.content) return;
            try {
                await invoke('insert_text_to_cursor', { content: record.content });
                setDesktopTextStatus('记录已插入到当前光标位置。', 'success');
                setLastInputStatus('最近插入：历史记录已直接插入。', 'success');
            } catch (error) {
                console.error('插入历史记录失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '插入失败';
                setDesktopTextStatus(message, 'error');
                setLastInputStatus(`最近插入：${message}`, 'error');
            }
            return;
        }

        if (action === 'open-file') {
            try {
                await invoke('open_history_record_file', { id: recordId });
                setDesktopTextStatus('已打开文件。', 'success');
            } catch (error) {
                console.error('打开文件失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '打开文件失败';
                setDesktopTextStatus(message, 'error');
                showToast(message, 'error', 5200);
            }
            return;
        }

        if (action === 'open-folder') {
            try {
                await invoke('open_history_record_folder', { id: recordId });
                setDesktopTextStatus('已打开目录。', 'success');
            } catch (error) {
                console.error('打开目录失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '打开目录失败';
                setDesktopTextStatus(message, 'error');
                showToast(message, 'error', 5200);
            }
            return;
        }

        if (action === 'save-image') {
            try {
                const savedPath = await invoke('save_history_image_as', { id: recordId });
                setDesktopTextStatus(`图片已保存：${savedPath}`, 'success');
            } catch (error) {
                console.error('保存图片失败:', error);
                const message = typeof error === 'string' ? error : error?.message || '保存图片失败';
                setDesktopTextStatus(message, 'error');
                showToast(message, 'error', 5200);
            }
            return;
        }

        if (action === 'delete') {
            await deleteHistoryRecord(recordId);
        }
    });

    syncDesktopEditorUi();
    setDesktopTextStatus('可在电脑端新增、复制、修改、删除文字记录。');

    clearHistoryBtn.addEventListener('click', async () => {
        const dialog = buildClearHistoryDialog(activeWorkspaceTab);
        const shouldClear = await confirmDialog({
            title: dialog.title,
            message: dialog.message,
            okText: '清空'
        });
        if (!shouldClear) return;

        try {
            let deletedCount = 0;
            for (const contentType of workspaceContentTypes(activeWorkspaceTab)) {
                deletedCount += await deleteRecordsByContentType(contentType);
            }
            await loadHistoryPage({ reset: true });
            showToast(dialog.successMessage(deletedCount), 'success');
            setHistoryMoreMenuVisible(false);
        } catch (error) {
            console.error('清空记录失败:', error);
            showToast(dialog.errorMessage, 'error');
        }
    });

    openaiProviderSelect?.addEventListener('change', () => {
        const configured = applyOpenAiProviderPreset(
            collectOpenAiConfigFromForm(),
            openaiProviderSelect.value
        );
        if (openaiApiUrlInput) openaiApiUrlInput.value = configured.api_url;
        if (openaiModelNameInput) openaiModelNameInput.value = configured.model_name;
        refreshAiConfigurationState(configured);
    });

    [
        historySearchInput,
        historyTypeFilter,
        historyChannelFilter,
        historyDeviceFilter,
        historyStatusFilter,
        historySourceAppFilter,
        historyTagFilter,
        historyCategoryFilter
    ].forEach(control => {
        if (!control) return;
        control.addEventListener('input', () => {
            syncHistoryFilterStateFromControls();
            loadHistoryPage({ reset: true });
        });
        control.addEventListener('change', () => {
            syncHistoryFilterStateFromControls();
            loadHistoryPage({ reset: true });
        });
    });

    resetHistoryFiltersBtn?.addEventListener('click', () => {
        resetHistoryFilters();
    });

    exportFilteredHistoryBtn?.addEventListener('click', () => {
        exportFilteredHistoryRecords('csv');
    });

    exportFilteredHistoryTxtBtn?.addEventListener('click', () => {
        exportFilteredHistoryRecords('txt');
    });

    exportFilteredHistoryMdBtn?.addEventListener('click', () => {
        exportFilteredHistoryRecords('md');
    });

    exportFilteredHistoryWordBtn?.addEventListener('click', () => {
        exportFilteredHistoryRecords('word');
    });

    toggleHistorySelectionBtn?.addEventListener('click', () => {
        setHistorySelectionMode(!historySelectionMode);
    });

    favoriteSelectedHistoryBtn?.addEventListener('click', async () => {
        await setSelectedHistoryFavorite(true);
    });

    unfavoriteSelectedHistoryBtn?.addEventListener('click', async () => {
        await setSelectedHistoryFavorite(false);
    });

    pinSelectedHistoryBtn?.addEventListener('click', async () => {
        await setSelectedHistoryPinned(true);
    });

    unpinSelectedHistoryBtn?.addEventListener('click', async () => {
        await setSelectedHistoryPinned(false);
    });

    exportSelectedHistoryCsvBtn?.addEventListener('click', async () => {
        await exportSelectedHistoryRecords('csv');
    });

    exportSelectedHistoryTxtBtn?.addEventListener('click', async () => {
        await exportSelectedHistoryRecords('txt');
    });

    exportSelectedHistoryMdBtn?.addEventListener('click', async () => {
        await exportSelectedHistoryRecords('md');
    });

    exportSelectedHistoryBtn?.addEventListener('click', async () => {
        await exportSelectedHistoryRecords('word');
    });

    deleteSelectedHistoryBtn?.addEventListener('click', async () => {
        await deleteSelectedHistoryRecords();
    });

    desktopCategorySelect?.addEventListener('change', () => {
        selectedHistoryCategory = desktopCategorySelect.value || DEFAULT_HISTORY_CATEGORY;
        if (historyCategoryFilter && activeWorkspaceTab === 'history') {
            historyCategoryFilter.value = selectedHistoryCategory;
            syncHistoryFilterStateFromControls();
            loadHistoryPage({ reset: true });
        }
        syncDesktopCategoryOptions();
    });

    addDesktopCategoryBtn?.addEventListener('click', async () => {
        await createHistoryCategory();
    });

    renameDesktopCategoryBtn?.addEventListener('click', async () => {
        await renameHistoryCategory();
    });

    deleteDesktopCategoryBtn?.addEventListener('click', async () => {
        await deleteHistoryCategory();
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

    exportWeekBtn?.addEventListener('click', async () => {
        const start = getStartOfWeek();
        await exportHistory(start.getTime(), Date.now(), 'week');
        setHistoryExportMenuVisible(false);
    });

    exportMonthBtn?.addEventListener('click', async () => {
        const start = getStartOfMonth();
        await exportHistory(start.getTime(), Date.now(), 'month');
        setHistoryExportMenuVisible(false);
    });

    exportQuarterBtn?.addEventListener('click', async () => {
        const start = getStartOfQuarter();
        await exportHistory(start.getTime(), Date.now(), 'quarter');
        setHistoryExportMenuVisible(false);
    });

    exportHalfYearBtn?.addEventListener('click', async () => {
        const start = getStartOfHalfYear();
        await exportHistory(start.getTime(), Date.now(), 'half-year');
        setHistoryExportMenuVisible(false);
    });

    exportYearBtn?.addEventListener('click', async () => {
        const start = getStartOfYear();
        await exportHistory(start.getTime(), Date.now(), 'year');
        setHistoryExportMenuVisible(false);
    });

    copyAiReportBtn?.addEventListener('click', async () => {
        await copyAiReport();
    });

    copyAiReportInlineBtn?.addEventListener('click', async () => {
        await copyAiReport();
    });

    exportRangeBtn?.addEventListener('click', async () => {
        if (!exportStartDate.value || !exportEndDate.value) {
            showToast('请选择开始和结束日期', 'error');
            return;
        }

        const startAt = toRangeStart(exportStartDate.value);
        const endAt = toRangeEnd(exportEndDate.value);
        if (startAt > endAt) {
            showToast('开始日期不能晚于结束日期', 'error');
            return;
        }

        await exportHistory(startAt, endAt, `${exportStartDate.value}_to_${exportEndDate.value}`);
        setHistoryExportMenuVisible(false);
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
