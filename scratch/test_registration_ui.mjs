import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// DOM giả lập để kiểm tra lỗi xuất hiện ngay khi nhập, không cần gửi request.
const elements = new Map();
for (const id of ['login-form', 'register-form', 'forgot-form', 'auth-msg', 'link-register',
    'link-login', 'link-forgot', 'link-back-login', 'btn-register', 'reg-email', 'reg-email-error',
    'reg-username', 'reg-password', 'btn-login', 'login-username', 'login-password', 'btn-forgot',
    'forgot-email', 'btn-logout', 'auth-section', 'portal-section', 'display-name']) {
    elements.set(id, {
        value: '', textContent: '', innerText: '', style: {}, disabled: false,
        listeners: {}, attributes: {}, classList: { add() {}, remove() {} },
        addEventListener(type, callback) { this.listeners[type] = callback; },
        setAttribute(key, value) { this.attributes[key] = value; },
        checkValidity() { return true; }, focus() { this.focused = true; }
    });
}
const requests = [];
const scheduled = [];
const context = {
    document: {
        getElementById(id) { assert.ok(elements.has(id), `Unexpected DOM element: ${id}`); return elements.get(id); },
        addEventListener(event, callback) { if (event === 'DOMContentLoaded') callback(); }
    },
    fetch: async (path, options) => {
        requests.push({ path, options });
        return { json: async () => path === '/auth/me' ? { loggedIn: false } : { success: true, verificationRequired: true, message: 'Check email to activate' } };
    },
    console, setTimeout(callback) { scheduled.push(callback); }, sessionStorage: { setItem() {} }, window: { location: {} }
};
vm.runInNewContext(fs.readFileSync(new URL('../public/auth.js', import.meta.url), 'utf8'), context);
await Promise.resolve();
const email = elements.get('reg-email');
const error = elements.get('reg-email-error');
const initialRequests = requests.length;
for (const invalid of ['abc', 'abc@', 'abc@gmail', 'a..b@gmail.com', '.abc@gmail.com', 'abc@gmail..com', 'abc@-gmail.com']) {
    email.value = invalid;
    email.listeners.input();
    assert.ok(error.textContent.length > 0, `No immediate error for ${invalid}`);
    assert.equal(email.attributes['aria-invalid'], 'true');
    assert.equal(requests.length, initialRequests, 'Typing email must not send requests');
}
for (const valid of ['person@gmail.com', 'first.last+game@sub.example.com']) {
    email.value = valid;
    email.listeners.input();
    assert.equal(error.textContent, '');
    assert.equal(email.attributes['aria-invalid'], 'false');
}
elements.get('reg-username').value = 'ui_user';
elements.get('reg-password').value = 'ExamplePassword123';
email.value = 'invalid';
await elements.get('btn-register').onclick();
assert.equal(requests.length, initialRequests, 'Invalid email must block registration');
assert.ok(email.focused);
email.value = 'person@gmail.com';
await elements.get('btn-register').onclick();
const registration = requests.at(-1);
assert.equal(registration.path, '/auth/register');
assert.deepEqual(JSON.parse(registration.options.body), {
    username: 'ui_user', email: 'person@gmail.com', password: 'ExamplePassword123'
});
assert.equal(elements.get('btn-register').disabled, false);
assert.equal(scheduled.length, 0, 'Pending registration must not hide confirmation instructions');
assert.equal(elements.get('auth-msg').innerText, 'Check email to activate');
console.log('PASS: Immediate email errors, blocked invalid submission and link confirmation instructions without OTP.');

const verificationSource = fs.readFileSync(new URL('../public/verify-email.js', import.meta.url), 'utf8');
for (const valid of [true, false]) {
    const token = 'a'.repeat(43);
    const status = { textContent: '', style: {} };
    let cleaned = false;
    let calls = 0;
    await vm.runInNewContext(verificationSource, {
        URLSearchParams,
        document: { getElementById() { return status; } },
        window: { location: { hash: valid ? '#token=' + token : '', pathname: '/verify-email.html' },
            history: { replaceState() { cleaned = true; } } },
        fetch: async (path, options) => {
            assert.ok(cleaned, 'Token must be removed from URL before confirmation request');
            assert.equal(path, '/auth/verify-email');
            assert.equal(options.method, 'POST');
            assert.equal(JSON.parse(options.body).token, token);
            calls++;
            return { json: async () => ({ success: true, message: 'Activated' }) };
        }
    });
    assert.equal(calls, valid ? 1 : 0);
    assert.equal(status.style.color, valid ? '#10b981' : '#ef4444');
}
console.log('PASS: Email link fragment handling, automatic POST activation and invalid link message.');
