(() => {
  'use strict';
  const $=id=>document.getElementById(id),el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);if(cls)n.className=cls;return n;};
  let socket,user,overview,state={phase:'IDLE'},mode='RANKED',offset=0,answerBusy=false,review=null,reviewId='',terminalSeen='',reconnectTimer,leaving=false,lastQuestion='',pendingHome=null;
  const cancelledMatches=new Set();
  const modeNames={RANKED:'Đấu rank',CASUAL:'Đấu thường',BOT:'Luyện với máy',DAILY:'Thử thách ngày'};
  const statusNames={WAITING:'Đang chờ',ACTIVE:'Đang thi đấu',COMPLETED:'Hoàn thành',CANCELLED:'Đã hủy',INTERRUPTED:'Gián đoạn'};
  function message(text){$('race-message').textContent=text||'';$('race-message').classList.toggle('hidden',!text);}
  function button(text,fn,cls='text-button'){const b=el('button',text,cls);b.type='button';b.addEventListener('click',fn);return b;}
  async function api(path){const r=await fetch(path,{cache:'no-store'}),data=await r.json();if(r.status===401){location.assign('/');throw new Error('Vui lòng đăng nhập.');}if(!r.ok)throw new Error(data.error||'Không tải được dữ liệu.');return data;}
  function send(type,extra={}){if(!socket||socket.readyState!==WebSocket.OPEN){message('Đang kết nối lại máy chủ. Hãy chờ trong giây lát.');return false;}socket.send(JSON.stringify({type,...extra}));return true;}
  function choose(next){
    mode=modeNames[next]?next:'RANKED';document.body.dataset.mode=mode.toLowerCase();document.title=(mode==='DAILY'?'Thử thách hằng ngày':'Đường đua · '+modeNames[mode])+' · Vũ Môn';
    const parent={RANKED:'rank',CASUAL:'pvp',BOT:'bot',DAILY:'daily'}[mode];$('back-to-portal').href='/#'+parent;$('back-to-portal').textContent='← '+{RANKED:'Đấu xếp hạng',CASUAL:'Đấu thường PvP',BOT:'Chơi với máy',DAILY:'Thử thách hằng ngày'}[mode];
    $('page-title').textContent={RANKED:'Đường đua xếp hạng',CASUAL:'Đường đua đấu thường',BOT:'Đường đua luyện với máy',DAILY:'Thử thách hằng ngày'}[mode];
    $('mode-brand-label').textContent=mode==='DAILY'?'THỬ THÁCH HẰNG NGÀY':'ĐƯỜNG ĐUA KIẾN THỨC';
    $('page-description').textContent={RANKED:'Thi đấu trực tiếp, tích lũy RP và chinh phục các bậc rank.',CASUAL:'Tìm đối thủ tự động hoặc tạo phòng riêng để cùng bạn bè đua kiến thức.',BOT:'Chọn số bot, số câu và thời gian để luyện tập theo nhịp của bạn.',DAILY:'Một lượt mỗi ngày. Chinh phục 10 câu Khó và nhận thẻ thưởng.'}[mode];
    $('private-title').textContent=mode==='BOT'?'Tạo phòng luyện với máy':'Tạo phòng đấu thường';$('private-description').textContent=mode==='BOT'?'Máy chủ điều khiển bot. Kết quả luyện tập không cộng hoặc trừ RP.':'Chia sẻ mã phòng để mời bạn bè. Kết quả đấu thường không cộng hoặc trừ RP.';
    $('rank-summary').classList.toggle('hidden',mode!=='RANKED');$('rank-board-panel').classList.toggle('hidden',mode!=='RANKED');$('launch-panel').classList.toggle('hidden',!['RANKED','CASUAL','DAILY'].includes(mode));
    $('bot-count-field').classList.toggle('hidden',mode!=='BOT');$('bot-count').disabled=mode!=='BOT';$('bot-count').value=mode==='BOT'?'3':'0';$('bot-count').querySelector('[value="0"]').disabled=mode==='BOT';
    $('join-form').classList.toggle('hidden',mode!=='CASUAL');$('rooms-panel').classList.toggle('hidden',mode!=='CASUAL');$('history-title').textContent='Lịch sử '+modeNames[mode].toLowerCase();
    renderMenu();
  }
  function renderMenu(){
    if(!overview)return;const s=overview.settings,a=overview.account;
    const titles={RANKED:'Đường đua xếp hạng',CASUAL:'Tìm đối thủ đấu thường',BOT:'Khởi động cùng máy',DAILY:'Thử thách 10 câu sinh tử'};
    const desc={RANKED:'Ghép tự động với đối thủ có mức rank gần bạn. Câu hỏi và thời gian giống nhau cho mọi người.',CASUAL:'Tự động tìm người chơi khác đang đấu thường. Đủ người sẽ bắt đầu ngay; kết quả không cộng hoặc trừ RP.',BOT:'Tự tạo một phòng có bot, chọn số câu và thời gian để luyện tập.',DAILY:'Một lượt mỗi ngày, 10 câu hỏi Khó. Chơi để nhận thẻ, không ảnh hưởng đến rank.'};
    $('mode-title').textContent=titles[mode];$('mode-description').textContent=desc[mode];$('mode-eyebrow').textContent=mode==='RANKED'?'HÀNH TRÌNH LEO HẠNG':mode==='CASUAL'?'GHÉP TRẬN TỰ ĐỘNG':'HỌC HỎI THEO NHỊP CỦA BẠN';
    const facts=mode==='DAILY'?['10 câu Khó',`${s.dailyTimeLimit} giây/câu`,'1 lượt/ngày · giờ Việt Nam']:mode==='BOT'?['Không tính RP','Tự chọn thời gian','Bot được đánh dấu rõ']:[`${s.questionCount} câu`,`${s.timeLimit} giây/câu`,`${s.targetPlayers} người · điền bot sau ${s.queueSeconds}s`];
    $('mode-facts').replaceChildren(...facts.map(t=>el('span',t,'fact')));$('card-options').classList.toggle('hidden',mode!=='RANKED');$('private-panel').classList.toggle('hidden',!['CASUAL','BOT'].includes(mode));$('daily-info').classList.toggle('hidden',mode!=='DAILY');
    $('shield-count').textContent=a.cards.shields;$('double-count').textContent=a.cards.doubles;$('use-shield').disabled=!a.cards.shields;$('use-double').disabled=!a.cards.doubles;
    if(!a.cards.shields)$('use-shield').checked=false;if(!a.cards.doubles)$('use-double').checked=false;
    $('play-button').textContent={RANKED:'Bắt đầu ghép rank →',CASUAL:'Tìm trận đấu thường →',BOT:'Tạo phòng luyện với máy →',DAILY:'Bắt đầu thử thách →'}[mode];
    $('play-button').disabled=!s.enabled||mode==='RANKED'&&!s.rankedEnabled||mode==='DAILY'&&(!s.dailyEnabled||a.daily.used);
    $('daily-status').textContent=mode==='DAILY'?(a.daily.used?'Bạn đã dùng lượt hôm nay. Xem kết quả trong lịch sử.':'Lượt hôm nay của bạn vẫn còn.'):mode==='CASUAL'?'Chỉ ghép với hàng chờ đấu thường. Không tính RP và không dùng thẻ. Bạn có thể hủy khi đang tìm trận.':'Thẻ chỉ dùng khi có RP phù hợp; trận bị hủy không tiêu tốn thẻ. Không cộng RP nếu rời trận hoặc không đúng câu nào.';
    $('daily-reward-copy').textContent=`Đúng ít nhất ${s.dailyCorrect}/10 câu nhận ${s.shieldReward} thẻ bảo vệ và ${s.doubleReward} thẻ x2 RP.`;
    $('create-button').textContent=mode==='BOT'?'Tạo phòng luyện với máy':'Tạo phòng đấu thường';
    $('create-button').disabled=!s.enabled;
  }
  function account(data){
    const a=data.account,current=a.current,highest=a.highest;$('current-rank').textContent=a.title||current.name;$('rank-rp').textContent=current.step===14?`${current.rp} RP · Kim Cương 1`:`${current.rp} / 100 RP`;
    $('highest-rank').textContent='Cao nhất: '+(a.highestTitle||highest.name)+` · ${highest.rp} RP`;$('rank-progress').style.width=(current.step===14?100:current.rp)+'%';$('rank-emblem').className='rank-emblem '+current.tier.toLowerCase();
    $('rank-leaderboard').replaceChildren();if(!data.leaderboard.length)$('rank-leaderboard').append(el('p','Chưa có kết quả rank. Hãy là người mở đường!','empty'));
    data.leaderboard.forEach(p=>{const row=el('div',undefined,'leader-entry'),info=el('div');info.append(el('strong',`#${p.position} · ${p.username}`),el('small',(p.title?p.title+' · ':'')+p.rankName));row.append(info,el('strong',`${p.current_points} RP`));$('rank-leaderboard').append(row);});
    const rooms=data.rooms.filter(r=>r.bots===0);$('open-rooms').replaceChildren();if(!rooms.length)$('open-rooms').append(el('p','Chưa có phòng chờ. Bạn có thể tạo phòng và chia sẻ mã cho bạn bè.','empty'));
    rooms.forEach(r=>{const row=el('div',undefined,'open-room'),info=el('div');info.append(el('strong',r.roomName),el('small',`${r.roomCode} · ${r.players} người · ${r.questionCount} câu/${r.seconds}s`));row.append(info,button('Tham gia →',()=>send('race_join',{roomCode:r.roomCode,mode:'CASUAL'})));$('open-rooms').append(row);});
  }
  async function refresh(){try{overview=await api('/api/race/overview');account(overview);renderMenu();}catch(e){message(e.message);}}
  async function history(){try{const data=await api('/api/race/history');$('race-history').replaceChildren();if(!data.items.length)$('race-history').append(el('p','Chưa có trận Đường đua nào.','empty'));
    const items=data.items.filter(r=>r.mode===mode);if(data.items.length&&!items.length)$('race-history').append(el('p','Chưa có trận ở chế độ này.','empty'));
    for(const r of items){const row=el('div',undefined,'history-entry'),info=el('div');const forfeited=r.mode==='RANKED'&&r.forfeited===1&&['ACTIVE','COMPLETED'].includes(r.status);info.append(el('strong',modeNames[r.mode]+' · '+r.room_name),el('small',new Date(r.started_at).toLocaleString('vi-VN')+' · '+(forfeited?'Thua · Bỏ cuộc':statusNames[r.status])),el('small',r.status==='COMPLETED'?`Hạng ${r.placement} · ${r.correct_count} câu đúng · ${r.mode==='RANKED'?((r.result.rpDelta>=0?'+':'')+r.result.rpDelta+' RP'):'Không tính rank'}`:forfeited?'Không thể vào lại trận. RP được chốt khi trận kết thúc.':r.reason||'Chưa hoàn thành'));
      row.append(info);if(!['ACTIVE','WAITING'].includes(r.status))row.append(button('Ôn lại →',()=>loadReview(r.id)));$('race-history').append(row);}
  }catch(e){message(e.message);}}
  async function loadReview(id){try{const data=await api('/api/race/review?id='+encodeURIComponent(id));if(!new URLSearchParams(location.search).has('mode'))choose(data.match.mode);review=data;reviewId=id;$('review-panel').classList.remove('hidden');renderReview();$('review-panel').scrollIntoView({behavior:'smooth',block:'start'});}catch(e){message(e.message);}}
  function renderReview(){const rows=review?.questions||[];const selected=rows.filter(r=>!$('wrong-only').checked||!r.correct);$('review-list').replaceChildren();if(!selected.length)$('review-list').append(el('p',rows.length?'Bạn không có câu sai trong bộ lọc này. Tiếp tục phát huy!':'Trận chưa có câu đã hoàn thành để ôn lại.','empty'));
    for(const r of selected){const q=r.question,box=el('article',undefined,'review-question');box.append(el('h3',`${r.round_no}. ${q.text}`),el('p','Bạn chọn: '+(r.answer===null?'Hết giờ / chưa trả lời':`${'ABCD'[r.answer]}. ${q.options[r.answer]}`)),el('p',`Đáp án đúng: ${'ABCD'[q.correct]}. ${q.options[q.correct]}`,'correct-answer'),el('p',q.explanation));$('review-list').append(box);}}
  function render(data){
    if(data.queueCancelled)window.SystemClient?.showCancellation(data);
    if(data.phase==='CANCELLED'&&!cancelledMatches.has(data.matchId)){cancelledMatches.add(data.matchId);window.SystemClient?.showCancellation(data);}
    if(pendingHome&&data.phase==='IDLE'&&data.matchId===pendingHome.matchId){const destination=pendingHome.href;pendingHome=null;leaving=true;location.assign(destination);return;}
    $('active-session-panel').classList.toggle('hidden',data.phase!=='OTHER_MODE');
    if(data.phase==='OTHER_MODE'){
      state=data;$('menu-panel').classList.add('hidden');$('queue-panel').classList.add('hidden');$('match-panel').classList.add('hidden');$('active-session-message').textContent=data.message;$('resume-other-mode').href='/race.html?mode='+data.activeMode.toLowerCase();return;
    }
    state=data;answerBusy=false;if(data.serverTime)offset=data.serverTime-Date.now();if(data.message)message(data.message);
    const idle=data.phase==='IDLE',queued=data.phase==='QUEUED';$('menu-panel').classList.toggle('hidden',!idle);$('queue-panel').classList.toggle('hidden',!queued);$('match-panel').classList.toggle('hidden',idle||queued);
    if(idle){refresh();history();return;}
    if(queued){$('queue-title').textContent=data.mode==='RANKED'?'Ghép theo rank gần nhau':'Tìm bạn cùng đua';updateTimer();return;}
    $('match-mode').textContent=(modeNames[data.mode]||data.mode)+(data.mode==='RANKED'?' · CÓ TÍNH RP':' · KHÔNG TÍNH RP');$('match-title').textContent=data.roomName;$('room-code').textContent='Mã phòng: '+data.roomCode;
    $('race-lanes').replaceChildren();
    [...data.players].sort((a,b)=>a.placement-b.placement||b.score-a.score).forEach(p=>{const row=el('div',undefined,'race-lane'+(p.username===data.me?' me':'')),name=el('div',undefined,'lane-name'),track=el('div',undefined,'lane-track'),fill=el('div',undefined,'lane-fill'),score=el('div',p.score+'đ','lane-score');
      name.append(el('strong',p.username),el('small',p.forfeited?'Đã rời trận':p.bot?'BOT':p.answered?'✓ Đã chốt':p.online?'Đang kết nối':'Mất kết nối · có thể quay lại'));fill.style.width=Math.max(5,p.score/(data.questionCount*3)*100)+'%';fill.append(el('span',p.placement,'lane-marker'));track.append(fill);score.append(el('small',p.correct+' câu đúng'));row.append(name,track,score);
      if(data.host===data.me&&p.username!==data.me&&!p.bot&&!p.forfeited&&['WAITING','QUESTION','RESULT'].includes(data.phase))name.append(button('Kick',()=>{if(confirm('Đưa '+p.username+' ra khỏi phòng?'))send('race_kick',{targetUsername:p.username});},'text-button danger'));
      $('race-lanes').append(row);
    });
    const waiting=data.phase==='WAITING',playing=['QUESTION','RESULT'].includes(data.phase),ended=['COMPLETED','CANCELLED'].includes(data.phase);
    $('lobby-controls').classList.toggle('hidden',!waiting);$('question-panel').classList.toggle('hidden',!playing);$('result-panel').classList.toggle('hidden',!ended);
    $('start-match').classList.toggle('hidden',data.host!==data.me);$('delete-room').classList.toggle('hidden',data.host!==data.me);$('lobby-description').textContent='Chia sẻ mã phòng cho bạn bè. '+(data.host===data.me?'Bạn là chủ phòng: bắt đầu khi đã đủ ít nhất 2 người/bot.':'Đang chờ chủ phòng bắt đầu.');
    if(playing){const q=data.question;$('round-counter').textContent=`CÂU ${data.round} / ${data.questionCount}`;$('difficulty-label').textContent={EASY:'Dễ',MEDIUM:'Trung bình',HARD:'Khó'}[q.difficulty];$('question-text').textContent=q.text;$('answer-options').replaceChildren();
      q.options.forEach((text,index)=>{const b=button('',()=>{if(answerBusy||state.phase!=='QUESTION'||state.myAnswer>=0)return;answerBusy=true;document.querySelectorAll('.answer-option').forEach(n=>n.disabled=true);if(!send('race_answer',{matchId:data.matchId,questionToken:q.token,answer:index}))answerBusy=false;},'answer-option');b.append(el('span','ABCD'[index]),el('span',text));b.disabled=data.phase!=='QUESTION'||data.myAnswer>=0;b.classList.toggle('chosen',data.myAnswer===index);if(data.solution){b.classList.toggle('correct',data.solution.correctIndex===index);b.classList.toggle('incorrect',data.myAnswer===index&&data.solution.correctIndex!==index);}$('answer-options').append(b);});
      $('question-feedback').textContent=data.solution?((data.solution.correct?'Chính xác! ':data.myAnswer<0?'Bạn chưa chốt đáp án. ':'Chưa chính xác. ')+`Đáp án: ${'ABCD'[data.solution.correctIndex]}. ${q.options[data.solution.correctIndex]}\n`+data.solution.explanation):data.myAnswer>=0?'Đã chốt đáp án. Đang chờ các đối thủ; điểm được công bố cùng lúc khi kết thúc câu.':'Chọn một đáp án. Chỉ được chốt một lần.';updateTimer();
      if(data.phase==='QUESTION'&&lastQuestion!==q.token){lastQuestion=q.token;requestAnimationFrame(()=>$('question-panel').scrollIntoView({behavior:'smooth',block:'center'}));}
    }
    if(ended){const me=data.players.find(p=>p.username===data.me),result=me.result||{};$('result-title').textContent=data.phase==='CANCELLED'?'Trận đã được hủy':data.mode==='DAILY'?(result.rewarded?'Hoàn thành thử thách!':'Hẹn bạn ở thử thách ngày mai'):result.promoted?'Thăng hạng · '+result.after.name:result.demoted?'Tiếp tục hành trình · '+result.after.name:'Hoàn thành · Hạng '+me.placement;
      $('result-rp').textContent=data.phase==='CANCELLED'?'Không thay đổi RP · không dùng thẻ':data.mode==='RANKED'?(result.rpDelta>=0?'+':'')+result.rpDelta+' RP'+(result.after?' · '+result.after.name+' · '+result.after.rp+' RP':''):data.mode==='DAILY'?(result.rewarded?'Đã nhận thẻ thưởng':'Không ảnh hưởng rank'):'Đấu thường · Không tính RP';
      $('result-detail').textContent=data.phase==='CANCELLED'?data.reason:`${me.correct}/${data.questionCount} câu đúng · ${me.score} điểm.`+(result.card==='SHIELD'?' Đã dùng thẻ bảo vệ.':result.card==='DOUBLE'?' Đã dùng thẻ x2 RP.':'')+(result.promoted&&result.after.step%3===0?' Đã nhận điểm đệm khi lên bậc mới.':'');
      $('view-review').disabled=false;if(terminalSeen!==data.matchId+data.phase){terminalSeen=data.matchId+data.phase;refresh();history();}
    }
  }
  function updateTimer(){const now=Date.now()+offset;if(state.phase==='QUEUED'){
      const seconds=Math.max(0,Math.floor((now-state.joinedAt)/1000));$('queue-time').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');$('queue-detail').textContent=`${state.waitingPlayers} người đang tìm trận · Điền bot sau ${Math.max(0,Math.ceil((state.fillAt-now)/1000))} giây.`;
    }else if(['QUESTION','RESULT'].includes(state.phase)){
      const remaining=Math.max(0,state.deadline-now);$('question-timer').textContent=state.phase==='RESULT'?'Kết quả':Math.ceil(remaining/1000)+'s';$('question-time-progress').style.width=(state.phase==='RESULT'?0:Math.min(100,remaining/(state.seconds*1000)*100))+'%';if(!remaining)document.querySelectorAll('.answer-option').forEach(b=>b.disabled=true);
    }}
  function connect(){
    clearTimeout(reconnectTimer);socket=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws');const active=socket;
    socket.onopen=()=>{$('connection-status').textContent='● Máy chủ trực tuyến';send('race_resume',{mode});};
    socket.onmessage=event=>{if(socket!==active)return;try{const data=JSON.parse(event.data);if(data.type==='race_state')render(data);else if(data.type==='race_error'){pendingHome=null;answerBusy=false;message(data.message);if(state.question)render(state);}else if(data.type==='race_kicked'){pendingHome=null;render({phase:'IDLE',message:data.message});window.SystemClient?.showKick(data);}else if(data.type==='server_notice')message(data.message);else if(data.type==='auth_error'){leaving=true;window.SystemClient?.handleAuthError(data);}}catch(e){message('Không đọc được cập nhật từ máy chủ.');console.error(e);}};
    socket.onclose=()=>{if(socket!==active||leaving)return;pendingHome=null;$('connection-status').textContent='Đang kết nối lại…';message('Mất kết nối. Hệ thống đang thử kết nối lại; thời gian trận vẫn tiếp tục trên server.');reconnectTimer=setTimeout(connect,1500);};
    socket.onerror=()=>{$('connection-status').textContent='Lỗi kết nối';};
  }
  $('play-button').addEventListener('click',()=>{message('');if(mode==='DAILY')send('race_daily');else if(mode==='RANKED')send('race_queue',{mode,useShield:$('use-shield').checked,useDouble:$('use-double').checked});else if(mode==='CASUAL')send('race_queue',{mode});});
  $('create-form').addEventListener('submit',e=>{e.preventDefault();if(!['CASUAL','BOT'].includes(mode))return;send('race_create',{withBots:mode==='BOT',roomName:$('room-name').value,questionCount:Number($('question-count').value),timeLimit:Number($('question-seconds').value),botCount:mode==='BOT'?Number($('bot-count').value):0});});
  $('join-form').addEventListener('submit',e=>{e.preventDefault();if(mode==='CASUAL')send('race_join',{roomCode:$('join-code').value.trim().toUpperCase(),mode});});
  $('cancel-queue').addEventListener('click',()=>send('race_cancel_queue'));$('start-match').addEventListener('click',()=>send('race_start'));$('delete-room').addEventListener('click',()=>{if(confirm('Xóa phòng chờ này?'))send('race_delete');});
  const rankedLeaveWarning='Bạn đang trong trận xếp hạng quan trọng, bạn vẫn rời phòng chứ ?';
  const inRankedMatch=()=>state.mode==='RANKED'&&['QUESTION','RESULT'].includes(state.phase);
  document.querySelectorAll('.race-header a[href="/"], #back-to-portal').forEach(link=>link.addEventListener('click',event=>{
    if(!inRankedMatch())return;
    event.preventDefault();
    if(pendingHome||!confirm(rankedLeaveWarning))return;
    pendingHome={href:link.href,matchId:state.matchId};
    if(!send('race_leave'))pendingHome=null;
  }));
  $('leave-match').addEventListener('click',()=>{if(!['QUESTION','RESULT'].includes(state.phase)||confirm(inRankedMatch()?rankedLeaveWarning:'Rời trận đang chơi? Bạn sẽ xếp sau người ở lại và kết quả rank vẫn được ghi.'))send('race_leave');});
  $('back-menu').addEventListener('click',()=>send('race_leave'));$('view-review').addEventListener('click',()=>loadReview(state.matchId));$('wrong-only').addEventListener('change',renderReview);
  $('refresh-overview').addEventListener('click',refresh);$('refresh-history').addEventListener('click',history);
  window.addEventListener('beforeunload',()=>{leaving=true;socket?.close();});setInterval(updateTimer,200);setInterval(()=>{if(document.hidden)return;if(state.phase==='IDLE')refresh();else if(state.phase==='OTHER_MODE')send('race_resume',{mode});},15000);
  (async()=>{try{const me=await api('/auth/me');if(!me.loggedIn){location.assign('/');return;}user=me.user;$('player-name').textContent=user.username;window.SystemClient?.setAccount(user,me.accountNotice);const params=new URLSearchParams(location.search);choose(params.get('mode')?.toUpperCase()||'RANKED');await refresh();if(params.get('review'))await loadReview(params.get('review'));await history();connect();}catch(e){message(e.message);}})();
})();
