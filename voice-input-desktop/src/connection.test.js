import test from 'node:test';
import assert from 'node:assert/strict';

import {
    applyOpenAiProviderPreset,
    getOpenAiProviderPreset,
    hasConfiguredOpenAi,
    inputModeLabel,
    normalizeInputMode,
    normalizeOpenAiConfig,
    normalizeOpenAiProvider
} from './connection.js';

test('normalizeOpenAiConfig accepts snake_case and camelCase while trimming values', () => {
    assert.deepEqual(
        normalizeOpenAiConfig({
            apiKey: ' key ',
            apiUrl: ' https://api.example.com ',
            modelName: ' gpt-test ',
            provider: ' custom '
        }),
        {
            provider: 'custom',
            api_key: 'key',
            api_url: 'https://api.example.com',
            model_name: 'gpt-test'
        }
    );
});

test('AI provider normalization infers known providers from legacy URLs', () => {
    assert.equal(
        normalizeOpenAiProvider('', 'https://ark.cn-beijing.volces.com/api/coding/v3'),
        'volcengine'
    );
    assert.equal(normalizeOpenAiProvider('custom', 'https://api.deepseek.com'), 'deepseek');
    assert.equal(normalizeOpenAiProvider('', 'https://api.example.com/v1'), 'custom');
});

test('Volcano Engine preset supplies the Coding API URL without retaining another model', () => {
    assert.deepEqual(getOpenAiProviderPreset('volcengine'), {
        api_url: 'https://ark.cn-beijing.volces.com/api/coding/v3',
        model_name: ''
    });
    assert.deepEqual(
        applyOpenAiProviderPreset(
            { api_key: 'secret', api_url: 'https://api.openai.com/v1', model_name: 'gpt-5-mini' },
            'volcengine'
        ),
        {
            provider: 'volcengine',
            api_key: 'secret',
            api_url: 'https://ark.cn-beijing.volces.com/api/coding/v3',
            model_name: ''
        }
    );
});

test('hasConfiguredOpenAi only requires api key and model name', () => {
    assert.equal(hasConfiguredOpenAi({ api_key: 'key', model_name: 'model' }), true);
    assert.equal(hasConfiguredOpenAi({ api_key: 'key', model_name: '' }), false);
    assert.equal(hasConfiguredOpenAi({ api_key: '', model_name: 'model' }), false);
});

test('input mode normalization falls back to direct and labels all known modes', () => {
    assert.equal(normalizeInputMode('direct'), 'direct');
    assert.equal(normalizeInputMode('clipboard'), 'clipboard');
    assert.equal(normalizeInputMode('confirm'), 'confirm');
    assert.equal(normalizeInputMode('unknown'), 'direct');
    assert.equal(inputModeLabel('direct'), '直接插入');
    assert.equal(inputModeLabel('clipboard'), '只复制');
    assert.equal(inputModeLabel('confirm'), '手动插入');
    assert.equal(inputModeLabel('history_only'), '仅保存');
    assert.equal(inputModeLabel('unknown'), '直接插入');
});
