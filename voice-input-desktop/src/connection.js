export function normalizeOpenAiConfig(openai = {}) {
    return {
        api_key: (openai.api_key || openai.apiKey || '').trim(),
        api_url: (openai.api_url || openai.apiUrl || '').trim(),
        model_name: (openai.model_name || openai.modelName || '').trim()
    };
}

export function hasConfiguredOpenAi(openai = {}) {
    const normalized = normalizeOpenAiConfig(openai);
    return Boolean(
        normalized.api_key &&
        normalized.model_name
    );
}

export function normalizeInputMode(mode = 'direct') {
    return ['direct', 'clipboard', 'confirm'].includes(mode) ? mode : 'direct';
}

export function inputModeLabel(mode) {
    switch (mode) {
        case 'clipboard':
            return '只复制';
        case 'confirm':
            return '手动插入';
        case 'history_only':
            return '仅保存';
        default:
            return '直接插入';
    }
}
