import { escapeHtml, formatDateTime } from './ui-state.js';

export function compareHistoryRecord(a, b) {
    const pinnedDiff = Number(Boolean(b.pinned)) - Number(Boolean(a.pinned));
    if (pinnedDiff !== 0) return pinnedDiff;
    const favoriteDiff = Number(Boolean(b.favorite)) - Number(Boolean(a.favorite));
    if (favoriteDiff !== 0) return favoriteDiff;
    const timeDiff = (b.received_at || 0) - (a.received_at || 0);
    if (timeDiff !== 0) return timeDiff;
    return String(b.id || '').localeCompare(String(a.id || ''));
}

export function parseRecordTags(value) {
    return String(value || '')
        .split(',')
        .map(tag => tag.trim())
        .filter(Boolean);
}

export function normalizeFilterText(value) {
    return String(value || '').trim().toLowerCase();
}

export function normalizedHistoryFilterValue(value) {
    const normalized = String(value || '').trim();
    return normalized && normalized !== 'all' ? normalized : null;
}

export function currentHistoryDeliveryStatusFilter(filters = {}) {
    if (filters.status === 'favorite' || filters.status === 'pinned') return null;
    return normalizedHistoryFilterValue(filters.status);
}

export function currentHistoryFavoriteFilter(filters = {}) {
    return filters.status === 'favorite' ? true : null;
}

export function currentHistoryPinnedFilter(filters = {}) {
    return filters.status === 'pinned' ? true : null;
}

export function workspaceContentTypeScope(tab = 'history') {
    return tab === 'notifications' ? 'notification' : 'text,image,file';
}

export function workspaceContentTypes(tab = 'history') {
    return workspaceContentTypeScope(tab).split(',').filter(Boolean);
}

export function buildClearHistoryDialog(tab = 'history') {
    if (tab === 'notifications') {
        return {
            title: '清空通知记录',
            message: '确定清空全部通知记录吗？文本、图片和文件历史会保留。',
            successMessage: count => `已清空 ${count} 条通知记录。`,
            errorMessage: '清空通知记录失败。'
        };
    }
    return {
        title: '清空历史记录',
        message: '确定清空全部历史记录吗？通知记录会保留。',
        successMessage: count => `已清空 ${count} 条历史记录。`,
        errorMessage: '清空历史记录失败。'
    };
}

export function currentHistoryContentTypeFilter(filters = {}, tab = 'history') {
    if (tab === 'notifications') {
        return 'notification';
    }
    if (tab === 'history') {
        return normalizedHistoryFilterValue(filters.type) || workspaceContentTypeScope(tab);
    }
    return normalizedHistoryFilterValue(filters.type);
}

export function recordDeviceKey(record = {}) {
    return record.from_device_name || record.from_device_id || '未知设备';
}

export function parseRecordMetadata(record = {}) {
    if (!record?.metadata) return null;
    try {
        return JSON.parse(record.metadata);
    } catch (_) {
        return null;
    }
}

export function recordSourceAppKey(record = {}) {
    if (record.content_type !== 'notification') return '';
    const metadata = parseRecordMetadata(record);
    return metadata?.app_name || metadata?.app_package || '';
}

export function recordStatusKey(record = {}) {
    if (record.delivery_mode === 'manual') return 'manual';
    if (record.delivery_mode === 'offline_sync') return 'offline_sync';
    return 'received';
}

export function filterHistoryRecords(records = [], filters = {}, tab = 'history') {
    const query = normalizeFilterText(filters.query);
    const tagQuery = normalizeFilterText(filters.tag);
    return records.filter(record => {
        if (tab === 'history' && record.content_type === 'notification') {
            return false;
        }

        if (tab === 'notifications' && record.content_type !== 'notification') {
            return false;
        }

        if (filters.type && filters.type !== 'all' && record.content_type !== filters.type) {
            return false;
        }

        if (filters.channel && filters.channel !== 'all' && record.via !== filters.channel) {
            return false;
        }

        if (filters.device && filters.device !== 'all' && recordDeviceKey(record) !== filters.device) {
            return false;
        }

        if (filters.status && filters.status !== 'all') {
            if (filters.status === 'favorite') {
                if (!record.favorite) return false;
            } else if (filters.status === 'pinned') {
                if (!record.pinned) return false;
            } else if (recordStatusKey(record) !== filters.status) {
                return false;
            }
        }

        if (tagQuery) {
            const tags = normalizeFilterText(record.tags);
            if (!tags.includes(tagQuery)) {
                return false;
            }
        }

        if (filters.sourceApp && filters.sourceApp !== 'all' && recordSourceAppKey(record) !== filters.sourceApp) {
            return false;
        }

        if (!query) return true;
        const metadata = parseRecordMetadata(record);
        return [
            record.content,
            record.from_device_name,
            record.from_device_id,
            record.via,
            record.delivery_mode,
            record.content_type,
            metadata?.app_name,
            metadata?.app_package,
            metadata?.title,
            metadata?.text,
            record.tags,
            record.metadata
        ].some(value => normalizeFilterText(value).includes(query));
    });
}

