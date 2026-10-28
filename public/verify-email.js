(async () => {
    const message = document.getElementById('verification-message');
    const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
    // Token trong fragment không đi vào request HTTP/log và được xóa khỏi URL ngay.
    window.history.replaceState(null, '', window.location.pathname);
    if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
        message.textContent = 'Liên kết xác nhận không hợp lệ. Hãy mở đúng liên kết trong email đăng ký.';
        message.style.color = '#ef4444';
        return;
    }
    try {
        const response = await fetch('/auth/verify-email', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token })
        });
        const data = await response.json();
        message.textContent = data.message || data.error || 'Chưa thể xác nhận email.';
        message.style.color = data.success ? '#10b981' : '#ef4444';
    } catch (error) {
        message.textContent = 'Không kết nối được máy chủ. Hãy mở lại liên kết từ email để thử lại.';
        message.style.color = '#ef4444';
    }
})();
