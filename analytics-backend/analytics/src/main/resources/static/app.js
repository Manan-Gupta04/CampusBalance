// Shared helpers for every page: the login session, authenticated API calls, and HTML escaping.
// Load after config.js.

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

// fetch() wrapper: adds the login token and JSON body handling, and sends the user back to the
// login page if the token is missing/expired. Returns the Response, like fetch does.
async function api(path, { method = 'GET', body } = {}) {
    const headers = {};
    if (Session.token) headers['Authorization'] = 'Bearer ' + Session.token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    const res = await fetch(API_BASE + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (res.status === 401) {
        const page = loginPageFor(Session.role);
        Session.clear();
        window.location.href = page;
        throw new Error('Session expired — please log in again');
    }
    return res;
}

// Escape anything user-provided before putting it into innerHTML.
function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
