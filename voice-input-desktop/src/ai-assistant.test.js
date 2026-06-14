import test from 'node:test';
import assert from 'node:assert/strict';

import {
    AI_ASSISTANT_EMPTY_TEXT,
    aiToolCallDetailLabel,
    aiAssistantPlaceholder,
    applyAiToolEvent,
    collectAiMessageReferenceIds,
    collectAiReferences,
    parseAiMetadata,
    renderAiExportedFileItems,
    renderAiReferenceItems,
    renderAiToolCallItem
} from './ai-assistant.js';

test('tool start event appends a running tool call with arguments', () => {
    const result = applyAiToolEvent([], {
        event: 'tool_call_start',
        tool_name: 'search_history_records',
        message: '开始搜索',
        data: {
            arguments: {
                content_type: 'text',
                favorite: true
            }
        }
    });

    assert.equal(result.handled, true);
    assert.equal(result.statusMessage, '开始搜索');
    assert.equal(result.toolCalls.length, 1);
    assert.equal(result.toolCalls[0].tool_name, 'search_history_records');
    assert.equal(result.toolCalls[0].status, 'running');
    assert.deepEqual(JSON.parse(result.toolCalls[0].arguments_json), {
        content_type: 'text',
        favorite: true
    });
});

test('tool result event completes matching pending call and keeps normalized arguments', () => {
    const start = applyAiToolEvent([], {
        event: 'tool_call_start',
        tool_name: 'search_notification_records',
        data: {
            arguments: {
                source_app: '微信'
            }
        }
    });

    const result = applyAiToolEvent(start.toolCalls, {
        event: 'tool_call_result',
        tool_name: 'search_notification_records',
        tool_call_id: 'call-1',
        message: '查询完成',
        data: {
            arguments: {
                source_app: '微信',
                pinned: true
            },
            record_count: 2,
            record_ids: ['pc-1', 'pc-2']
        }
    });

    assert.equal(result.handled, true);
    assert.equal(result.statusMessage, '查询完成');
    assert.equal(result.toolCalls.length, 1);
    assert.equal(result.toolCalls[0].id, 'call-1');
    assert.equal(result.toolCalls[0].status, 'completed');
    assert.deepEqual(JSON.parse(result.toolCalls[0].arguments_json), {
        source_app: '微信',
        pinned: true
    });
    assert.deepEqual(JSON.parse(result.toolCalls[0].result_json).record_ids, ['pc-1', 'pc-2']);
});

test('result event creates a completed call when start event was missed', () => {
    const result = applyAiToolEvent([], {
        event: 'tool_call_result',
        tool_name: 'summarize_records',
        tool_call_id: 'call-2',
        data: {
            arguments: {
                record_ids: ['pc-1']
            },
            record_count: 1
        }
    });

    assert.equal(result.toolCalls.length, 1);
    assert.equal(result.toolCalls[0].id, 'call-2');
    assert.equal(result.toolCalls[0].status, 'completed');
    assert.deepEqual(JSON.parse(result.toolCalls[0].arguments_json), {
        record_ids: ['pc-1']
    });
});

test('terminal events report success or error status without changing calls', () => {
    const calls = [{ id: 'call-1', status: 'completed' }];
    const done = applyAiToolEvent(calls, {
        event: 'assistant_done',
        data: {
            exported_file: {
                saved_path: 'C:/tmp/answer.docx'
            }
        }
    });
    const error = applyAiToolEvent(calls, {
        event: 'assistant_error',
        message: '规划失败'
    });

    assert.equal(done.statusTone, 'success');
    assert.equal(done.statusMessage, '回答完成，已导出：C:/tmp/answer.docx');
    assert.equal(done.toolCalls, calls);
    assert.equal(error.statusTone, 'error');
    assert.equal(error.statusMessage, '规划失败');
});

