document.addEventListener("DOMContentLoaded", () => {
    checkAuth();
    const googleErrors={maintenance:'Server đang bảo trì. Vui lòng chờ đến thời gian mở lại.',google_not_configured:'Đăng nhập Google chưa được cấu hình đầy đủ trên server.',google_redirect_config:'Địa chỉ callback Google chưa hợp lệ. Kiểm tra GOOGLE_REDIRECT_URI/APP_BASE_URL.',google_state_invalid:'Phiên đăng nhập Google đã hết hạn hoặc khác trình duyệt. Hãy bấm đăng nhập Google lại.',google_cancelled:'Bạn đã hủy đăng nhập Google.',google_token_failed:'Google từ chối mã đăng nhập. Kiểm tra Client ID, Client Secret và callback đã đăng ký.',google_network_failed:'Server không kết nối được Google. Kiểm tra quyền truy cập Internet.',google_email_unverified:'Google chưa xác nhận email của tài khoản này.',google_profile_failed:'Không lấy được thông tin tài khoản Google.',google_account_restricted:'Tài khoản Google không được phép đăng nhập ở cổng người chơi.',google_account_failed:'Chưa hoàn tất được đăng nhập Google. Hãy thử lại.',google_busy:'Nhiều yêu cầu đăng nhập Google. Vui lòng thử lại sau.'};
    const errorCode=typeof URLSearchParams==='undefined'?null:new URLSearchParams(window.location.search).get('error');
    if(googleErrors[errorCode]){document.getElementById('auth-msg').innerText=googleErrors[errorCode];history.replaceState(null,'',window.location.pathname);}

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
    const registerButton = document.getElementById('btn-register');
    const registerEmail = document.getElementById('reg-email');
    const registerEmailError = document.getElementById('reg-email-error');
    let registrationBusy = false;

    function validateRegistrationEmail(required = false) {
        const email = registerEmail.value.trim();
        const pattern = new RegExp("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
        const valid = pattern.test(email) && email.length <= 254 && email.indexOf('@') <= 64 && registerEmail.checkValidity();
        const showError = !valid && (required || email.length > 0);
        registerEmailError.textContent = showError ? 'Email không hợp lệ. Ví dụ: ten@gmail.com' : '';
        registerEmail.setAttribute('aria-invalid', String(showError));
        return valid;
    }
    registerEmail.addEventListener('input', () => validateRegistrationEmail());
    registerEmail.addEventListener('blur', () => validateRegistrationEmail(true));

    document.getElementById('btn-register').onclick = async () => {
        if (registrationBusy) return;
        const user = document.getElementById('reg-username').value.trim();
        const email = document.getElementById('reg-email').value.trim();
        const pass = document.getElementById('reg-password').value;

        if (!validateRegistrationEmail(true)) {
            registerEmail.focus();
            return;
        }

        if (!user || !email || !pass) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Vui lòng nhập đầy đủ thông tin!';
            return;
        }
        if (user.length > 64 || pass.trim().length === 0 || pass.length < 8 || pass.length > 128) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Hãy nhập tên tối đa 64 ký tự và mật khẩu 8–128 ký tự.';
            return;
        }
        registrationBusy = true;
        registerButton.disabled = true;
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
                document.getElementById('reg-password').value = '';
                if (!data.verificationRequired) {
                    setTimeout(() => document.getElementById('link-login').click(), 1200);
                }
            } else {
                msgBox.style.color = '#ef4444';
                msgBox.innerText = data.error || 'Đăng ký thất bại!';
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ Java!';
        } finally {
            registrationBusy = false;
            registerButton.disabled = false;
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
                if(data.accountNotice)window.SystemClient?.showNotice(data.accountNotice,data.error);
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ!';
        }
    };

    // 3. Quên mật khẩu
    const forgotButton = document.getElementById('btn-forgot');
    document.getElementById('btn-forgot').onclick = async () => {
        const emailInput = document.getElementById('forgot-email');
        const email = emailInput.value.trim();
        if (!email || !emailInput.checkValidity()) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Vui lòng nhập email hợp lệ!';
            return;
        }

        msgBox.style.color = '#facc15';
        msgBox.innerText = 'Đang gửi mã xác nhận...';
        forgotButton.disabled = true;

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
                sessionStorage.setItem('password-reset-email', email);
                sessionStorage.setItem('password-reset-resend-at', String(Date.now() + data.retryAfter * 1000));
                window.location.href = '/reset.html';
            } else {
                msgBox.style.color = '#ef4444';
                msgBox.innerText = data.error || 'Có lỗi xảy ra!';
            }
        } catch (e) {
            msgBox.style.color = '#ef4444';
            msgBox.innerText = 'Lỗi kết nối máy chủ!';
        } finally {
            forgotButton.disabled = false;
        }
    };

    // 4. Đăng xuất
    document.getElementById('btn-logout').onclick = async (e) => {
        e.preventDefault();
        window.SystemClient?.expectLogout();
        await fetch('/auth/logout', { method: 'POST' });
        checkAuth();
    };
});

async function checkAuth() {
    try {
        const res = await fetch('/auth/me');
        const data = await res.json();
        if(res.ok===false){document.getElementById('auth-msg').innerText=data.error||'Chưa kết nối được máy chủ.';return;}
        if(data.maintenance)window.SystemClient?.setMaintenance(data.maintenance);
        window.SystemClient?.setAccount(data.loggedIn?data.user:null,data.accountNotice);

        if (data.loggedIn) {
            document.getElementById('auth-section').classList.add('hidden');
            document.getElementById('portal-section').classList.remove('hidden');
            const roleBadge = data.user.isAdmin ? ' 👑 (Admin)' : '';
            document.getElementById('display-name').innerText = data.user.username + roleBadge;
            document.body?.classList.add('signed-in');
            window.portalSignedIn?.(data.user);
        } else {
            document.getElementById('portal-section').classList.add('hidden');
            document.getElementById('auth-section').classList.remove('hidden');
            document.body?.classList.remove('signed-in');
            window.portalSignedOut?.();
        }
    } catch (e) {
        console.error('checkAuth error:', e);
    }
}
