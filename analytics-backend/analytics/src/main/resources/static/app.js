// Shared helpers for every page: the login session, API calls, toasts, dialogs, the server
// wake-up notice, the phone menu and the change-password dialog.

// The pages are served by the same Spring Boot app as the API, so API calls use relative URLs
// and work unchanged on localhost and on Render.
const API_BASE = "";

// ---------- Login session ----------

const Session = {
    get token() { return localStorage.getItem('cbToken'); },
    get user() { return localStorage.getItem('activeUser'); },
    get role() { return localStorage.getItem('activeRole'); },

    save(login) {
        localStorage.setItem('cbToken', login.token);
        localStorage.setItem('activeUser', login.username);
        localStorage.setItem('activeRole', login.role);
    },

    clear() {
        localStorage.removeItem('cbToken');
        localStorage.removeItem('activeUser');
        localStorage.removeItem('activeRole');
    }
};

const HOME_PAGE_BY_ROLE = { STUDENT: 'index.html', FACULTY: 'faculty.html', ADMIN: 'admin.html' };

function loginPageFor(role) {
    return role === 'FACULTY' || role === 'ADMIN' ? 'staff-login.html' : 'login.html';
}

// Call at the top of a protected page. Sends the visitor to the right place if they aren't
// logged in, or are logged in with a role that belongs on a different page. The server enforces
// the same rules on every API call — this just avoids showing a page that would only error out.
function requireRole(role) {
    if (!Session.token) {
        window.location.href = loginPageFor(role);
        return false;
    }
    if (Session.role !== role) {
        window.location.href = HOME_PAGE_BY_ROLE[Session.role] || 'login.html';
        return false;
    }
    return true;
}

function logout() {
    const page = loginPageFor(Session.role);
    Session.clear();
    window.location.href = page;
}

// ---------- API calls ----------

// Render's free plan puts the server to sleep when it's idle, and the first request then takes
// up to a minute. If any request is still waiting after 2 seconds, show a notice until all finish.
const WakeNotice = {
    pending: 0,
    timer: null,

    start() {
        this.pending++;
        if (!this.timer) this.timer = setTimeout(() => this.show(), 2000);
    },

    end() {
        this.pending = Math.max(0, this.pending - 1);
        if (this.pending > 0) return;
        clearTimeout(this.timer);
        this.timer = null;
        document.getElementById('wakeNotice')?.remove();
    },

    show() {
        if (document.getElementById('wakeNotice')) return;
        const el = document.createElement('div');
        el.id = 'wakeNotice';
        el.className = 'wake-notice';
        el.setAttribute('role', 'status');
        el.innerHTML = '<span class="spinner"></span><span>Waking up the server — this can take up to a minute…</span>';
        document.body.appendChild(el);
    }
};

// fetch() wrapper: adds the login token (unless auth is false) and JSON handling, and sends the
// user back to the login page if their token is missing or expired. Returns the Response, like
// fetch does.
async function api(path, { method = 'GET', body, auth = true } = {}) {
    const headers = {};
    if (auth && Session.token) headers['Authorization'] = 'Bearer ' + Session.token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    WakeNotice.start();
    let res;
    try {
        res = await fetch(API_BASE + path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    } catch (err) {
        toast("Couldn't reach the server. Check your connection and try again.", 'error');
        throw err;
    } finally {
        WakeNotice.end();
    }

    if (auth && res.status === 401) {
        const page = loginPageFor(Session.role);
        Session.clear();
        window.location.href = page;
        throw new Error('Session expired — please log in again');
    }
    return res;
}

// The server sends error messages as plain text
async function errorText(res, fallback = 'Something went wrong. Please try again.') {
    const text = (await res.text()).trim();
    return text || fallback;
}

// Escape anything user-provided before putting it into innerHTML
function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}

// Width-to-height ratio for line/bar charts: taller on phones so they stay readable
function chartAspect() {
    return window.innerWidth < 600 ? 1.1 : 2;
}

// Disables the button while its action runs, so a slow request can't be submitted twice
async function withBusy(button, action) {
    if (button?.disabled) return;
    if (button) button.disabled = true;
    try {
        return await action();
    } finally {
        if (button) button.disabled = false;
    }
}

// ---------- Toasts ----------

function toast(message, type = 'info') {
    let stack = document.getElementById('toastStack');
    if (!stack) {
        stack = document.createElement('div');
        stack.id = 'toastStack';
        stack.className = 'toast-stack';
        stack.setAttribute('aria-live', 'polite');
        document.body.appendChild(stack);
    }
    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.textContent = message;
    stack.appendChild(el);
    setTimeout(() => el.remove(), type === 'error' ? 6000 : 4000);
}

// ---------- Dialogs ----------

// Shows a dialog with the given inner HTML and returns its element. It closes on Escape, on a
// click outside it, or via closeModal().
function openModal(innerHtml) {
    closeModal();
    const backdrop = document.createElement('div');
    backdrop.id = 'modalBackdrop';
    backdrop.className = 'modal-backdrop';
    backdrop.innerHTML = `<div class="modal" role="dialog" aria-modal="true">${innerHtml}</div>`;
    backdrop.addEventListener('click', e => { if (e.target === backdrop) closeModal(); });
    document.body.appendChild(backdrop);
    backdrop.querySelector('input, button')?.focus();
    return backdrop.firstElementChild;
}

function closeModal() {
    document.getElementById('modalBackdrop')?.remove();
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

// A styled replacement for confirm(). Resolves to true only if the user confirms.
function confirmDialog(message, confirmLabel = 'Confirm') {
    return new Promise(resolve => {
        const modal = openModal(`
            <p>${escapeHtml(message)}</p>
            <div class="form-actions">
                <button class="btn btn-danger" data-answer="yes">${escapeHtml(confirmLabel)}</button>
                <button class="btn btn-secondary" data-answer="no">Cancel</button>
            </div>`);
        modal.addEventListener('click', e => {
            const answer = e.target.dataset.answer;
            if (!answer) return;
            closeModal();
            resolve(answer === 'yes');
        });
    });
}

function openChangePassword() {
    closeNav();
    const modal = openModal(`
        <h2>Change password</h2>
        <form>
            <label for="cpCurrent">Current password</label>
            <input type="password" id="cpCurrent" autocomplete="current-password" required>
            <label for="cpNew">New password (at least 8 characters)</label>
            <input type="password" id="cpNew" autocomplete="new-password" minlength="8" required>
            <label for="cpConfirm">Repeat the new password</label>
            <input type="password" id="cpConfirm" autocomplete="new-password" required>
            <div class="form-actions">
                <button type="submit" class="btn btn-primary">Change password</button>
                <button type="button" class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            </div>
        </form>`);

    modal.querySelector('form').addEventListener('submit', e => {
        e.preventDefault();
        const currentPassword = modal.querySelector('#cpCurrent').value;
        const newPassword = modal.querySelector('#cpNew').value;
        if (newPassword !== modal.querySelector('#cpConfirm').value) {
            return toast("The new passwords don't match", 'error');
        }
        withBusy(e.submitter, async () => {
            const res = await api('/api/change-password', { method: 'POST', body: { currentPassword, newPassword } });
            if (!res.ok) return toast(await errorText(res), 'error');
            closeModal();
            toast('Password changed', 'success');
        });
    });
}

// ---------- Phone menu ----------

function closeNav() {
    document.body.classList.remove('nav-open');
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('menuBtn')?.addEventListener('click', () => document.body.classList.toggle('nav-open'));
    document.querySelector('.backdrop')?.addEventListener('click', closeNav);
});
