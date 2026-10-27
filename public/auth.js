document.addEventListener("DOMContentLoaded", () => {
    checkAuth();

    const loginForm = document.getElementById('login-form');
    const registerForm = document.getElementById('register-form');
    const forgotForm = document.getElementById('forgot-form');
    const msgBox = document.getElementById('auth-msg');

    function showForm(formElement) {
        loginForm.classList.add('hidden');
        registerForm.classList.add('hidden');
        forgotForm.classList.add('hidden');
        formElement.classList.remove('hidden');
        msgBox.innerText = '';
    }

    document.getElementById('link-register').onclick = () => showForm(registerForm);
    document.getElementById('link-login').onclick = () => showForm(loginForm);
    document.getElementById('link-forgot').onclick = () => showForm(forgotForm);
    document.getElementById('link-back-login').onclick = () => showForm(loginForm);

    // 1. Đăng ký
    document.getElementById('btn-register').onclick = async () => {
        const user = document.getElementById('reg-username').value.trim();
        const email = document.getElementById('reg-email').value.trim();
        const pass = document.getElementById('reg-password').value;

        if (!user || !email || !pass) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Vui lòng nhập đầy đủ thông tin!';
            return;
        }

        try {
            const res = await fetch('/auth/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: user, email: email, password: pass })
            });
            const data = await res.json();
            if (data.success) {
                msgBox.style.color = '#10b981';
                msgBox.innerText = data.message;
                setTimeout(() => document.getElementById('link-login').click(), 1200);
            } else {
                msgBox.style.color = '#ef4444';
                msgBox.innerText = data.error || 'Đăng ký thất bại!';
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ Java!';
        }
    };

    // 2. Đăng nhập
    document.getElementById('btn-login').onclick = async () => {
        const user = document.getElementById('login-username').value.trim();
        const pass = document.getElementById('login-password').value;

        if (!user || !pass) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Vui lòng nhập tên đăng nhập và mật khẩu!';
            return;
        }

        try {
            const res = await fetch('/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: user, password: pass })
            });
            const data = await res.json();
            if (data.success) {
                checkAuth();
            } else {
                msgBox.style.color = '#ef4444';
                msgBox.innerText = data.error || 'Sai tên đăng nhập hoặc mật khẩu!';
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ!';
        }
    };

    // 3. Quên mật khẩu
    document.getElementById('btn-forgot').onclick = async () => {
        const email = document.getElementById('forgot-email').value.trim();
        if (!email) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Vui lòng nhập email!';
            return;
        }

        msgBox.style.color = '#facc15';
        msgBox.innerText = 'Đang xử lý yêu cầu...';

        try {
            const res = await fetch('/auth/forgot', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: email })
            });
            const data = await res.json();
            if (data.success) {
                msgBox.style.color = '#10b981';
                msgBox.innerText = data.message;
            } else {
                msgBox.style.color = '#ef4444';
                msgBox.innerText = data.error || 'Có lỗi xảy ra!';
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ!';
        }
    };

    // 4. Đăng xuất
    document.getElementById('btn-logout').onclick = async (e) => {
        e.preventDefault();
        await fetch('/auth/logout', { method: 'POST' });
        checkAuth();
    };
});

async function checkAuth() {
    try {
        const res = await fetch('/auth/me');
        const data = await res.json();

        if (data.loggedIn) {
            document.getElementById('auth-section').classList.add('hidden');
            document.getElementById('portal-section').classList.remove('hidden');
            const roleBadge = data.user.isAdmin ? ' 👑 (Admin)' : '';
            document.getElementById('display-name').innerText = data.user.username + roleBadge;
            document.getElementById('question-bank-link').classList.toggle('hidden', !data.user.isAdmin && !['admin', 'DuyNguyen'].includes(data.user.username));
        } else {
            document.getElementById('portal-section').classList.add('hidden');
            document.getElementById('auth-section').classList.remove('hidden');
        }
    } catch (e) {
        console.error('checkAuth error:', e);
    }
}
