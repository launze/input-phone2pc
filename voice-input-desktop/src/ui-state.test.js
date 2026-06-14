import test from 'node:test';
import assert from 'node:assert/strict';

import {
    compactToolArguments,
    escapeHtml,
    formatDateTime,
    markdownToHtml,
    parseJsonSafe
} from './ui-state.js';

test('escapeHtml escapes unsafe characters used in history and assistant rendering', () => {
    assert.equal(
        escapeHtml(`<img src=x onerror="alert('x')">&`),
        '&lt;img src=x onerror=&quot;alert(&#39;x&#39;)&quot;&gt;&amp;'
    );
});

test('parseJsonSafe returns parsed objects or null without throwing', () => {
    assert.deepEqual(parseJsonSafe('{"ok":true}'), { ok: true });
    assert.equal(parseJsonSafe('{broken'), null);
});

test('compactToolArguments removes empty filters and keeps structured values', () => {
    const text = compactToolArguments({
        search: '项目',
        content_type: 'all',
        source_app: '',
        favorite: true,
        record_ids: ['pc-1', 'pc-2'],
        limit: 50
    });

    assert.equal(text, 'search: 项目, favorite: true, record_ids: ["pc-1","pc-2"], limit: 50');
});

test('markdownToHtml renders common answer structure and escapes unsafe inline HTML', () => {
    const html = markdownToHtml([
        '# 标题 <script>',
        '',
        '- **重点**',
        '- `工具`',
        '',
        '1. 步骤',
        '',
        '> 引用',
        '',
        '正文 *强调*'
    ].join('\n'));

    assert.match(html, /<h1>标题 &lt;script&gt;<\/h1>/);
    assert.match(html, /<ul><li><strong>重点<\/strong><\/li><li><code>工具<\/code><\/li><\/ul>/);
    assert.match(html, /<ol><li>步骤<\/li><\/ol>/);
    assert.match(html, /<blockquote>引用<\/blockquote>/);
    assert.match(html, /<p>正文 <em>强调<\/em><\/p>/);
});

test('markdownToHtml renders fenced code, tables, task lists and links', () => {
    const html = markdownToHtml([
        '```js',
        'const value = "<safe>";',
        '```',
        '',
        '| 事项 | 状态 |',
        '| --- | --- |',
        '| SSE | 已完成 |',
        '',
        '- [x] 完成',
        '- [ ] 待办',
        '',
        '[官网](https://example.com)'
    ].join('\n'));

    assert.match(html, /<pre><code class="language-js">const value = &quot;&lt;safe&gt;&quot;;<\/code><\/pre>/);
    assert.match(html, /<table>/);
    assert.match(html, /<th>事项<\/th>/);
    assert.match(html, /<td>SSE<\/td>/);
    assert.match(html, /<input type="checkbox" disabled checked> 完成/);
    assert.match(html, /<input type="checkbox" disabled> 待办/);
    assert.match(html, /<a href="https:\/\/example\.com" target="_blank" rel="noreferrer">官网<\/a>/);
});

test('formatDateTime returns blank for missing timestamps and localized text otherwise', () => {
    assert.equal(formatDateTime(0), '');
    assert.match(formatDateTime(1700000000000), /\d{4}/);
});
