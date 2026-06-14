export function setMenuVisible(menuEl, triggerEl, visible, display = 'block') {
    if (!menuEl || !triggerEl) return;
    menuEl.style.display = visible ? display : 'none';
    triggerEl.classList.toggle('active', visible);
    triggerEl.setAttribute('aria-expanded', visible ? 'true' : 'false');
}

export function createMenuVisibilityController(menuEl, triggerEl, display = 'block') {
    return {
        setVisible(visible) {
            setMenuVisible(menuEl, triggerEl, visible, display);
        },
        isVisible() {
            return Boolean(menuEl && menuEl.style.display !== 'none');
        }
    };
}
