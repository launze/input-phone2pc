import test from 'node:test';
import assert from 'node:assert/strict';

import {
    getStartOfHalfYear,
    getStartOfMonth,
    getStartOfQuarter,
    getStartOfWeek,
    getStartOfYear,
    toRangeEnd,
    toRangeStart
} from './reports.js';

function withFakeDate(iso, callback) {
    const RealDate = globalThis.Date;
    const fixed = new RealDate(iso).getTime();
    class FakeDate extends RealDate {
        constructor(...args) {
            if (args.length === 0) {
                super(fixed);
            } else {
                super(...args);
            }
        }

        static now() {
            return fixed;
        }
    }
    globalThis.Date = FakeDate;
    try {
        callback();
    } finally {
        globalThis.Date = RealDate;
    }
}

test('report period helpers calculate week, month, quarter, half year and year starts', () => {
    withFakeDate('2026-06-13T15:30:00+08:00', () => {
        assert.equal(getStartOfWeek().getDay(), 1);
        assert.equal(getStartOfWeek().getHours(), 0);
        assert.equal(getStartOfMonth().getMonth(), 5);
        assert.equal(getStartOfMonth().getDate(), 1);
        assert.equal(getStartOfQuarter().getMonth(), 3);
        assert.equal(getStartOfHalfYear().getMonth(), 0);
        assert.equal(getStartOfYear().getMonth(), 0);
        assert.equal(getStartOfYear().getDate(), 1);
    });
});

test('custom range helpers cover whole selected day', () => {
    const start = new Date(toRangeStart('2026-06-13'));
    const end = new Date(toRangeEnd('2026-06-13'));

    assert.equal(start.getFullYear(), 2026);
    assert.equal(start.getMonth(), 5);
    assert.equal(start.getDate(), 13);
    assert.equal(start.getHours(), 0);
    assert.equal(start.getMinutes(), 0);
    assert.equal(end.getHours(), 23);
    assert.equal(end.getMinutes(), 59);
    assert.equal(end.getSeconds(), 59);
    assert.equal(end.getMilliseconds(), 999);
});
