const emailInput = document.getElementById('reset-email');
const codeInput = document.getElementById('reset-code');
const resetButton = document.getElementById('btn-reset');
const resendButton = document.getElementById('btn-resend');
const msgBox = document.getElementById('msg');
let busy = false;
let completed = false;
let resendAt = Number(sessionStorage.getItem('password-reset-resend-at')) || 0;
emailInput.value = sessionStorage.getItem('password-reset-email') || '';

function showMessage(message, success = false) {
    msgBox.textContent = message;
    msgBox.style.color = success ? '#10b981' : '#ef4444';
}

function updateButtons() {
    const seconds = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000));
    resendButton.disabled = busy || completed || seconds > 0;
    resetButton.disabled = busy || completed;
    resendButton.textContent = seconds > 0 ? `Gửi lại mã sau ${seconds}s` : 'Gửi Lại Mã';
}
updateButtons();
setInterval(updateButtons, 1000);

document.getElementById('reset-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy || completed) return;
    const newPassword = document.getElementById('new-password').value;
    if (newPassword !== document.getElementById('confirm-password').value) {
        showMessage('Hai mật khẩu chưa trùng khớp.');
        return;
    }
    busy = true;
    updateButtons();
    showMessage('Đang cập nhật mật khẩu...', true);
    try {
        const response = await fetch('/auth/reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: emailInput.value.trim(), code: codeInput.value.trim(), newPassword })
        });
        const data = await response.json();
        showMessage(data.message || data.error || 'Chưa thể đổi mật khẩu.', data.success);
        if (data.success) {
            completed = true;
            sessionStorage.removeItem('password-reset-email');
            sessionStorage.removeItem('password-reset-resend-at');
            setTimeout(() => { window.location.href = '/'; }, 1800);
        }
    } catch (error) {
        showMessage('Lỗi kết nối máy chủ. Vui lòng thử lại.');
    } finally {
        busy = false;
        updateButtons();
    }
});

resendButton.addEventListener('click', async () => {
    if (busy || completed || Date.now() < resendAt) return;
    if (!emailInput.value.trim() || !emailInput.reportValidity()) return;
    busy = true;
    updateButtons();
    showMessage('Đang gửi mã xác nhận...', true);
    try {
        const response = await fetch('/auth/forgot', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: emailInput.value.trim() })
        });
        const data = await response.json();
        showMessage(data.message || data.error || 'Chưa gửi được mã.', data.success);
        if (Number.isFinite(data.retryAfter) && data.retryAfter > 0) {
            resendAt = Date.now() + data.retryAfter * 1000;
            sessionStorage.setItem('password-reset-resend-at', String(resendAt));
        }
        if (data.success) {
            sessionStorage.setItem('password-reset-email', emailInput.value.trim());
            codeInput.value = '';
            codeInput.focus();
        }
    } catch (error) {
        showMessage('Lỗi kết nối máy chủ. Vui lòng thử lại.');
    } finally {
        busy = false;
        updateButtons();
    }
});
