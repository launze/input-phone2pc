export function getStartOfWeek() {
    const now = new Date();
    const day = now.getDay() || 7;
    const start = new Date(now);
    start.setHours(0, 0, 0, 0);
    start.setDate(now.getDate() - day + 1);
    return start;
}

export function getStartOfMonth() {
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0, 0);
}

export function getStartOfQuarter() {
    const now = new Date();
    const quarterStartMonth = Math.floor(now.getMonth() / 3) * 3;
    return new Date(now.getFullYear(), quarterStartMonth, 1, 0, 0, 0, 0);
}

export function getStartOfHalfYear() {
    const now = new Date();
    const halfStartMonth = now.getMonth() < 6 ? 0 : 6;
    return new Date(now.getFullYear(), halfStartMonth, 1, 0, 0, 0, 0);
}

export function getStartOfYear() {
    const now = new Date();
    return new Date(now.getFullYear(), 0, 1, 0, 0, 0, 0);
}

export function toRangeStart(dateValue) {
    const date = parseDateInput(dateValue);
    date.setHours(0, 0, 0, 0);
    return date.getTime();
}

export function toRangeEnd(dateValue) {
    const date = parseDateInput(dateValue);
    date.setHours(23, 59, 59, 999);
    return date.getTime();
}

function parseDateInput(dateValue) {
    const [year, month, day] = dateValue.split('-').map(Number);
    return new Date(year, month - 1, day, 0, 0, 0, 0);
}
