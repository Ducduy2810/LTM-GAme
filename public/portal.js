(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const games = [
    {id:'arena',key:'ARENA',name:'Đấu Trường 100',icon:'🏛️',tag:'TRẮC NGHIỆM · LOẠI ĐỐI THỦ',desc:'Một người chơi chính, nhiều đối thủ. Chinh phục từng câu hỏi để tiến xa hơn.'},
    {id:'wheel',key:'WHEEL',name:'Chiếc Nón Kỳ Diệu',icon:'🎡',tag:'Ô CHỮ · TƯ DUY NGÔN NGỮ',desc:'Quay nón, mở từng chữ cái và khám phá kiến thức ẩn sau mỗi ô chữ.'},
    {id:'survival',key:'SURVIVAL',name:'Đấu Trường Sinh Tử',icon:'🛡️',tag:'SINH TỒN · THỬ THÁCH KIẾN THỨC',desc:'Cùng trả lời, bảo toàn HP và trở thành người sống sót cuối cùng.'}
  ];
  const names = {home:'Trang chủ',practice:'Phòng học',daily:'Thử thách hằng ngày',games:'Khu giải trí',bot:'Chơi với máy',pvp:'Đấu thường PvP',rank:'Đấu xếp hạng',history:'Lịch sử trận',leaderboard:'Bảng xếp hạng',profile:'Hồ sơ của tôi'};
  let currentUser, overview, questionId, answerBusy=false, questionBusy=false;
  let refreshGeneration=0;
  const el = (tag,text,cls) => {const n=document.createElement(tag); if(text!==undefined)n.textContent=text; if(cls)n.className=cls; return n;};
  function message(text) { $('portal-message').textContent=text; $('portal-message').classList.toggle('hidden',!text); }
  async function api(path,body) {
    const response=await fetch(path, body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
    const data=await response.json();
    if(!response.ok) {
      if(response.status===401) {currentUser=null;checkAuth();}
      throw new Error(data.error || 'Chưa kết nối được máy chủ.');
    }
    return data;
  }
  function show(view, changeHash=true) {
    if(!names[view]) view='home';
    document.querySelectorAll('.view').forEach(n=>n.classList.toggle('hidden',n.id!==`view-${view}`));
    document.querySelectorAll('.nav-item').forEach(n=>n.classList.toggle('active',n.dataset.view===view));
    $('breadcrumb-name').textContent=names[view];
    if(changeHash) history.replaceState(null,'',`#${view}`);
    if(view==='leaderboard'){loadLegacy();loadRankBoard();}
    if(view==='history')loadRaceHistory();
    if(view==='history' || view==='pvp') refresh();
    if(view==='profile')loadProfile();
    message('');
  }
  document.querySelectorAll('[data-view]').forEach(n=>n.addEventListener('click',()=>show(n.dataset.view)));
  document.querySelectorAll('.nav-item').forEach(n=>{if(n.dataset.view){n.setAttribute('aria-label',names[n.dataset.view]);n.title=names[n.dataset.view];}});
  window.addEventListener('hashchange',()=>show(location.hash.slice(1),false));
  function gameCards(target,mode) {
    const container=$(target);container.replaceChildren();
    if(mode==='bot'||mode==='pvp'){
      const card=el('article',undefined,'game-card'),body=el('div',undefined,'game-body');
      body.append(el('span','LẬP TRÌNH MẠNG · ĐUA ĐIỂM','tag'),el('h2','Đường đua Kiến thức'),el('p',mode==='bot'?'Tạo phòng luyện với máy, chọn số bot và thời gian để ôn kiến thức theo nhịp của bạn.':'Tìm đối thủ tự động hoặc tạo phòng riêng, chia sẻ mã với bạn bè và đua điểm trực tiếp.'));
      const link=el('a',mode==='bot'?'Tạo phòng luyện máy →':'Tìm trận / Tạo phòng →','button primary');link.href='/race.html?mode='+(mode==='bot'?'bot':'casual');
      body.append(el('span','KHÔNG TÍNH RANK','tag'),link);card.append(el('div','🏁','game-art'),body);container.append(card);
    }
    games.filter(g=>mode!=='bot'||g.id!=='survival').forEach(g=>{
      const card=el('article',undefined,'game-card');
      const art=el('div',g.icon,'game-art');art.setAttribute('aria-hidden','true');
      const body=el('div',undefined,'game-body');
      body.append(el('span',g.tag,'tag'),el('h2',g.name),el('p',g.desc));
      const link=el('a',mode==='bot'?'Luyện cùng máy →':mode==='pvp'?'Vào sảnh PvP →':'Vào sảnh chơi →','button primary');
      link.href=`/${g.id}.html?mode=${mode}`;
      body.append(el('span','GIẢI TRÍ · KHÔNG TÍNH RANK','tag'),link);card.append(art,body);container.append(card);
    });
  }
  gameCards('games-list','casual');gameCards('bot-list','bot');gameCards('pvp-list','pvp');
  const statusNames={ACTIVE:'Đang chơi',COMPLETED:'Hoàn thành',CANCELLED:'Đã hủy',INTERRUPTED:'Bị gián đoạn'};
  const outcomes={WIN:'Thắng',LOSS:'Kết thúc',LEFT:'Đã rời trận',CANCELLED:'Đã hủy'};
  function renderHistory(target,rows) {
    const container=$(target);container.replaceChildren();
    if(!rows.length){container.append(el('div','Chưa có trận nào. Hãy ghé khu giải trí để bắt đầu!','empty-state'));return;}
    rows.forEach(r=>{
      const game=games.find(g=>g.key===r.game_type);
      const row=el('div',undefined,'history-row'),meta=el('div',undefined,'history-meta');
      meta.append(el('strong',game?.name || r.game_type),el('small',`${new Date(r.started_at).toLocaleString('vi-VN')} · ${r.mode==='BOT'?'Có máy':'Đấu thường'} · ${r.room_code}`));
      const result=el('div',undefined,'history-result');
      result.append(el('div',r.status==='COMPLETED'?(outcomes[r.outcome]||r.outcome):statusNames[r.status]),el('small',r.status==='COMPLETED'?`${r.score} điểm · Không tính rank`:'Không tính rank'));
      row.append(el('span',game?.icon||'⚑','history-icon'),meta,result);container.append(row);
    });
  }
  function renderRooms(target,rooms,admin=false) {
    const container=$(target);container.replaceChildren();
    container.append(el('h2',admin?'Các phòng trên máy chủ':'Phòng đang mở'));
    for(const game of games){
      const group=el('div',undefined,'room-group');group.append(el('h3',game.name));
      const list=rooms[game.id]||[];
      if(!list.length)group.append(el('p','Chưa có phòng.','muted'));
      list.forEach(r=>{
        const row=el('div',undefined,'room-row');
        row.append(el('span',`${r.roomName} · ${r.roomCode}`),el('small',`${r.playerCount??0} người · ${r.gameState==='WAITING'?'Đang chờ':r.gameState||''}`,'muted'));
        const link=el('a','Vào sảnh →');link.href=`/${game.id}.html?mode=pvp`;row.append(link);group.append(row);
      });container.append(group);
    }
  }
  async function refresh() {
    if(!currentUser)return;
    const generation=++refreshGeneration;
    try{
      const [data,catalog]=await Promise.all([api('/api/portal/overview'),api('/api/catalog')]);
      if(generation!==refreshGeneration||!currentUser)return;
      overview=data;
      const selector=$('practice-topic'),selected=selector.value;
      selector.replaceChildren(el('option','Tất cả chủ đề'));selector.firstChild.value='ALL';
      catalog.topics.filter(t=>t.scope==='QUESTION').forEach(t=>{const o=el('option',t.name);o.value=t.code;selector.append(o);});
      if([...selector.options].some(o=>o.value===selected))selector.value=selected;
      $('server-state').textContent='Máy chủ trực tuyến';$('server-state').classList.remove('offline');
      $('recent-history').classList.remove('empty-state');
      renderHistory('recent-history',data.history.slice(0,3));renderHistory('full-history',data.history);
      renderRooms('room-summary',data.rooms);
    }catch(e){
      if(generation!==refreshGeneration)return;
      $('server-state').textContent='Mất kết nối';$('server-state').classList.add('offline');
      for(const id of ['recent-history','full-history','room-summary']) $(id).replaceChildren(el('p','Không tải được dữ liệu. Hãy thử làm mới.','muted'));
      message(e.message);
    }
  }
  async function loadLegacy(){
    $('legacy-leaderboard').textContent='Đang tải…';
    try{const data=await api('/api/leaderboard');const root=$('legacy-leaderboard');root.replaceChildren();
      if(!data.leaderboard.length)root.append(el('p','Chưa có thành tích cũ.','muted'));
      data.leaderboard.forEach((r,i)=>{const row=el('div',undefined,'score-row');row.append(el('span',`#${i+1}`),el('strong',r.username),el('span',`${r.rankPoints} điểm cũ`));root.append(row);});
    }catch(e){$('legacy-leaderboard').textContent=e.message;}
  }
  async function loadRankBoard(){
    const root=$('portal-ranked-board');if(!root)return;root.textContent='Đang tải…';
    try{const data=await api('/api/race/overview');root.replaceChildren();if(!data.leaderboard.length)root.append(el('p','Chưa có trận rank hoàn thành. Hãy mở đường đua để bắt đầu!','muted'));data.leaderboard.forEach(p=>{const row=el('div',undefined,'score-row');row.append(el('span','#'+p.position),el('strong',p.username),el('span',(p.title?p.title+' · ':'')+p.rankName+' · '+p.current_points+' RP'));root.append(row);});}catch(e){root.textContent=e.message;}
  }
  async function loadRaceHistory(){
    const root=$('portal-race-history');if(!root||!currentUser)return;root.textContent='Đang tải…';
    try{const data=await api('/api/race/history');root.replaceChildren();if(!data.items.length)root.append(el('p','Chưa có trận Đường đua nào.','muted'));for(const r of data.items){const row=el('div',undefined,'history-row'),info=el('div',undefined,'history-meta');info.append(el('strong',r.room_name),el('small',`${new Date(r.started_at).toLocaleString('vi-VN')} · ${r.mode==='RANKED'?'Đấu rank':r.mode==='DAILY'?'Thử thách ngày':'Đấu thường/luyện máy'} · ${statusNames[r.status]||r.status}`));row.append(info);if(!['ACTIVE','WAITING'].includes(r.status)){const a=el('a','Ôn lại →');a.href='/race.html?review='+encodeURIComponent(r.id);row.append(a);}root.append(row);}}catch(e){root.textContent=e.message;}
  }
  $('refresh-history').addEventListener('click',()=>{refresh();loadRaceHistory();});
  function renderRank(rank={}){
    $('profile-current-rank').textContent=rank.title||rank.currentName||'Đồng 3';$('profile-highest-rank').textContent=rank.highestTitle||rank.highestName||'Đồng 3';
    $('profile-current-points').textContent=(rank.current_points||0)+' RP';$('profile-highest-points').textContent=(rank.highest_points||0)+' RP';
  }
  async function loadProfile(){
    if(!currentUser)return;try{const data=await api('/auth/profile');if(!currentUser||currentUser.username!==data.username)return;renderRank(data.rank);$('google-password-help').classList.toggle('hidden',!data.googleLinked);}catch(e){message(e.message);}
  }
  $('player-password-form').addEventListener('submit',async event=>{
    event.preventDefault();const feedback=$('player-password-message');feedback.textContent='';
    if($('player-new-password').value!==$('player-confirm-password').value){feedback.textContent='Mật khẩu xác nhận không khớp.';return;}
    $('player-password-submit').disabled=true;window.SystemClient?.expectLogout();
    try{
      const response=await fetch('/auth/change-password',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':currentUser?.csrfToken||''},body:JSON.stringify({currentPassword:$('player-current-password').value,newPassword:$('player-new-password').value})});
      const data=await response.json();if(!response.ok)throw new Error(data.error||'Không đổi được mật khẩu.');
      $('player-password-form').reset();await checkAuth();document.getElementById('auth-msg').textContent=data.message;
    }catch(e){window.SystemClient?.expectLogout(false);feedback.textContent=e.message;}finally{$('player-password-submit').disabled=false;}
  });
  $('player-password-reset').addEventListener('click',async()=>{
    const button=$('player-password-reset');button.disabled=true;
    try{const email=currentUser?.email;if(!email)throw new Error('Tài khoản chưa có email.');const data=await api('/auth/forgot',{email});sessionStorage.setItem('password-reset-email',email);sessionStorage.setItem('password-reset-resend-at',String(Date.now()+(data.retryAfter||60)*1000));location.assign('/reset.html');}
    catch(e){$('player-password-message').textContent=e.message;}finally{button.disabled=false;}
  });
  $('practice-next').addEventListener('click',async()=>{
    if(questionBusy||answerBusy)return;
    questionBusy=true;$('practice-next').disabled=true;questionId=null;$('practice-options').replaceChildren();
    $('practice-feedback').textContent='Đang lấy câu hỏi…';
    try{
      const q=await api('/api/practice/next',{topic:$('practice-topic').value,difficulty:$('practice-difficulty').value});
      questionId=q.questionId;$('practice-question').textContent=q.text;$('practice-feedback').textContent='Chọn một đáp án. Mỗi câu chỉ được chốt một lần.';
      q.options.forEach((text,index)=>{const b=el('button',`${'ABCD'[index]}. ${text}`,'answer-button');b.addEventListener('click',()=>answer(index));$('practice-options').append(b);});
    }catch(e){$('practice-feedback').textContent=e.message;}
    finally{questionBusy=false;$('practice-next').disabled=false;}
  });
  async function answer(index){
    if(!questionId||answerBusy)return;answerBusy=true;
    const buttons=[...$('practice-options').children];buttons.forEach(b=>b.disabled=true);$('practice-next').disabled=true;
    try{const result=await api('/api/practice/answer',{questionId,answer:index});questionId=null;
      buttons[result.correctIndex].classList.add('correct');if(!result.correct)buttons[index].classList.add('wrong');
      $('practice-feedback').textContent=result.correct?`Chính xác! ${result.correctText}`:`Hãy ghi nhớ: đáp án đúng là ${'ABCD'[result.correctIndex]}. ${result.correctText}`;
      if(result.explanation)$('practice-feedback').textContent+=' · '+result.explanation;
    }catch(e){questionId=null;$('practice-feedback').textContent=`${e.message} Hãy lấy câu hỏi mới.`;}
    finally{answerBusy=false;$('practice-next').disabled=false;}
  }
  window.portalSignedIn=user=>{
    currentUser=user;overview=null;questionId=null;refreshGeneration++;
    $('avatar-initial').textContent=user.username.slice(0,1).toUpperCase();
    $('profile-avatar').textContent=user.username.slice(0,1).toUpperCase();
    $('profile-name').textContent=user.username;$('profile-email').textContent=user.email||'Chưa có email';
    $('profile-role').textContent=user.isAdmin?'Quản trị viên':'Học viên';
    renderRank(user.rank);$('google-password-help').classList.toggle('hidden',!user.googleLinked);
    show(location.hash.slice(1)||'home',false);refresh();
  };
  window.portalSignedOut=()=>{currentUser=null;overview=null;questionId=null;refreshGeneration++;$('practice-options').replaceChildren();$('practice-question').textContent='Bạn đã sẵn sàng khám phá?';$('practice-feedback').textContent='Chọn chủ đề rồi lấy câu hỏi đầu tiên.';};
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
})();
