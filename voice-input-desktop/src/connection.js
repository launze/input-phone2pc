const OPENAI_PROVIDER_PRESETS = {
    openai: {
        api_url: 'https://api.openai.com/v1/responses',
        model_name: 'gpt-5-mini'
    },
    volcengine: {
        api_url: 'https://ark.cn-beijing.volces.com/api/coding/v3',
        model_name: ''
    },
    deepseek: {
        api_url: 'https://api.deepseek.com',
        model_name: 'deepseek-chat'
    },
    dashscope: {
        api_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        model_name: 'qwen-plus'
    }
};

function inferOpenAiProvider(apiUrl = '') {
    const normalizedUrl = apiUrl.trim().toLowerCase();
    if (normalizedUrl.includes('ark.cn-beijing.volces.com')) return 'volcengine';
    if (normalizedUrl.includes('api.deepseek.com')) return 'deepseek';
    if (normalizedUrl.includes('dashscope') || normalizedUrl.includes('/compatible-mode/')) return 'dashscope';
    if (normalizedUrl.includes('api.openai.com')) return 'openai';
    return '';
}

export function normalizeOpenAiProvider(provider = '', apiUrl = '') {
    const normalized = String(provider || '').trim().toLowerCase();
    const inferred = inferOpenAiProvider(apiUrl);
    if (
        normalized === 'custom' ||
        !Object.prototype.hasOwnProperty.call(OPENAI_PROVIDER_PRESETS, normalized)
    ) {
        return inferred || 'custom';
    }
    return normalized;
}

export function getOpenAiProviderPreset(provider = '') {
    const preset = OPENAI_PROVIDER_PRESETS[provider];
    return preset ? { ...preset } : null;
}

export function applyOpenAiProviderPreset(openai = {}, provider = '') {
    const normalized = normalizeOpenAiConfig(openai);
    const normalizedProvider = normalizeOpenAiProvider(provider);
    const preset = getOpenAiProviderPreset(normalizedProvider);
    return {
        ...normalized,
        provider: normalizedProvider,
        api_url: preset?.api_url ?? normalized.api_url,
        model_name: preset?.model_name ?? normalized.model_name
    };
}

export function normalizeOpenAiConfig(openai = {}) {
    const apiUrl = (openai.api_url || openai.apiUrl || '').trim();
    return {
        provider: normalizeOpenAiProvider(openai.provider, apiUrl),
        api_key: (openai.api_key || openai.apiKey || '').trim(),
        api_url: apiUrl,
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