test('AI tool call detail labels cover records apps exports schedules and saved sessions', () => {
    assert.equal(aiToolCallDetailLabel({ record_count: 3 }), '3 条记录');
    assert.equal(aiToolCallDetailLabel({ records: [{}, {}] }), '2 条记录');
    assert.equal(aiToolCallDetailLabel({ apps: [{}, {}, {}] }), '3 个 App');
    assert.equal(aiToolCallDetailLabel({ exported_file: { saved_path: 'C:/tmp/a.docx' } }), '已导出 Word');
    assert.equal(aiToolCallDetailLabel({ summary: '摘要' }), '已生成摘要');
    assert.equal(aiToolCallDetailLabel({ scheduled: true }), '已安排');
    assert.equal(aiToolCallDetailLabel({ saved: true, selected_skill: 'weekly_report', source_filters: '{}' }), '已保存，Skill=weekly_report，筛选已保存');
    assert.equal(aiToolCallDetailLabel({}), '已执行');
});

test('renderAiToolCallItem escapes values and compacts arguments', () => {
    const html = renderAiToolCallItem({
        tool_name: '<search_history_records>',
        status: 'completed',
        arguments_json: JSON.stringify({
            content_type: 'notification',
            empty: '',
            record_ids: ['pc-1', 'pc-2']
        }),
        result_json: JSON.stringify({ record_count: 2 })
    });

    assert.match(html, /&lt;search_history_records&gt;/);
    assert.match(html, /completed · 2 条记录/);
    assert.match(html, /content_type/);
    assert.doesNotMatch(html, /empty/);
});

test('collectAiReferences merges tool records with assistant metadata ids without duplicates', () => {
    const toolCalls = [
        {
            result_json: JSON.stringify({
                records: [
                    {
                        id: 'pc-1',
                        content: '工具记录',
                        metadata: JSON.stringify({ app_name: '微信' })
                    }
                ]
            })
        }
    ];
    const messages = [
        {
            role: 'assistant',
            metadata: JSON.stringify({ record_ids: ['pc-1', 'pc-2'] })
        }
    ];
    const references = collectAiReferences(toolCalls, messages, id => id === 'pc-2'
        ? {
            id: 'pc-2',
            content: '本地记录',
            metadata: JSON.stringify({ file_name: 'todo.md' })
        }
        : null);

    assert.deepEqual(collectAiMessageReferenceIds(messages), ['pc-1', 'pc-2']);
    assert.deepEqual(references.map(item => item.id), ['pc-1', 'pc-2']);
    assert.deepEqual(references[0].metadata, { app_name: '微信' });
    assert.deepEqual(references[1].metadata, { file_name: 'todo.md' });
});

test('AI reference and exported file renderers escape content and keep actions', () => {
    assert.deepEqual(parseAiMetadata({ app_name: '微信' }), { app_name: '微信' });
    assert.equal(parseAiMetadata('{bad json'), null);

    const referenceHtml = renderAiReferenceItems([{
        id: 'pc-1',
        content_type: 'notification',
        content: '<b>下午三点同步</b>',
        metadata: { app_name: '微信' }
    }]);
    const fileHtml = renderAiExportedFileItems([{
        filename: '<answer>.docx',
        saved_path: 'C:/tmp/<answer>.docx',
        file_type: 'answer_word',
        created_at: 1700000000000
    }]);

    assert.match(referenceHtml, /data-ai-reference-action="copy"/);
    assert.match(referenceHtml, /data-ai-reference-action="insert"/);
    assert.match(referenceHtml, /&lt;b&gt;下午三点同步&lt;\/b&gt;/);
    assert.match(referenceHtml, /微信/);
    assert.match(fileHtml, /&lt;answer&gt;\.docx/);
    assert.match(fileHtml, /data-ai-export-action="open"/);
    assert.match(fileHtml, /data-ai-export-action="folder"/);
});

test('AI assistant visible empty states keep assistant wording instead of legacy report wording', () => {
    const combinedText = Object.values(AI_ASSISTANT_EMPTY_TEXT).join('\n');

    assert.doesNotMatch(combinedText, /报告/);
    assert.equal(AI_ASSISTANT_EMPTY_TEXT.chat, '');
    assert.match(AI_ASSISTANT_EMPTY_TEXT.output, /回答将在这里实时显示/);
    assert.match(aiAssistantPlaceholder('output'), /ai-report-placeholder/);
    assert.match(aiAssistantPlaceholder('output'), /回答将在这里实时显示/);
    assert.doesNotMatch(aiAssistantPlaceholder('output'), /报告/);
});
