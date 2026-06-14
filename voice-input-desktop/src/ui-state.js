export function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export function parseJsonSafe(value) {
    try {
        return JSON.parse(value);
    } catch (_) {
        return null;
    }
}

export function formatDateTime(timestamp) {
    if (!timestamp) return '';
    return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
}

export function compactToolArguments(args) {
    if (!args || typeof args !== 'object') return '{}';
    const entries = Object.entries(args).filter(([, value]) => {
        return value !== null && value !== undefined && value !== '' && value !== 'all';
    });
    if (!entries.length) return '{}';
    return entries
        .map(([key, value]) => `${key}: ${typeof value === 'string' ? value : JSON.stringify(value)}`)
        .join(', ');
}

export function markdownToHtml(markdown) {
    const lines = String(markdown || '').replace(/\r\n/g, '\n').split('\n');
    const blocks = [];
    let paragraph = [];
    let listType = null;
    let listItems = [];
    let blockquote = [];
    let codeFence = null;
    let codeLines = [];

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

    function flushBlockquote() {
        if (!blockquote.length) return;
        blocks.push(`<blockquote>${blockquote.map(renderInlineMarkdown).join('<br>')}</blockquote>`);
        blockquote = [];
    }

    function flushCodeFence() {
        if (!codeFence) return;
        const languageClass = codeFence.language ? ` class="language-${escapeHtml(codeFence.language)}"` : '';
        blocks.push(`<pre><code${languageClass}>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
        codeFence = null;
        codeLines = [];
    }

    function flushAllTextBlocks() {
        flushParagraph();
        flushList();
        flushBlockquote();
    }

    for (let index = 0; index < lines.length; index += 1) {
        const rawLine = lines[index];
        const fenceMatch = rawLine.match(/^```([A-Za-z0-9_+.-]*)\s*$/);
        if (fenceMatch) {
            if (codeFence) {
                flushCodeFence();
            } else {
                flushAllTextBlocks();
                codeFence = { language: fenceMatch[1] || '' };
                codeLines = [];
            }
            continue;
        }

        if (codeFence) {
            codeLines.push(rawLine);
            continue;
        }

        const line = rawLine.trim();

        if (!line) {
            flushAllTextBlocks();
            continue;
        }

        const headingMatch = line.match(/^(#{1,6})\s+(.*)$/);
        if (headingMatch) {
            flushAllTextBlocks();
            const level = Math.min(headingMatch[1].length, 6);
            blocks.push(`<h${level}>${renderInlineMarkdown(headingMatch[2])}</h${level}>`);
            continue;
        }

        if (/^---+$/.test(line) || /^\*\*\*+$/.test(line)) {
            flushAllTextBlocks();
            blocks.push('<hr>');
            continue;
        }

        if (isTableStart(lines, index)) {
            flushAllTextBlocks();
            const parsed = parseMarkdownTable(lines, index);
            blocks.push(renderMarkdownTable(parsed.rows));
            index = parsed.nextIndex - 1;
            continue;
        }

        const unorderedMatch = line.match(/^[-*+]\s+(.*)$/);
        if (unorderedMatch) {
            flushParagraph();
            flushBlockquote();
            if (listType !== 'ul') {
                flushList();
                listType = 'ul';
            }
            listItems.push(`<li>${renderTaskListItem(unorderedMatch[1])}</li>`);
            continue;
        }

        const orderedMatch = line.match(/^(\d+)([.)、）])\s+(.*)$/);
        if (orderedMatch) {
            flushParagraph();
            flushBlockquote();
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
            blockquote.push(quoteMatch[1]);
            continue;
        }

        flushList();
        flushBlockquote();
        paragraph.push(line);
    }

    flushCodeFence();
    flushAllTextBlocks();
    return blocks.join('');
}

function isTableStart(lines, index) {
    const header = lines[index]?.trim() || '';
    const separator = lines[index + 1]?.trim() || '';
    return header.includes('|') && /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(separator);
}

function parseMarkdownTable(lines, startIndex) {
    const rows = [];
    let index = startIndex;
    while (index < lines.length) {
        const line = lines[index].trim();
        if (!line || !line.includes('|')) break;
        if (index !== startIndex + 1) {
            rows.push(splitTableRow(line));
        }
        index += 1;
    }
    return { rows, nextIndex: index };
}

function splitTableRow(line) {
    const normalized = line.replace(/^\|/, '').replace(/\|$/, '');
    return normalized.split('|').map(cell => cell.trim());
}

function renderMarkdownTable(rows) {
    if (!rows.length) return '';
    const [header, ...body] = rows;
    return [
        '<div class="markdown-table-wrap"><table>',
        `<thead><tr>${header.map(cell => `<th>${renderInlineMarkdown(cell)}</th>`).join('')}</tr></thead>`,
        `<tbody>${body.map(row => `<tr>${row.map(cell => `<td>${renderInlineMarkdown(cell)}</td>`).join('')}</tr>`).join('')}</tbody>`,
        '</table></div>'
    ].join('');
}

function renderTaskListItem(text) {
    const taskMatch = text.match(/^\[( |x|X)\]\s+(.*)$/);
    if (!taskMatch) return renderInlineMarkdown(text);
    const checked = taskMatch[1].toLowerCase() === 'x' ? ' checked' : '';
    return `<input type="checkbox" disabled${checked}> ${renderInlineMarkdown(taskMatch[2])}`;
}

function renderInlineMarkdown(text) {
    return escapeHtml(text)
        .replace(/!\[([^\]]*)\]\((https?:\/\/[^)\s]+)\)/g, '<img src="$2" alt="$1">')
        .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/__([^_]+)__/g, '<strong>$1</strong>')
        .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
        .replace(/(^|[^_])_([^_\n]+)_/g, '$1<em>$2</em>')
        .replace(/~~([^~]+)~~/g, '<del>$1</del>');
}