export function buildAiAssistantFilters(filters = {}, tab = 'history', limit = 120) {
    return {
        search: normalizedHistoryFilterValue(filters.query),
        content_type: currentHistoryContentTypeFilter(filters, tab),
        via: normalizedHistoryFilterValue(filters.channel),
        from_device: normalizedHistoryFilterValue(filters.device),
        source_app: normalizedHistoryFilterValue(filters.sourceApp),
        delivery_status: currentHistoryDeliveryStatusFilter(filters),
        favorite: currentHistoryFavoriteFilter(filters),
        pinned: currentHistoryPinnedFilter(filters),
        tag: normalizedHistoryFilterValue(filters.tag),
        limit
    };
}

export function buildAiAssistantScopeText(filters = {}, tab = 'history', labelFor = () => null) {
    const tabLabel = tab === 'notifications'
        ? '通知记录'
        : tab === 'history'
            ? '历史记录'
            : '当前筛选';
    const parts = [`范围=${tabLabel}`];
    if (filters.search) parts.push(`关键词=${filters.search}`);
    if (filters.content_type) parts.push(`类型=${labelFor('type', filters.content_type) || filters.content_type}`);
    if (filters.via) parts.push(`通道=${labelFor('channel', filters.via) || filters.via}`);
    if (filters.from_device) parts.push(`设备=${labelFor('device', filters.from_device) || filters.from_device}`);
    if (filters.delivery_status) parts.push(`状态=${labelFor('status', filters.delivery_status) || filters.delivery_status}`);
    if (filters.favorite === true) parts.push('状态=收藏');
    if (filters.pinned === true) parts.push('状态=置顶');
    if (filters.source_app) parts.push(`来源App=${labelFor('sourceApp', filters.source_app) || filters.source_app}`);
    if (filters.tag) parts.push(`标签=${filters.tag}`);
    parts.push(`上限=${filters.limit || 120}条`);
    return `将把当前筛选作为 AI 工具参考：${parts.join('，')}。LLM 会自主选择 Skill 和工具。`;
}

export function buildAiSessionScopeLabel(filters = {}) {
    if (!filters || typeof filters !== 'object') {
        return '范围：未记录';
    }
    const parts = [];
    if (filters.search) parts.push(`关键词=${filters.search}`);
    if (filters.content_type) parts.push(`类型=${filters.content_type}`);
    if (filters.via) parts.push(`通道=${filters.via}`);
    if (filters.from_device) parts.push(`设备=${filters.from_device}`);
    if (filters.delivery_status) parts.push(`状态=${filters.delivery_status}`);
    if (filters.favorite === true) parts.push('收藏');
    if (filters.pinned === true) parts.push('置顶');
    if (filters.source_app) parts.push(`来源App=${filters.source_app}`);
    if (filters.tag) parts.push(`标签=${filters.tag}`);
    if (Array.isArray(filters.record_ids) && filters.record_ids.length) {
        parts.push(`记录ID=${filters.record_ids.length}条`);
    }
    if (filters.limit) parts.push(`上限=${filters.limit}`);
    return parts.length ? `范围：${parts.join('，')}` : '范围：未记录';
}

export function getHistoryTypeLabel(contentType) {
    switch (contentType) {
        case 'image':
            return '图片';
        case 'file':
            return '文件';
        case 'notification':
            return '通知';
        default:
            return '文字';
    }
}

export function renderNotificationTimeline(records, renderItem) {
    const groups = [];
    records.forEach(record => {
        const timestamp = record.received_at || record.sent_at;
        const key = notificationDayKey(timestamp);
        let group = groups.find(item => item.key === key);
        if (!group) {
            group = {
                key,
                label: notificationDayLabel(timestamp),
                records: []
            };
            groups.push(group);
        }
        group.records.push(record);
    });

    return groups.map(group => `
        <section class="notification-day-group" data-notification-day="${escapeHtml(group.key)}">
            <div class="notification-day-header">
                <span>${escapeHtml(group.label)}</span>
                <span>${group.records.length} 条</span>
            </div>
            ${group.records.map(renderItem).join('')}
        </section>
    `).join('');
}

