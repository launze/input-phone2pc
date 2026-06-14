import test from 'node:test';
import assert from 'node:assert/strict';

import {
    hasConfiguredOpenAi,
    inputModeLabel,
    normalizeInputMode,
    normalizeOpenAiConfig
} from './connection.js';

test('normalizeOpenAiConfig accepts snake_case and camelCase while trimming values', () => {
    assert.deepEqual(
        normalizeOpenAiConfig({
            apiKey: ' key ',
            apiUrl: ' https://api.example.com ',
            modelName: ' gpt-test '
        }),
        {
            api_key: 'key',
            api_url: 'https://api.example.com',
            model_name: 'gpt-test'
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
