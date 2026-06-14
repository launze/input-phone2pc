import {
    compactToolArguments,
    escapeHtml,
    formatDateTime,
    parseJsonSafe
} from './ui-state.js';

export const AI_ASSISTANT_EMPTY_TEXT = {
    skills: '暂无 Skills。',
    sessions: '暂无会话。',
    chat: '',
    tools: '暂无工具调用。',
    references: '暂无引用记录。',
    exports: '暂无导出文件。',
    output: '回答将在这里实时显示。'
};

export function aiAssistantPlaceholder(key) {
    const text = AI_ASSISTANT_EMPTY_TEXT[key] || '';
    return `<div class="ai-report-placeholder">${escapeHtml(text)}</div>`;
}

export function applyAiToolEvent(toolCalls = [], payload = {}) {
    const event = payload.event || '';
    if (event === 'tool_call_start') {
        return {
            toolCalls: [
                ...toolCalls,
                {
                    id: `pending-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
                    tool_name: payload.tool_name || payload.toolName || 'tool',
                    arguments_json: JSON.stringify(payload.data?.arguments || {}),
                    result_json: JSON.stringify({ message: payload.message || '开始调用' }),
                    status: 'running'
                }
            ],
            statusMessage: payload.message || '正在调用工具...',
            handled: true
        };
    }

    if (event === 'tool_call_result') {
        const toolName = payload.tool_name || payload.toolName || 'tool';
        const nextToolCalls = [...toolCalls];
        const pending = [...nextToolCalls].reverse().find(item =>
            String(item.id).startsWith('pending-') &&
            (item.tool_name || item.toolName) === toolName &&
            item.status === 'running'
        );
        if (pending) {
            pending.id = payload.tool_call_id || payload.toolCallId || pending.id;
            if (payload.data?.arguments) {
                pending.arguments_json = JSON.stringify(payload.data.arguments);
            }
            pending.result_json = JSON.stringify(payload.data || {});
            pending.status = 'completed';
        } else {
            nextToolCalls.push({
                id: payload.tool_call_id || payload.toolCallId || `event-${Date.now()}`,
                tool_name: toolName,
                arguments_json: JSON.stringify(payload.data?.arguments || {}),
                result_json: JSON.stringify(payload.data || {}),
                status: 'completed'
            });
        }
        return {
            toolCalls: nextToolCalls,
            statusMessage: payload.message || '工具调用完成。',
            handled: true
        };
    }

    if (event === 'assistant_done') {
        const exportedFile = payload.data?.exported_file || payload.data?.exportedFile;
        const savedPath = exportedFile?.saved_path || exportedFile?.savedPath;
        return {
            toolCalls,
            statusMessage: savedPath ? `回答完成，已导出：${savedPath}` : (payload.message || '回答完成。'),
            statusTone: 'success',
            handled: true
        };
    }

    if (event === 'assistant_error') {
        return {
            toolCalls,
            statusMessage: payload.message || 'AI 助手执行失败。',
            statusTone: 'error',
            handled: true
        };
    }

    return {
        toolCalls,
        handled: false
    };
}

export function parseAiMetadata(metadata) {
    if (!metadata) return null;
    if (typeof metadata === 'object') return metadata;
    return parseJsonSafe(metadata);
}

export function aiToolCallDetailLabel(result = {}) {
    const recordCount = result?.record_count ?? result?.recordCount ?? result?.records?.length ?? null;
    const appCount = result?.apps?.length ?? null;
    const exportedFile = result?.exported_file || result?.exportedFile || null;
    const savedSkill = result?.selected_skill || result?.selectedSkill || null;
    const savedFilters = result?.source_filters || result?.sourceFilters || null;
    if (recordCount !== null) return `${recordCount} 条记录`;
    if (appCount !== null) return `${appCount} 个 App`;
    if (exportedFile?.saved_path || exportedFile?.savedPath) return '已导出 Word';
    if (result?.summary) return '已生成摘要';
    if (result?.scheduled) return '已安排';
    if (result?.saved) {
        return [
            '已保存',
            savedSkill ? `Skill=${savedSkill}` : '',
            savedFilters ? '筛选已保存' : ''
        ].filter(Boolean).join('，');
    }
    return '已执行';
}

export function renderAiToolCallItem(toolCall = {}) {
    const args = parseJsonSafe(toolCall.arguments_json || toolCall.argumentsJson || '{}');
    const result = parseJsonSafe(toolCall.result_json || toolCall.resultJson || '{}') || {};
    return `
        <div class="ai-tool-call">
            <strong>${escapeHtml(toolCall.tool_name || toolCall.toolName || 'tool')}</strong>
            <span>${escapeHtml(toolCall.status || '')} · ${escapeHtml(aiToolCallDetailLabel(result))}</span>
            <code>${escapeHtml(compactToolArguments(args))}</code>
        </div>
    `;
}

export function collectAiMessageReferenceIds(messages = []) {
    const ids = [];
    messages.forEach(message => {
        if (message.role !== 'assistant') return;
        const metadata = parseAiMetadata(message.metadata);
        const recordIds = metadata?.record_ids || metadata?.recordIds || [];
        if (!Array.isArray(recordIds)) return;
        recordIds.forEach(id => {
            if (id) ids.push(String(id));
        });
    });
    return ids;
}

export function collectAiReferences(toolCalls = [], messages = [], findHistoryRecordById = () => null) {
    const seen = new Set();
    const refs = [];
    const metadataRecordIds = collectAiMessageReferenceIds(messages);
    toolCalls.forEach(toolCall => {
        const result = parseJsonSafe(toolCall.result_json || toolCall.resultJson || '{}');
        const records = Array.isArray(result?.records)
            ? result.records
            : result?.record
                ? [result.record]
                : [];
        records.forEach(record => {
            if (!record || !record.id || seen.has(record.id)) return;
            seen.add(record.id);
            refs.push({
                ...record,
                metadata: parseAiMetadata(record.metadata)
            });
        });
    });
    metadataRecordIds.forEach(recordId => {
        if (seen.has(recordId)) return;
        const record = findHistoryRecordById(recordId);
        if (!record) return;
        seen.add(recordId);
        refs.push({
            ...record,
            metadata: parseAiMetadata(record.metadata)
        });
    });
    return refs;
}

export function renderAiReferenceItems(references = [], limit = 20) {
    return references.slice(0, limit).map((record, index) => {
        const app = record.metadata?.app_name || record.metadata?.app_package || '';
        const source = app || record.from_device_name || record.fromDeviceName || record.from_device_id || record.fromDeviceId || '';
        const content = String(record.content || '').replace(/\s+/g, ' ').trim();
        return `
            <div class="ai-reference-item" title="${escapeHtml(content)}" data-record-id="${escapeHtml(record.id)}">
                <div class="ai-reference-head">
                    <strong>#${index + 1} ${escapeHtml(record.content_type || record.contentType || 'record')}</strong>
                    <div class="ai-reference-actions">
                        <button class="history-action-btn" data-ai-reference-action="copy" data-record-id="${escapeHtml(record.id)}">复制</button>
                        <button class="history-action-btn" data-ai-reference-action="insert" data-record-id="${escapeHtml(record.id)}">插入</button>
                    </div>
                </div>
                <span>${escapeHtml(source || '未知来源')}</span>
                <p>${escapeHtml(content.slice(0, 120))}${content.length > 120 ? '...' : ''}</p>
            </div>
        `;
    }).join('');
}

export function renderAiExportedFileItems(files = [], limit = 12) {
    return files.slice(0, limit).map(file => {
        const savedPath = file.saved_path || file.savedPath || '';
        const filename = file.filename || savedPath || '导出文件';
        return `
            <div class="ai-reference-item" title="${escapeHtml(savedPath)}">
                <div class="ai-reference-head">
                    <strong>${escapeHtml(filename)}</strong>
                    <div class="ai-reference-actions">
                        <button class="history-action-btn" data-ai-export-action="open" data-path="${escapeHtml(savedPath)}">打开</button>
                        <button class="history-action-btn" data-ai-export-action="folder" data-path="${escapeHtml(savedPath)}">目录</button>
                    </div>
                </div>
                <span>${escapeHtml(file.file_type || file.fileType || 'word')} · ${escapeHtml(formatDateTime(file.created_at || file.createdAt || Date.now()))}</span>
            </div>
        `;
    }).join('');
}
