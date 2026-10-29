(() => {
  'use strict';
  const $=id=>document.getElementById(id);
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined&&text!==null)n.textContent=String(text);if(cls)n.className=cls;return n;};
  const gameNames={ARENA:'Đấu Trường 100',WHEEL:'Chiếc Nón Kỳ Diệu',SURVIVAL:'Đấu Trường Sinh Tử'};
  const statusNames={ACTIVE:'Đang chơi',WAITING:'Đang chờ',STARTING:'Chuẩn bị',QUESTION:'Trả lời',RESULT:'Kết quả',PLAYING:'Đang chơi',END:'Kết thúc',COMPLETED:'Hoàn thành',CANCELLED:'Đã hủy',INTERRUPTED:'Gián đoạn',PUBLISHED:'Đã xuất bản',DRAFT:'Bản nháp',ARCHIVED:'Đã ẩn'};
  const titles={overview:['Tổng quan hệ thống','Một góc nhìn xuyên suốt về thế giới Vũ Môn.'],users:['Quản lý người chơi','Tra cứu tài khoản, khóa/mở khóa và kết thúc phiên đăng nhập.'],rooms:['Phòng & trận trực tiếp','Theo dõi người chơi, gửi thông báo và điều hành các phòng.'],questions:['Ngân hàng câu hỏi','Quản lý câu hỏi, đáp án và lời giải dùng cho lượt chơi mới.'],wheel:['Ngân hàng ô chữ','Quản lý ô chữ, gợi ý và kiến thức bổ sung.'],topics:['Chủ đề kiến thức','Sắp xếp nội dung học tập theo các chủ đề có thể bật hoặc ẩn.'],matches:['Lịch sử trận đấu','Tra cứu kết quả và nội dung đã được dùng trong từng trận.'],logs:['Nhật ký quản trị','Lưu vết thao tác của quản trị viên và các lần kick của chủ phòng.'],settings:['Cấu hình & bảo trì','Điều chỉnh hoạt động máy chủ và cấu hình mặc định phòng mới.'],backups:['Sao lưu dữ liệu','Tạo và tải các bản sao nhất quán của cơ sở dữ liệu.'],account:['Tài khoản quản trị','Quản lý mật khẩu và phiên đăng nhập riêng.']};
  let csrf='',username='',view='overview',seq=0,topics=[],contentKind='QUESTION',editor=null,action=null,previewCsv=null;
  titles.announcements=['Thông báo toàn server','Phát hoặc tắt dòng chữ chạy trên toàn bộ giao diện người chơi.'];
  titles.integrations=['Google & Email','Kiểm tra cấu hình đăng nhập và kết nối gửi thư từ chính máy server.'];
  titles.race=['Đường đua & Rank','Điều hành ghép trận, phòng thi đấu, điểm RP và thẻ.'];
  titles.leaderboard=['Bảng xếp hạng toàn server','Tra cứu thứ hạng, rank và RP của toàn bộ người chơi đã có trận xếp hạng.'];
  const offsets={users:0,matches:0,logs:0,leaderboard:0};
  function notify(text,error=false){$('notice').textContent=text;$('notice').className='notice'+(error?' error-notice':'');}
  function loginScreen(){csrf='';username='';seq++;$('admin-app').classList.add('hidden');$('login-screen').classList.remove('hidden');document.querySelectorAll('dialog[open]').forEach(d=>d.close());}
  async function api(path,body){
    const options={credentials:'same-origin',cache:'no-store'};
    if(body!==undefined){options.method='POST';options.headers={'Content-Type':'application/json','X-CSRF-Token':csrf};options.body=JSON.stringify(body);}
    const response=await fetch('/api/admin'+path,options);
    let data;try{data=await response.json();}catch{throw new Error('Máy chủ trả dữ liệu không hợp lệ.');}
    if(!response.ok){if(response.status===401&&path!=='/login')loginScreen();throw new Error(data.error||'Chưa thực hiện được thao tác.');}return data;
  }
  function date(value){return value?new Date(Number(value)).toLocaleString('vi-VN'):'—';}
  function badge(value){return el('span',statusNames[value]||value,'badge '+value);}
  function button(label,callback,cls='ghost'){const n=el('button',label,cls);n.type='button';n.addEventListener('click',callback);return n;}
  function group(...nodes){const n=el('div',undefined,'button-group');n.append(...nodes);return n;}
  function textMeta(title,subtitle){const n=el('span');n.append(el('span',title),el('small',subtitle,'subtext'));return n;}
  function table(container,columns,rows,empty='Chưa có dữ liệu phù hợp.'){
    container.replaceChildren();if(!rows.length){container.append(el('p',empty,'empty'));return;}
    const t=el('table'),head=el('thead'),hr=el('tr'),body=el('tbody');columns.forEach(c=>hr.append(el('th',c[0])));head.append(hr);
    rows.forEach((row,index)=>{const tr=el('tr');columns.forEach(c=>{const td=el('td'),value=c[1](row,index);if(value instanceof Node)td.append(value);else td.textContent=value??'—';tr.append(td);});body.append(tr);});t.append(head,body);container.append(t);
  }
  function detail(title,nodes){$('detail-title').textContent=title;$('detail-body').replaceChildren(...nodes);$('detail-dialog').showModal();}
  function actionDialog(title,description,callback,lock=false){action=callback;$('action-title').textContent=title;$('action-description').textContent=description;$('action-reason').value='';$('lock-duration').classList.toggle('hidden',!lock);$('action-dialog').querySelector('.dialog-error').textContent='';$('action-dialog').showModal();}
  function show(next,hash=true){
    if(!titles[next])next='overview';view=next;contentKind=next==='wheel'?'WHEEL':'QUESTION';
    document.querySelectorAll('.view').forEach(n=>n.classList.toggle('hidden',n.id!==`view-${['questions','wheel'].includes(next)?'content':next}`));
    document.querySelectorAll('.sidebar [data-view]').forEach(n=>n.classList.toggle('active',n.dataset.view===next));
    $('page-title').textContent=titles[next][0];$('breadcrumb').textContent=titles[next][0];$('page-description').textContent=titles[next][1];
    $('page-eyebrow').textContent=['questions','wheel','topics'].includes(next)?'NỘI DUNG HỌC TẬP':['settings','logs','backups','account'].includes(next)?'QUẢN TRỊ HỆ THỐNG':'KIỂM SOÁT HOẠT ĐỘNG';
    $('notice').classList.add('hidden');if(hash)history.replaceState(null,'','#'+next);load();
  }
  document.querySelectorAll('[data-view]').forEach(n=>{n.addEventListener('click',()=>show(n.dataset.view));n.title=titles[n.dataset.view][0];});
  window.addEventListener('hashchange',()=>{if(username)show(location.hash.slice(1),false);});
  document.querySelectorAll('[data-close]').forEach(n=>n.addEventListener('click',()=>$(n.dataset.close).close()));
  $('refresh').addEventListener('click',()=>load());
  async function load(){
    if(!username)return;const ticket=++seq,current=view;
    try{
      let data;
      switch(current){
        case 'overview':data=await api('/overview');if(ticket!==seq)return;renderOverview(data);break;
        case 'users':data=await api('/users?'+new URLSearchParams({q:$('users-query').value,offset:offsets.users}));if(ticket!==seq)return;renderUsers(data.items);break;
        case 'rooms':data=await api('/rooms');if(ticket!==seq)return;renderRooms(data.items);break;
        case 'questions':case 'wheel':{
          const kind=current==='wheel'?'WHEEL':'QUESTION';const result=await Promise.all([api('/topics'),api('/content?'+new URLSearchParams({kind,q:$('content-query').value,status:$('content-status').value}))]);if(ticket!==seq)return;topics=result[0].items;renderContent(result[1].items);break;
        }
        case 'topics':data=await api('/topics');if(ticket!==seq)return;topics=data.items;renderTopics(data.items);break;
        case 'matches':data=await api('/matches?'+new URLSearchParams({q:$('matches-query').value,game:$('matches-game').value,status:$('matches-status').value,offset:offsets.matches}));if(ticket!==seq)return;renderMatches(data.items);break;
        case 'logs':data=await api('/logs?'+new URLSearchParams({q:$('logs-query').value,offset:offsets.logs}));if(ticket!==seq)return;renderLogs(data.items);break;
        case 'settings':{const result=await Promise.all([api('/settings'),api('/race/settings')]);if(ticket!==seq)return;renderSettings(result[0]);renderDailySettings(result[1]);break;}
        case 'leaderboard':data=await api('/leaderboard?'+new URLSearchParams({q:$('rank-board-query').value,status:$('rank-board-status').value,offset:offsets.leaderboard}));if(ticket!==seq)return;renderRankBoard(data);break;
        case 'announcements':data=await api('/announcement');if(ticket!==seq)return;renderAnnouncement(data);break;
        case 'integrations':data=await api('/integrations');if(ticket!==seq)return;renderIntegrations(data);break;
        case 'race':data=await api('/race');if(ticket!==seq)return;renderRace(data);break;
        case 'backups':data=await api('/backups');if(ticket!==seq)return;renderBackups(data.items);break;
      }
      $('sidebar-status').textContent='Đã kết nối';$('last-update').textContent='Cập nhật '+new Date().toLocaleTimeString('vi-VN');
    }catch(e){if(ticket!==seq)return;$('sidebar-status').textContent='Chưa tải được dữ liệu';notify(e.message,true);}
  }
  function renderOverview(data){
    const c=data.counts;const stats=[['Người chơi',c.players,'Tài khoản trong hệ thống','♙'],['Kết nối trực tiếp',data.connections,'Kết nối WebSocket đang mở','⌁'],['Trận đã ghi nhận',c.matches,'Gồm hoàn thành, hủy, gián đoạn','⚑'],['Câu hỏi',c.questions,'Toàn bộ bản nháp & xuất bản','▤'],['Ô chữ',c.phrases,'Nội dung cho Chiếc Nón','◉'],['Tài khoản bị khóa',c.blocked,'Đang trong thời gian khóa','♧']];
    $('stat-grid').replaceChildren(...stats.map(([title,value,note,icon])=>{const card=el('article',undefined,'stat-card');card.append(el('span',title),el('strong',value),el('small',note),el('span',icon,'stat-icon'));return card;}));
    table($('overview-matches'),[['Trò chơi',r=>textMeta(gameNames[r.game_type],r.room_code)],['Trạng thái',r=>badge(r.status)],['Bắt đầu',r=>date(r.started_at)]],data.recentMatches);
    $('operation-title').textContent=data.settings.maintenance?'Máy chủ đang bảo trì':'Hệ thống đang hoạt động';
    $('operation-message').textContent=data.settings.maintenance?data.settings.message:'Đang nhận phòng và trận mới cho các trò chơi được bật.';
  }
  function pagination(name,count){$(name+'-page').textContent=`Trang ${offsets[name]/50+1}`;$(name+'-prev').disabled=offsets[name]===0;$(name+'-next').disabled=count<50;}
  function userAction(user,type){
    const titles={lock:'Khóa tài khoản',unlock:'Mở khóa tài khoản',disconnect:'Kết thúc phiên đăng nhập'};
    actionDialog(`${titles[type]} · ${user.username}`,type==='lock'?'Tài khoản sẽ bị đăng xuất và không thể đăng nhập trong thời gian khóa.':type==='disconnect'?'Tất cả phiên chơi và kết nối của tài khoản sẽ bị kết thúc.':'Cho phép tài khoản đăng nhập trở lại.',async(reason,minutes)=>{await api('/users/action',{userId:user.id,action:type,reason,minutes});},type==='lock');
  }
  function renderUsers(rows){
    table($('users-table'),[['Tài khoản',r=>textMeta(r.username,r.email||'Không có email')],['Quyền',r=>r.role==='admin'?'Quản trị viên':'Người chơi'],['Trạng thái',r=>{const blocked=Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now();return textMeta(blocked?'Đã khóa':'Hoạt động',blocked?(Number(r.blocked_until)===-1?'Đến khi mở thủ công':date(r.blocked_until)):'');}],['Thao tác',r=>r.role==='admin'?el('span','Tài khoản quản trị riêng','muted'):group(button('Lịch sử',()=>{$('matches-query').value=r.username;offsets.matches=0;show('matches');}),button('Ngắt phiên',()=>userAction(r,'disconnect')),button(Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now()?'Mở khóa':'Khóa',()=>userAction(r,Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now()?'unlock':'lock'),'danger'))]],rows);
    pagination('users',rows.length);
  }
  function roomAction(room,type,target=''){
    const title=type==='cancel'?'Hủy phòng & trận':type==='kick'?'Kick người chơi':'Gửi thông báo';
    actionDialog(title,type==='cancel'?`Phòng ${room.roomCode} · ${gameNames[room.game]}. Thành viên sẽ về sảnh, trận được ghi là đã hủy.`:type==='kick'?`${target} sẽ rời phòng ${room.roomCode} và không thể vào lại phòng này.`:`Thông báo đến mọi người trong phòng ${room.roomCode}.`,reason=>api('/rooms/action',{game:room.game,roomCode:room.roomCode,action:type,targetUsername:target,reason}));
  }
  function renderRooms(rows){
    const root=$('rooms-grid');root.replaceChildren();if(!rows.length){root.append(el('p','Không có phòng đang mở.','empty'));return;}
    rows.sort((a,b)=>a.game.localeCompare(b.game)||a.roomCode.localeCompare(b.roomCode)).forEach(r=>{
      const card=el('article',undefined,'room-card'),header=el('div',undefined,'room-card-header');header.append(el('span',gameNames[r.game],'small-label'));
      const title=el('div');title.append(el('h2',r.roomName),badge(r.gameState));header.append(title,el('span',r.roomCode,'room-code'));
      const members=el('div',undefined,'room-members');if(!r.members.length)members.append(el('p','Đang chờ người chơi…','empty'));
      r.members.forEach(member=>{const row=el('div',undefined,'room-member');row.append(el('span',member.username+(member.host?' · Chủ phòng':'')),button('Kick',()=>roomAction(r,'kick',member.username),'danger'));members.append(row);});
      const actions=el('div',undefined,'room-actions');actions.append(button('Gửi thông báo',()=>roomAction(r,'notice'),'secondary'),button('Hủy phòng',()=>roomAction(r,'cancel'),'danger'));card.append(header,members,actions);root.append(card);
    });
  }
  function renderContent(rows){
    const question=contentKind==='QUESTION';$('new-content').textContent=question?'+ Thêm câu hỏi':'+ Thêm ô chữ';
    table($('content-table'),[['Nội dung',r=>{const n=textMeta(question?r.text:r.phrase,question?['Dễ','Trung bình','Khó'][['EASY','MEDIUM','HARD'].indexOf(r.difficulty)]:r.hint);n.firstChild.className='truncate';return n;}],['Chủ đề',r=>r.topic],['Trạng thái',r=>badge(r.status)],['Phiên bản',r=>'v'+r.revision],['Thao tác',r=>group(button('Chỉnh sửa',()=>openEditor(r),'secondary'),button('Phiên bản',()=>showRevisions(r)))]],rows);
  }
  function openEditor(row=null){
    editor=row;$('content-form').reset();const q=contentKind==='QUESTION';$('content-dialog-title').textContent=(row?'Chỉnh sửa ':'Thêm ')+(q?'câu hỏi':'ô chữ');
    $('question-fields').classList.toggle('hidden',!q);$('wheel-fields').classList.toggle('hidden',q);$('difficulty-field').classList.toggle('hidden',!q);
    const root=$('editor-topic');root.replaceChildren();topics.filter(t=>t.scope===contentKind).forEach(t=>{const o=el('option',t.name+(Number(t.active)?'':' · Đã ẩn'));o.value=t.code;root.append(o);});
    if(row){if(q){$('question-text').value=row.text;row.options.forEach((v,i)=>$('option-'+String.fromCharCode(97+i)).value=v);$('correct-answer').value=row.correct;$('editor-difficulty').value=row.difficulty;}else{$('wheel-phrase').value=row.phrase;$('wheel-hint').value=row.hint;}$('editor-topic').value=row.topic;$('editor-status').value=row.status;$('editor-explanation').value=row.explanation;}
    $('question-usage').value=row?.usage||'LEGACY';
    $('content-dialog').querySelector('.dialog-error').textContent='';$('content-dialog').showModal();
  }
  async function showRevisions(row){try{const data=await api('/revisions?'+new URLSearchParams({kind:contentKind,id:row.id}));const nodes=[];if(!data.items.length)nodes.push(el('p','Nội dung được nhập từ dữ liệu ban đầu; chưa có chỉnh sửa.','muted'));data.items.forEach(r=>{nodes.push(el('h3',`Phiên bản ${r.revision} · ${r.actor} · ${date(r.created_at)}`),el('pre',JSON.stringify(JSON.parse(r.payload_json),null,2)));});detail('Lịch sử phiên bản',nodes);}catch(e){notify(e.message,true);}}
  function renderTopics(rows){table($('topics-table'),[['Tên chủ đề',r=>textMeta(r.name,r.code)],['Loại',r=>r.scope==='QUESTION'?'Câu hỏi':'Ô chữ'],['Trạng thái',r=>Number(r.active)?badge('PUBLISHED'):badge('ARCHIVED')],['Thao tác',r=>button('Chỉnh sửa',()=>openTopic(r),'secondary')]],rows);}
  function openTopic(row=null){$('topic-form').reset();$('topic-scope').value=row?.scope||'QUESTION';$('topic-code').value=row?.code||'';$('topic-name').value=row?.name||'';$('topic-active').checked=row?!!Number(row.active):true;$('topic-code').readOnly=!!row;$('topic-scope').disabled=!!row;$('topic-dialog').querySelector('.dialog-error').textContent='';$('topic-dialog').showModal();}
  async function matchDetail(id){try{const data=await api('/match?'+new URLSearchParams({id}));const players=el('div');table(players,[['Người chơi',r=>r.username],['Điểm',r=>r.score],['Thứ hạng',r=>r.placement||'—'],['Kết quả',r=>r.outcome]],data.players);detail('Chi tiết trận · '+data.match.room_code,[el('p',`${gameNames[data.match.game_type]} · ${statusNames[data.match.status]} · ${date(data.match.started_at)}`,'muted'),players,el('h3','Lý do kết thúc'),el('p',data.match.reason||'—'),el('h3','Cấu hình & nội dung tại thời điểm bắt đầu'),el('pre',JSON.stringify(JSON.parse(data.match.config_json),null,2))]);}catch(e){notify(e.message,true);}}
  function renderMatches(rows){table($('matches-table'),[['Phòng / game',r=>textMeta(r.room_code,gameNames[r.game_type])],['Trạng thái',r=>badge(r.status)],['Người chơi',r=>r.player_count],['Bắt đầu',r=>date(r.started_at)],['Thao tác',r=>button('Chi tiết',()=>matchDetail(r.id),'secondary')]],rows);pagination('matches',rows.length);}
  function renderLogs(rows){table($('logs-table'),[['Thời gian',r=>date(r.created_at)],['Người thực hiện',r=>r.actor],['Thao tác',r=>r.action],['Đối tượng',r=>r.target],['Chi tiết',r=>button('Xem',()=>detail('Chi tiết nhật ký',[el('p',r.action+' · '+r.actor,'muted'),el('pre',JSON.stringify(JSON.parse(r.details),null,2))]),'secondary')]],rows);pagination('logs',rows.length);}
  const settingFields={ARENA:[['questionCount','Số câu hỏi',1,100],['timeLimit','Giây lượt phụ',1,120],['mainPlayerTimeLimit','Giây lượt chính',1,120],['botCount','Số bot',0,100]],WHEEL:[['timeLimit','Giây mỗi lượt',5,120],['botCount','Bot tối đa',0,3]],SURVIVAL:[['startingHp','HP khởi đầu',1,1000],['totalRounds','Số vòng',5,25]]};
  function renderSettings(data){
    $('maintenance').checked=data.maintenance;$('maintenance-message').value=data.message;$('game-settings').replaceChildren();
    Object.entries(gameNames).forEach(([game,name])=>{const panel=el('article',undefined,'panel');panel.append(el('h2',name));const toggle=el('label',undefined,'toggle-label'),check=el('input');check.type='checkbox';check.id='enabled-'+game;check.checked=data.enabled[game];toggle.append(check,el('span','Nhận trận mới'));panel.append(toggle);settingFields[game].forEach(([key,label,min,max])=>{const id='setting-'+game+'-'+key,l=el('label',label),input=el('input');l.htmlFor=id;input.id=id;input.type='number';input.min=min;input.max=max;input.step=1;input.required=true;input.value=data.defaults[game][key];panel.append(l,input);});$('game-settings').append(panel);});
  }
  function renderBackups(rows){table($('backups-table'),[['Bản sao lưu',r=>r.name],['Thời gian',r=>date(r.created_at)],['Dung lượng',r=>(Number(r.size)/1024).toFixed(1)+' KB'],['Tải xuống',r=>{const a=el('a','↓ Tải bản sao','secondary');a.href='/api/admin/backup-file?'+new URLSearchParams({name:r.name});return a;}]],rows);}
  function renderAnnouncement(data){
    $('announcement-text').value=data.text||'';
    const active=data.enabled&&(!data.expiresAt||data.expiresAt>Date.now());
    $('announcement-status').textContent=active?'Đang phát · '+(data.expiresAt?'Kết thúc '+date(data.expiresAt):'Đến khi tắt'):'Hiện không có thông báo đang phát.';
  }
  function renderIntegrations(data){
    const g=data.google,s=data.smtp;
    $('google-diagnostics').replaceChildren(el('p','Client ID: '+(g.clientIdConfigured?'Đã cấu hình':'Chưa cấu hình')),el('p','Client secret: '+(g.clientSecretConfigured?'Đã cấu hình':'Chưa cấu hình')),el('h3','Authorized redirect URI'),el('pre',g.redirectUri||g.configurationError));
    $('smtp-diagnostics').replaceChildren(el('p','Email gửi: '+(s.senderConfigured?'Đã cấu hình':'Chưa hợp lệ')),el('p','Mật khẩu ứng dụng: '+(s.passwordConfigured?'Đã cấu hình':'Chưa cấu hình')),el('p',`${s.host}:${s.port} · ${s.security}`));
  }
  async function formSubmit(event,fn,dialogId){event.preventDefault();const form=event.currentTarget,submit=form.querySelector('button:not([type=button])');const error=dialogId?$(dialogId).querySelector('.dialog-error'):null;if(submit)submit.disabled=true;if(error)error.textContent='';try{await fn();if(dialogId)$(dialogId).close();notify('Đã lưu thay đổi và ghi nhật ký.');await load();}catch(e){if(error)error.textContent=e.message;else notify(e.message,true);}finally{if(submit)submit.disabled=false;}}
  $('login-form').addEventListener('submit',async event=>{event.preventDefault();$('login-button').disabled=true;$('login-error').textContent='';try{const session=await api('/login',{username:$('login-username').value.trim(),password:$('login-password').value});$('login-password').value='';signedIn(session);}catch(e){$('login-error').textContent=e.message;}finally{$('login-button').disabled=false;}});
  function signedIn(session){username=session.username;csrf=session.csrfToken;$('admin-name').textContent=username;$('login-screen').classList.add('hidden');$('admin-app').classList.remove('hidden');show(location.hash.slice(1)||'overview',false);}
  $('logout').addEventListener('click',async()=>{try{await api('/logout',{});loginScreen();}catch(e){notify(e.message,true);}});
  $('action-form').addEventListener('submit',e=>formSubmit(e,()=>action($('action-reason').value.trim(),Number($('action-minutes').value)),'action-dialog'));
  $('new-content').addEventListener('click',()=>openEditor());
  $('content-form').addEventListener('submit',e=>formSubmit(e,async()=>{
    const v={kind:contentKind,id:editor?.id||0,revision:editor?.revision||0,topic:$('editor-topic').value,status:$('editor-status').value,explanation:$('editor-explanation').value};
    if(contentKind==='QUESTION')Object.assign(v,{text:$('question-text').value,options:['a','b','c','d'].map(k=>$('option-'+k).value),correct:Number($('correct-answer').value),difficulty:$('editor-difficulty').value,usage:$('question-usage').value});else Object.assign(v,{phrase:$('wheel-phrase').value,hint:$('wheel-hint').value});
    await api('/content/save',v);
  },'content-dialog'));
  $('new-topic').addEventListener('click',()=>openTopic());
  $('topic-form').addEventListener('submit',e=>formSubmit(e,()=>api('/topics/save',{scope:$('topic-scope').value,code:$('topic-code').value.toUpperCase(),name:$('topic-name').value,active:$('topic-active').checked}),'topic-dialog'));
  for(const name of ['users','matches','logs']){
    $(name+'-search').addEventListener('submit',e=>{e.preventDefault();offsets[name]=0;load();});
    $(name+'-prev').addEventListener('click',()=>{offsets[name]=Math.max(0,offsets[name]-50);load();});$(name+'-next').addEventListener('click',()=>{offsets[name]+=50;load();});
  }
  $('content-search').addEventListener('submit',e=>{e.preventDefault();load();});
  $('settings-form').addEventListener('submit',e=>formSubmit(e,async()=>{
    const enabled={},defaults={};Object.keys(gameNames).forEach(game=>{enabled[game]=$('enabled-'+game).checked;defaults[game]={};settingFields[game].forEach(([key])=>defaults[game][key]=Number($('setting-'+game+'-'+key).value));});await api('/settings',{maintenance:$('maintenance').checked,message:$('maintenance-message').value,enabled,defaults});
  }));
  $('announcement-form').addEventListener('submit',e=>formSubmit(e,()=>api('/announcement',{enabled:true,text:$('announcement-text').value.trim(),minutes:Number($('announcement-minutes').value)})));
  $('announcement-clear').addEventListener('click',async()=>{const b=$('announcement-clear');b.disabled=true;try{await api('/announcement',{enabled:false,text:'',minutes:0});await load();notify('Đã tắt thông báo trên toàn server.');}catch(e){notify(e.message,true);}finally{b.disabled=false;}});
  $('mail-test').addEventListener('click',async()=>{const b=$('mail-test'),r=$('mail-test-result');b.disabled=true;r.textContent='Đang kiểm tra TLS và xác thực SMTP…';try{const d=await api('/mail-test',{});r.textContent=d.message||'Kết nối và xác thực SMTP thành công.';}catch(e){r.textContent=e.message;}finally{b.disabled=false;}});
  $('password-form').addEventListener('submit',async e=>{e.preventDefault();const form=e.currentTarget;if($('new-password').value!==$('confirm-password').value){notify('Mật khẩu xác nhận không khớp.',true);return;}const b=form.querySelector('button');b.disabled=true;try{await api('/password',{currentPassword:$('old-password').value,newPassword:$('new-password').value});form.reset();loginScreen();$('login-error').textContent='Đã đổi mật khẩu. Đăng nhập lại để tiếp tục.';}catch(error){notify(error.message,true);}finally{b.disabled=false;}});
  $('create-backup').addEventListener('click',async()=>{const b=$('create-backup');b.disabled=true;try{const data=await api('/backup',{});await load();notify('Đã tạo bản sao lưu '+data.name);}catch(e){notify(e.message,true);}finally{b.disabled=false;}});
  $('open-import').addEventListener('click',()=>{$('import-form').reset();previewCsv=null;$('confirm-import').disabled=true;$('import-preview').replaceChildren();$('import-dialog').querySelector('.dialog-error').textContent='';$('import-dialog').showModal();});
  $('csv-text').addEventListener('input',()=>{previewCsv=null;$('confirm-import').disabled=true;$('import-preview').replaceChildren();});
  $('csv-file').addEventListener('change',async()=>{const file=$('csv-file').files[0];if(!file)return;if(file.size>1400000){$('import-dialog').querySelector('.dialog-error').textContent='Tệp quá lớn.';return;}$('csv-text').value=await file.text();$('csv-text').dispatchEvent(new Event('input'));});
  $('csv-template').addEventListener('click',()=>{const csv=contentKind==='QUESTION'?'text,option_a,option_b,option_c,option_d,correct,topic,difficulty,explanation,status\r\nCổng HTTP mặc định là bao nhiêu?,21,443,80,8080,C,NETWORKING,EASY,HTTP sử dụng cổng 80 theo mặc định.,DRAFT\r\n':'phrase,hint,topic,explanation,status\r\nLAP TRINH MANG,Một môn học chuyên ngành,IT,Kiến thức kết nối và trao đổi dữ liệu giữa các máy.,DRAFT\r\n';const url=URL.createObjectURL(new Blob(['\uFEFF'+csv],{type:'text/csv;charset=utf-8'})),a=el('a');a.href=url;a.download=contentKind==='QUESTION'?'mau-cau-hoi.csv':'mau-o-chu.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  $('validate-import').addEventListener('click',async()=>{const b=$('validate-import');b.disabled=true;const err=$('import-dialog').querySelector('.dialog-error');err.textContent='';try{const csv=$('csv-text').value;const data=await api('/content/import',{kind:contentKind,csv,dryRun:true});previewCsv=csv;$('confirm-import').disabled=false;$('import-preview').replaceChildren(el('p',`${data.count} dòng hợp lệ. Xem trước tối đa 5 dòng:`, 'muted'),el('pre',JSON.stringify(data.preview,null,2)));}catch(e){previewCsv=null;$('confirm-import').disabled=true;err.textContent=e.message;}finally{b.disabled=false;}});
  $('import-form').addEventListener('submit',e=>formSubmit(e,async()=>{if(previewCsv===null||previewCsv!==$('csv-text').value)throw new Error('Hãy kiểm tra CSV trước khi nhập.');await api('/content/import',{kind:contentKind,csv:previewCsv,dryRun:false});previewCsv=null;},'import-dialog'));
  const raceSettings=['questionCount','timeLimit','queueSeconds','targetPlayers','firstRP','secondRP','duelSecondRP','thirdRP','fourthRP','rankBuffer','demotionRP'];
  const dailySettings=['dailyTimeLimit','dailyCorrect','shieldReward','doubleReward'];
  const rankNames=['Đồng 3','Đồng 2','Đồng 1','Bạc 3','Bạc 2','Bạc 1','Vàng 3','Vàng 2','Vàng 1','Bạch Kim 3','Bạch Kim 2','Bạch Kim 1','Kim Cương 3','Kim Cương 2','Kim Cương 1'];
  function raceAction(action,matchId,targetUsername){actionDialog('Đường đua · '+({cancel:'Hủy trận',kick:'Kick người chơi',dequeue:'Xóa khỏi hàng chờ',notice:'Thông báo phòng'}[action]),targetUsername||matchId,reason=>api('/race/action',{action,matchId,targetUsername,reason}));}
  function renderRace(data){
    $('race-rankedEnabled').checked=data.settings.rankedEnabled;for(const key of raceSettings)$('race-'+key).value=data.settings[key];
    table($('race-queue-table'),[['Người chơi',r=>r.username],['Chế độ',r=>r.mode],['Vào lúc',r=>date(r.joinedAt)],['Tổng RP',r=>r.rankTotal],['Thao tác',r=>button('Xóa khỏi hàng chờ',()=>raceAction('dequeue','',r.username),'danger')]],data.queue);
    table($('race-rooms-table'),[['Phòng',r=>textMeta(r.roomName,r.roomCode)],['Chế độ',r=>r.mode],['Trạng thái',r=>r.phase],['Câu',r=>r.round+'/'+r.questionCount],['Người chơi',r=>{const root=el('div');r.players.forEach(p=>root.append(group(el('span',p.username+(p.bot?' · BOT':'')),...(!p.bot&&!p.forfeited?[button('Kick',()=>raceAction('kick',r.matchId,p.username),'danger')]:[]))));return root;}],['Điều hành',r=>group(button('Thông báo',()=>raceAction('notice',r.matchId,'')),button('Hủy trận',()=>raceAction('cancel',r.matchId,''),'danger'))]],data.rooms);
    table($('race-players-table'),[['Người chơi',r=>r.username],['Rank hiện tại',r=>(rankNames[r.current_step||0])+' · '+(r.current_points||0)+' RP'],['Cao nhất',r=>(rankNames[r.highest_step||0])+' · '+(r.highest_points||0)+' RP'],['Trận / thắng',r=>(r.ranked_games||0)+' / '+(r.ranked_wins||0)],['Thẻ bảo vệ / x2',r=>r.shields+' / '+r.doubles],['Điều chỉnh',r=>button('Chỉnh sửa',()=>openRacePlayer(r),'secondary')]],data.players);
    table($('race-history-table'),[['Người chơi',r=>r.username],['Phòng',r=>textMeta(r.room_name,r.mode)],['Trạng thái',r=>r.status],['Điểm / hạng',r=>r.score+' / '+r.placement],['RP',r=>r.result.rpDelta||0],['Bắt đầu',r=>date(r.started_at)],['Chi tiết',r=>button('Câu hỏi & đáp án',()=>raceReview(r))]],data.history);
  }
  async function raceReview(row){try{const data=await api('/race/review?'+new URLSearchParams({id:row.id,username:row.username}));const nodes=[el('p',row.username+' · '+data.match.status,'muted')];for(const r of data.questions){const q=r.question;nodes.push(el('h3',r.round_no+'. '+q.text),el('p','Đã chọn: '+(r.answer===null?'Chưa trả lời':q.options[r.answer])),el('p','Đáp án đúng: '+q.options[q.correct]),el('p',q.explanation));}detail('Ôn tập · '+row.room_name,nodes);}catch(e){notify(e.message,true);}}
  function openRacePlayer(row={}){$('race-player-form').reset();$('race-target').value=row.username||'';$('race-step').value=row.current_step||0;$('race-rp').value=row.current_points||0;$('race-shields').value=row.shields||0;$('race-doubles').value=row.doubles||0;racePlayerFields();$('race-player-dialog').querySelector('.dialog-error').textContent='';$('race-player-dialog').showModal();}
  function racePlayerFields(){const action=$('race-player-action').value;$('race-rank-fields').classList.toggle('hidden',action!=='setRank');$('race-card-fields').classList.toggle('hidden',action!=='setCards');}
  $('race-open-player').addEventListener('click',()=>openRacePlayer());$('race-player-action').addEventListener('change',racePlayerFields);
  $('race-settings-form').addEventListener('submit',e=>formSubmit(e,()=>{const value={rankedEnabled:$('race-rankedEnabled').checked};for(const key of raceSettings)value[key]=Number($('race-'+key).value);return api('/race/rank-settings',value);}));
  $('race-player-form').addEventListener('submit',e=>formSubmit(e,()=>api('/race/action',{action:$('race-player-action').value,targetUsername:$('race-target').value.trim(),step:Number($('race-step').value),rp:Number($('race-rp').value),shields:Number($('race-shields').value),doubles:Number($('race-doubles').value),reason:$('race-player-reason').value.trim()}),'race-player-dialog'));
  function renderDailySettings(data){$('race-service-enabled').checked=data.enabled;$('daily-enabled').checked=data.dailyEnabled;for(const key of dailySettings)$('daily-'+key).value=data[key];}
  $('race-service-form').addEventListener('submit',e=>formSubmit(e,()=>api('/race/service-settings',{enabled:$('race-service-enabled').checked})));
  $('daily-settings-form').addEventListener('submit',e=>formSubmit(e,()=>{const value={dailyEnabled:$('daily-enabled').checked};for(const key of dailySettings)value[key]=Number($('daily-'+key).value);return api('/daily/settings',value);}));
  $('daily-reset-form').addEventListener('submit',e=>formSubmit(e,async()=>{await api('/daily/reset',{targetUsername:$('daily-reset-username').value.trim(),reason:$('daily-reset-reason').value.trim()});$('daily-reset-form').reset();}));
  function renderRankBoard(data){
    $('rank-board-summary').textContent=`${data.counts.eligibleCount} người đang xếp hạng · ${data.counts.blockedCount} tài khoản khóa · ${data.total} kết quả phù hợp`;
    table($('rank-board-table'),[['Hạng server',r=>r.position?'#'+r.position:'—'],['Người chơi',r=>r.username],['Rank hiện tại',r=>textMeta(r.rankName,r.current_points+' RP')],['Danh hiệu',r=>r.title||'—'],['Cao nhất',r=>textMeta(r.highestRankName,r.highest_points+' RP'+(r.highest_title?' · '+r.highest_title:''))],['Trận / Thắng',r=>r.ranked_games+' / '+r.ranked_wins],['Trạng thái',r=>r.blocked?'Đã khóa · Không tính hạng':'Đang xếp hạng'],['Cập nhật',r=>date(r.updated_at)]],data.items,'Chưa có người chơi phù hợp. Hoàn thành trận rank để xuất hiện trên bảng.');
    $('rank-board-page').textContent=`Trang ${Math.floor(data.offset/data.limit)+1} / ${Math.max(1,Math.ceil(data.total/data.limit))}`;$('rank-board-prev').disabled=data.offset===0;$('rank-board-next').disabled=data.offset+data.limit>=data.total;
  }
  $('rank-board-search').addEventListener('submit',e=>{e.preventDefault();offsets.leaderboard=0;load();});
  $('rank-board-prev').addEventListener('click',()=>{offsets.leaderboard=Math.max(0,offsets.leaderboard-50);load();});$('rank-board-next').addEventListener('click',()=>{offsets.leaderboard+=50;load();});
  setInterval(()=>{if(username&&!document.hidden&&['overview','rooms','race','leaderboard'].includes(view)&&!document.querySelector('dialog[open]')&&(view!=='leaderboard'||document.activeElement!==$('rank-board-query')))load();},10000);
  let lastTouch=0;document.addEventListener('pointerdown',()=>{if(username&&Date.now()-lastTouch>60000){lastTouch=Date.now();api('/session').catch(()=>{});}});
  api('/session').then(signedIn).catch(()=>loginScreen());
})();
