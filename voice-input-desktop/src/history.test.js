import test from 'node:test';
import assert from 'node:assert/strict';

import {
    buildAiAssistantFilters,
    buildAiAssistantScopeText,
    buildAiSessionScopeLabel,
    buildClearHistoryDialog,
    compareHistoryRecord,
    currentHistoryContentTypeFilter,
    currentHistoryDeliveryStatusFilter,
    currentHistoryFavoriteFilter,
    currentHistoryPinnedFilter,
    filterHistoryRecords,
    getHistoryTypeLabel,
    normalizedHistoryFilterValue,
    parseRecordTags,
    recordDeviceKey,
    recordSourceAppKey,
    recordStatusKey,
    renderHistoryItem,
    renderNotificationTimeline,
    workspaceContentTypeScope,
    workspaceContentTypes
} from './history.js';

test('history compare sorts pinned, favorite, time and id deterministically', () => {
    const records = [
        { id: 'b', received_at: 2, favorite: false, pinned: false },
        { id: 'a', received_at: 2, favorite: false, pinned: false },
        { id: 'fav', received_at: 1, favorite: true, pinned: false },
        { id: 'pin', received_at: 0, favorite: false, pinned: true }
    ];

    records.sort(compareHistoryRecord);

    assert.deepEqual(records.map(item => item.id), ['pin', 'fav', 'b', 'a']);
});

test('parseRecordTags trims empty entries and type labels are localized', () => {
    assert.deepEqual(parseRecordTags(' urgent, 项目 , ,#todo '), ['urgent', '项目', '#todo']);
    assert.equal(getHistoryTypeLabel('text'), '文字');
    assert.equal(getHistoryTypeLabel('image'), '图片');
    assert.equal(getHistoryTypeLabel('file'), '文件');
    assert.equal(getHistoryTypeLabel('notification'), '通知');
});

