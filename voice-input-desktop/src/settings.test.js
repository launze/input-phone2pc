import test from 'node:test';
import assert from 'node:assert/strict';

import {
    createMenuVisibilityController,
    setMenuVisible
} from './settings.js';

function fakeElement() {
    const classes = new Set();
    const attributes = new Map();
    return {
        style: {},
        classList: {
            toggle(name, enabled) {
                if (enabled) {
                    classes.add(name);
                } else {
                    classes.delete(name);
                }
            },
            contains(name) {
                return classes.has(name);
            }
        },
        setAttribute(name, value) {
            attributes.set(name, value);
        },
        getAttribute(name) {
            return attributes.get(name);
        }
    };
}

test('setMenuVisible updates display, active state and aria-expanded', () => {
    const menu = fakeElement();
    const trigger = fakeElement();

    setMenuVisible(menu, trigger, true, 'grid');

    assert.equal(menu.style.display, 'grid');
    assert.equal(trigger.classList.contains('active'), true);
    assert.equal(trigger.getAttribute('aria-expanded'), 'true');

    setMenuVisible(menu, trigger, false, 'grid');

    assert.equal(menu.style.display, 'none');
    assert.equal(trigger.classList.contains('active'), false);
    assert.equal(trigger.getAttribute('aria-expanded'), 'false');
});

test('setMenuVisible ignores missing elements without throwing', () => {
    assert.doesNotThrow(() => setMenuVisible(null, fakeElement(), true));
    assert.doesNotThrow(() => setMenuVisible(fakeElement(), null, true));
});

test('createMenuVisibilityController reports menu visibility consistently', () => {
    const menu = fakeElement();
    const trigger = fakeElement();
    const controller = createMenuVisibilityController(menu, trigger, 'flex');

    controller.setVisible(true);
    assert.equal(menu.style.display, 'flex');
    assert.equal(controller.isVisible(), true);

    controller.setVisible(false);
    assert.equal(menu.style.display, 'none');
    assert.equal(controller.isVisible(), false);
});
