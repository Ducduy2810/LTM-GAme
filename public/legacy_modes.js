// A lobby preference, never a claim to competitive ranking.
(() => {
  const mode=new URLSearchParams(location.search).get('mode');
  const bot=mode==='bot';
  const notice=document.createElement('div');
  notice.style.cssText='position:relative;z-index:10;background:#183e36;color:#e0e9ce;padding:9px 18px;text-align:center;font:12px/1.6 "Segoe UI",sans-serif;';
  notice.textContent=bot?'Chơi với máy · Chọn Tạo phòng để cấu hình bot. Không tính rank.':mode==='pvp'?'Đấu thường PvP · Tạo phòng không bot và mời bạn bằng mã phòng. Không tính rank.':'Khu giải trí · Trò chơi này không cộng hoặc trừ rank.';
  const home=document.createElement('a');home.href='/';home.textContent=' ← Về bản đồ';home.style.cssText='color:#efd28c;margin-left:15px;text-decoration:none;';notice.append(home);
  document.body.prepend(notice);
  document.getElementById('btn-open-create-modal')?.addEventListener('click',()=>{
    const bots=document.getElementById('create-bot-count');
    if(bots && (mode==='bot'||mode==='pvp')){
      bots.value=mode==='pvp'?'0':location.pathname.includes('arena')?'10':'1';
    }
  });
})();