test('renderHistoryItem escapes content and shows productivity actions', () => {
    const html = renderHistoryItem({
        id: 'record-1',
        content: '<script>alert(1)</script>',
        content_type: 'text',
        sent_at: 1700000000000,
        received_at: 1700000001000,
        from_device_name: '手机',
        via: 'server',
        delivery_mode: 'manual',
        favorite: true,
        pinned: true,
        tags: '工作,待办'
    }, {
        selectionMode: true,
        selectedIds: new Set(['record-1'])
    });

    assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.match(html, /type="checkbox"[^>]+checked/);
    assert.match(html, /待手动插入/);
    assert.match(html, /收藏/);
    assert.match(html, /置顶/);
    assert.match(html, /#工作/);
    assert.match(html, /data-action="insert"/);
    assert.match(html, /data-action="edit"/);
});

test('renderNotificationTimeline groups notifications by day and keeps counts', () => {
    const records = [
        { id: 'n1', received_at: Date.UTC(2026, 0, 1, 1), content: 'A' },
        { id: 'n2', received_at: Date.UTC(2026, 0, 1, 2), content: 'B' },
        { id: 'n3', received_at: Date.UTC(2026, 0, 2, 1), content: 'C' }
    ];

    const html = renderNotificationTimeline(records, record => `<article>${record.id}</article>`);

    assert.match(html, /data-notification-day="2026-01-01"/);
    assert.match(html, /data-notification-day="2026-01-02"/);
    assert.match(html, /2 条/);
    assert.match(html, /1 条/);
});

test('history filter helpers normalize tab scope and status filters', () => {
    const filters = {
        query: ' 项目 ',
        type: 'all',
        channel: 'server',
        device: '手机',
        status: 'favorite',
        sourceApp: '微信',
        tag: ' 待办 '
    };

    assert.equal(normalizedHistoryFilterValue(' all '), null);
    assert.equal(normalizedHistoryFilterValue('all'), null);
    assert.equal(workspaceContentTypeScope('history'), 'text,image,file');
    assert.equal(workspaceContentTypeScope('notifications'), 'notification');
    assert.deepEqual(workspaceContentTypes('history'), ['text', 'image', 'file']);
    assert.deepEqual(workspaceContentTypes('notifications'), ['notification']);
    assert.equal(currentHistoryContentTypeFilter(filters, 'history'), 'text,image,file');
    assert.equal(currentHistoryContentTypeFilter({ type: 'image' }, 'history'), 'image');
    assert.equal(currentHistoryContentTypeFilter(filters, 'notifications'), 'notification');
    assert.equal(currentHistoryDeliveryStatusFilter(filters), null);
    assert.equal(currentHistoryFavoriteFilter(filters), true);
    assert.equal(currentHistoryPinnedFilter({ status: 'pinned' }), true);
});

test('clear history dialog keeps history and notification destructive scopes separated', () => {
    const historyDialog = buildClearHistoryDialog('history');
    const notificationDialog = buildClearHistoryDialog('notifications');

    assert.equal(historyDialog.title, '清空历史记录');
    assert.match(historyDialog.message, /通知记录会保留/);
    assert.equal(historyDialog.successMessage(3), '已清空 3 条历史记录。');
    assert.equal(historyDialog.errorMessage, '清空历史记录失败。');

    assert.equal(notificationDialog.title, '清空通知记录');
    assert.match(notificationDialog.message, /文本、图片和文件历史会保留/);
    assert.equal(notificationDialog.successMessage(2), '已清空 2 条通知记录。');
    assert.equal(notificationDialog.errorMessage, '清空通知记录失败。');
});

test('filterHistoryRecords separates history and notification tabs with metadata search', () => {
    const records = [
        {
            id: 'text-1',
            content_type: 'text',
            content: '项目记录',
            from_device_name: '手机',
            via: 'lan',
            delivery_mode: 'manual',
            favorite: true,
            pinned: false,
            tags: '工作'
        },
        {
            id: 'notification-1',
            content_type: 'notification',
            content: '通知正文',
            from_device_name: '手机',
            via: 'server',
            delivery_mode: 'received',
            favorite: false,
            pinned: true,
            tags: '待办',
            metadata: JSON.stringify({
                app_name: '微信',
                app_package: 'com.tencent.mm',
                title: '项目群',
                text: '下午三点同步'
            })
        },
        {
            id: 'file-1',
            content_type: 'file',
            content: '预算.csv',
            from_device_id: 'phone-2',
            via: 'server',
            delivery_mode: 'offline_sync',
            favorite: false,
            pinned: false,
            tags: '文件'
        }
    ];

    assert.equal(recordDeviceKey(records[2]), 'phone-2');
    assert.equal(recordSourceAppKey(records[1]), '微信');
    assert.equal(recordStatusKey(records[0]), 'manual');
    assert.equal(recordStatusKey(records[2]), 'offline_sync');

    assert.deepEqual(
        filterHistoryRecords(records, { type: 'all', channel: 'all', device: 'all', status: 'all', sourceApp: 'all' }, 'history').map(item => item.id),
        ['text-1', 'file-1']
    );
    assert.deepEqual(
        filterHistoryRecords(records, { type: 'notification', channel: 'all', device: 'all', status: 'all', sourceApp: '微信', query: '三点' }, 'notifications').map(item => item.id),
        ['notification-1']
    );
    assert.deepEqual(
        filterHistoryRecords(records, { type: 'all', channel: 'all', device: 'all', status: 'favorite', sourceApp: 'all' }, 'history').map(item => item.id),
        ['text-1']
    );
    assert.deepEqual(
        filterHistoryRecords(records, { type: 'all', channel: 'all', device: 'all', status: 'pinned', sourceApp: 'all' }, 'notifications').map(item => item.id),
        ['notification-1']
    );
});

test('AI assistant filter and scope helpers keep LLM-driven tool context explicit', () => {
    const filters = buildAiAssistantFilters({
        query: '项目',
        type: 'all',
        channel: 'server',
        device: '手机',
        status: 'pinned',
        sourceApp: '微信',
        tag: '待办'
    }, 'notifications', 80);

    assert.deepEqual(filters, {
        search: '项目',
        content_type: 'notification',
        via: 'server',
        from_device: '手机',
        source_app: '微信',
        delivery_status: null,
        favorite: null,
        pinned: true,
        tag: '待办',
        limit: 80
    });

    const scopeText = buildAiAssistantScopeText(filters, 'notifications', (key, fallback) => {
        const labels = {
            type: '通知',
            channel: '服务器中转',
            device: '手机',
            sourceApp: '微信'
        };
        return labels[key] || fallback;
    });

    assert.match(scopeText, /范围=通知记录/);
    assert.match(scopeText, /类型=通知/);
    assert.match(scopeText, /状态=置顶/);
    assert.match(scopeText, /LLM 会自主选择 Skill 和工具/);

    assert.equal(
        buildAiSessionScopeLabel({ content_type: 'notification', record_ids: ['a', 'b'], limit: 80 }),
        '范围：类型=notification，记录ID=2条，上限=80'
    );
    assert.equal(buildAiSessionScopeLabel(null), '范围：未记录');
});