export function renderHistoryItem(record, { selectionMode = false, selectedIds = new Set() } = {}) {
    const sentAt = formatDateTime(record.sent_at);
    const receivedAt = formatDateTime(record.received_at);
    const typeLabel = getHistoryTypeLabel(record.content_type);
    const offlineBadge = record.delivery_mode === 'offline_sync'
        ? '<span class="history-badge offline">离线补发</span>'
        : '';
    const manualBadge = record.delivery_mode === 'manual'
        ? '<span class="history-badge">待手动插入</span>'
        : '';
    const favoriteBadge = record.favorite
        ? '<span class="history-badge favorite">收藏</span>'
        : '';
    const pinnedBadge = record.pinned
        ? '<span class="history-badge pinned">置顶</span>'
        : '';
    const tagBadges = parseRecordTags(record.tags)
        .map(tag => `<span class="history-badge tag">#${escapeHtml(tag)}</span>`)
        .join('');
    const typeBadge = `<span class="history-badge type">${escapeHtml(typeLabel)}</span>`;
    const itemClass = record.delivery_mode === 'offline_sync'
        ? 'history-item offline-sync'
        : 'history-item';
    const favoriteButton = `<button class="history-action-btn" data-action="favorite" data-record-id="${escapeHtml(record.id)}">${record.favorite ? '取消收藏' : '收藏'}</button>`;
    const pinnedButton = `<button class="history-action-btn" data-action="pin" data-record-id="${escapeHtml(record.id)}">${record.pinned ? '取消置顶' : '置顶'}</button>`;
    const tagsButton = `<button class="history-action-btn" data-action="edit-tags" data-record-id="${escapeHtml(record.id)}">标签</button>`;
    const selectionBox = selectionMode
        ? `<input class="history-select-box" type="checkbox" data-action="select" data-record-id="${escapeHtml(record.id)}" ${selectedIds.has(record.id) ? 'checked' : ''}>`
        : '';
    const editButton = record.content_type === 'text'
        ? `<button class="history-action-btn" data-action="edit" data-record-id="${escapeHtml(record.id)}">编辑</button>`
        : '';
    const insertButton = record.content_type === 'text'
        ? `<button class="history-action-btn" data-action="insert" data-record-id="${escapeHtml(record.id)}">插入</button>`
        : '';
    const copyButtonLabel = record.content_type === 'image' ? '复制图片' : '复制';
    const deleteButton = `<button class="history-action-btn danger" data-action="delete" data-record-id="${escapeHtml(record.id)}">删除</button>`;
    const fileButtons = record.content_type === 'file'
        ? `<button class="history-action-btn" data-action="open-file" data-record-id="${escapeHtml(record.id)}">打开文件</button>` +
          `<button class="history-action-btn" data-action="open-folder" data-record-id="${escapeHtml(record.id)}">打开目录</button>`
        : '';
    const imageSaveButton = record.content_type === 'image'
        ? `<button class="history-action-btn" data-action="save-image" data-record-id="${escapeHtml(record.id)}">另存为</button>`
        : '';

    return `
        <div class="${itemClass}" data-record-id="${escapeHtml(record.id)}">
            <div class="history-item-header">
                ${selectionBox}
                <div class="history-text">${escapeHtml(record.content)}</div>
                <div class="history-item-actions">
                    ${favoriteButton}
                    ${pinnedButton}
                    ${tagsButton}
                    <button class="history-action-btn" data-action="copy" data-record-id="${escapeHtml(record.id)}">${copyButtonLabel}</button>
                    ${insertButton}
                    ${editButton}
                    ${fileButtons}
                    ${imageSaveButton}
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
                ${typeBadge}
                ${offlineBadge}
                ${manualBadge}
                ${favoriteBadge}
                ${pinnedBadge}
                ${tagBadges}
            </div>
            <div class="history-tags-editor" data-tags-editor-for="${escapeHtml(record.id)}" style="display:none;">
                <input class="history-tags-input" type="text" value="${escapeHtml(record.tags || '')}" placeholder="标签用逗号分隔">
                <button class="history-action-btn" data-action="save-tags" data-record-id="${escapeHtml(record.id)}">保存标签</button>
                <button class="history-action-btn" data-action="cancel-tags" data-record-id="${escapeHtml(record.id)}">取消</button>
            </div>
        </div>
    `;
}

function notificationDayKey(timestamp) {
    if (!timestamp) return 'unknown';
    const date = new Date(timestamp);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function notificationDayLabel(timestamp) {
    if (!timestamp) return '未知日期';
    const dayStart = new Date(timestamp);
    dayStart.setHours(0, 0, 0, 0);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diffDays = Math.round((today.getTime() - dayStart.getTime()) / 86400000);
    const dateText = dayStart.toLocaleDateString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        weekday: 'short'
    });
    if (diffDays === 0) return `今天 ${dateText}`;
    if (diffDays === 1) return `昨天 ${dateText}`;
    return dateText;
}
