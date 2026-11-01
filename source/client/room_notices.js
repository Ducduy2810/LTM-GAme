(function () {
    'use strict';
    window.RoomNotices = {
        show(data) {
            const region = document.getElementById('room-notices');
            if (!region || !data || !data.username) return;
            const notice = document.createElement('div');
            notice.className = 'room-notice';
            notice.textContent = data.message || `Người chơi [${data.username}] đã mất kết nối.`;
            region.appendChild(notice);
            setTimeout(() => notice.remove(), 12000);
        }
    };
})();
