(() => {
  'use strict';
  const $=id=>document.getElementById(id);
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined&&text!==null)n.textContent=String(text);if(cls)n.className=cls;return n;};
  const gameNames={ARENA:'Đấu Trường 100',WHEEL:'Chiếc Nón Kỳ Diệu',SURVIVAL:'Đấu Trường Sinh Tử'};
  const rankTierNames={BRONZE:'Đồng',SILVER:'Bạc',GOLD:'Vàng',PLATINUM:'Bạch Kim',DIAMOND:'Kim Cương'};
  const statusNames={ACTIVE:'Đang chơi',WAITING:'Đang chờ',STARTING:'Chuẩn bị',QUESTION:'Trả lời',RESULT:'Kết quả',PLAYING:'Đang chơi',END:'Kết thúc',COMPLETED:'Hoàn thành',CANCELLED:'Đã hủy',INTERRUPTED:'Gián đoạn',PUBLISHED:'Đã xuất bản',DRAFT:'Bản nháp',ARCHIVED:'Đã ẩn'};
  const titles={overview:['Tổng quan hệ thống','Một góc nhìn xuyên suốt về thế giới Vũ Môn Tranh Hùng.'],users:['Quản lý người chơi','Tra cứu tài khoản, khóa/mở khóa và kết thúc phiên đăng nhập.'],rooms:['Phòng & trận trực tiếp','Theo dõi người chơi, gửi thông báo và điều hành các phòng.'],questions:['Ngân hàng câu hỏi','Quản lý câu hỏi, đáp án và lời giải dùng cho lượt chơi mới.'],wheel:['Ngân hàng ô chữ','Quản lý ô chữ, gợi ý và kiến thức bổ sung.'],topics:['Chủ đề kiến thức','Sắp xếp nội dung học tập theo các chủ đề có thể bật hoặc ẩn.'],matches:['Lịch sử trận đấu','Tra cứu kết quả và nội dung đã được dùng trong từng trận.'],logs:['Nhật ký quản trị','Lưu vết thao tác của quản trị viên và các lần kick của chủ phòng.'],settings:['Cấu hình & bảo trì','Điều chỉnh hoạt động máy chủ và cấu hình mặc định phòng mới.'],backups:['Sao lưu dữ liệu','Tạo và tải các bản sao nhất quán của cơ sở dữ liệu.'],account:['Tài khoản quản trị','Quản lý mật khẩu và phiên đăng nhập riêng.']};
  let csrf='',username='',view='overview',seq=0,topics=[],contentKind='QUESTION',editor=null,action=null,previewCsv=null,backupConfig=null;
  titles.announcements=['Thông báo toàn server','Phát hoặc tắt dòng chữ chạy trên toàn bộ giao diện người chơi.'];
  titles.integrations=['Google & Email','Kiểm tra cấu hình đăng nhập và kết nối gửi thư từ chính máy server.'];
  titles.users[1]='Trạng thái tài khoản và kết nối online/offline được hiển thị riêng. Tự cập nhật mỗi 3 giây khi đang xem trang.';
  titles.race=['Đường đua & Rank','Điều hành ghép trận, phòng thi đấu, điểm RP và thẻ.'];
  titles.leaderboard=['Bảng xếp hạng toàn server','Tra cứu thứ hạng, rank và RP của toàn bộ người chơi đã có trận xếp hạng.'];
  const offsets={users:0,matches:0,logs:0,leaderboard:0};
  const formSpecs={
    'settings-form':{path:'/settings',render:data=>renderSettings(data)},
    'race-settings-form':{path:'/race/settings',render:data=>renderRaceSettings(data)},
    'race-service-form':{path:'/race/settings',render:data=>renderRaceService(data)},
    'daily-settings-form':{path:'/race/settings',render:data=>renderDailyForm(data)},
    'announcement-form':{path:'/announcement',render:data=>renderAnnouncement(data)}
  };
  const formDrafts=new Map(Object.keys(formSpecs).map(id=>[id,{revision:0,fields:new Set(),saving:false,discarding:false,error:''}]));
  function draftVersions(){return new Map([...formDrafts].map(([id,draft])=>[id,draft.revision]));}
  function updateDraftNote(id){
    const draft=formDrafts.get(id);if(!draft)return;
    $(id+'-draft').classList.toggle('hidden',!draft.fields.size&&!draft.saving&&!draft.discarding&&!draft.error);
    $(id+'-draft-text').textContent=draft.error||(draft.discarding?'Đang tải cấu hình đã lưu…':draft.saving?'Đang lưu cấu hình…':'Có thay đổi chưa lưu. Nội dung đang nhập được giữ khi dữ liệu trực tiếp cập nhật.');
    $(id+'-discard').disabled=draft.saving||draft.discarding;
  }
  function clearDraft(id,fields){
    const draft=formDrafts.get(id);if(!draft)return;
    if(fields)fields.forEach(field=>draft.fields.delete(field));else draft.fields.clear();
    draft.revision++;draft.error='';updateDraftNote(id);
  }
  function canRenderForm(id,versions){
    const draft=formDrafts.get(id);
    return !draft.fields.size&&!draft.saving&&!draft.discarding&&!(id==='settings-form'&&maintenanceBusy)&&(!versions||versions.get(id)===draft.revision);
  }
  async function discardDraft(id){
    const draft=formDrafts.get(id);if(draft.saving||draft.discarding)return;
    const revision=draft.revision;draft.discarding=true;draft.error='';updateDraftNote(id);
    try{
      const data=await api(formSpecs[id].path);
      if(draft.revision!==revision){draft.error='Bạn vừa chỉnh sửa thêm; nội dung mới vẫn được giữ. Bấm Bỏ thay đổi lần nữa nếu muốn tải cấu hình đã lưu.';return;}
      draft.discarding=false;clearDraft(id);formSpecs[id].render(data);
    }catch(error){draft.error='Chưa tải được cấu hình đã lưu: '+error.message;notify(draft.error,true);}
    finally{draft.discarding=false;updateDraftNote(id);}
  }
  for(const id of Object.keys(formSpecs)){
    const changed=event=>{const draft=formDrafts.get(id);draft.revision++;draft.error='';if(event.target?.id!=='maintenance')draft.fields.add(event.target?.id||id);updateDraftNote(id);};
    $(id).addEventListener('input',changed);$(id).addEventListener('change',changed);
    $(id+'-discard').addEventListener('click',()=>discardDraft(id));
  }
  function notify(text,error=false){$('notice').textContent=text;$('notice').className='notice'+(error?' error-notice':'');}
  function loginScreen(){csrf='';username='';seq++;$('admin-app').classList.add('hidden');$('login-screen').classList.remove('hidden');document.querySelectorAll('dialog[open]').forEach(d=>d.close());}
  async function api(path,body){
    const options={credentials:'same-origin',cache:'no-store'};
    if(body!==undefined){const file=body instanceof Blob;options.method='POST';options.headers={'Content-Type':file?'application/octet-stream':'application/json','X-CSRF-Token':csrf};options.body=file?body:JSON.stringify(body);}
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
    document.querySelectorAll('.question-filter').forEach(n=>{n.classList.toggle('hidden',next!=='questions');n.disabled=next!=='questions';});$('question-filter-note').classList.toggle('hidden',next!=='questions');
    $('notice').classList.add('hidden');if(hash)history.replaceState(null,'','#'+next);load();
  }
  document.querySelectorAll('[data-view]').forEach(n=>{n.addEventListener('click',()=>show(n.dataset.view));n.title=titles[n.dataset.view][0];});
  window.addEventListener('hashchange',()=>{if(username)show(location.hash.slice(1),false);});
  document.querySelectorAll('[data-close]').forEach(n=>n.addEventListener('click',()=>$(n.dataset.close).close()));
  $('refresh').addEventListener('click',()=>load());
  async function load(){
    if(!username)return;const ticket=++seq,current=view,versions=draftVersions();
    try{
      let data;
      switch(current){
        case 'overview':data=await api('/overview');if(ticket!==seq)return;renderOverview(data);break;
        case 'users':data=await api('/users?'+new URLSearchParams({q:$('users-query').value,offset:offsets.users}));if(ticket!==seq)return;renderUsers(data.items);break;
        case 'rooms':data=await api('/rooms');if(ticket!==seq)return;renderRooms(data.items);break;
        case 'questions':case 'wheel':{
          const kind=current==='wheel'?'WHEEL':'QUESTION',filters={kind,q:$('content-query').value,status:$('content-status').value};if(kind==='QUESTION')Object.assign(filters,{mode:$('content-mode').value,difficulty:$('content-difficulty').value,rankTier:$('content-rank-tier').value});const result=await Promise.all([api('/topics'),api('/content?'+new URLSearchParams(filters))]);if(ticket!==seq)return;topics=result[0].items;renderContent(result[1].items);break;
        }
        case 'topics':data=await api('/topics');if(ticket!==seq)return;topics=data.items;renderTopics(data.items);break;
        case 'matches':data=await api('/matches?'+new URLSearchParams({q:$('matches-query').value,game:$('matches-game').value,status:$('matches-status').value,offset:offsets.matches}));if(ticket!==seq)return;renderMatches(data.items);break;
        case 'logs':data=await api('/logs?'+new URLSearchParams({q:$('logs-query').value,offset:offsets.logs}));if(ticket!==seq)return;renderLogs(data.items);break;
        case 'settings':{const result=await Promise.all([api('/settings'),api('/race/settings')]);if(ticket!==seq)return;renderSettings(result[0],versions);renderDailySettings(result[1],versions);break;}
        case 'leaderboard':data=await api('/leaderboard?'+new URLSearchParams({q:$('rank-board-query').value,status:$('rank-board-status').value,offset:offsets.leaderboard}));if(ticket!==seq)return;renderRankBoard(data);break;
        case 'announcements':data=await api('/announcement');if(ticket!==seq)return;renderAnnouncement(data,versions);break;
        case 'integrations':data=await api('/integrations');if(ticket!==seq)return;renderIntegrations(data);break;
        case 'race':data=await api('/race');if(ticket!==seq)return;renderRace(data,versions);break;
        case 'backups':data=await api('/backups');if(ticket!==seq)return;renderBackups(data.items);break;
      }
      $('sidebar-status').textContent='Đã kết nối';$('last-update').textContent='Cập nhật '+new Date().toLocaleTimeString('vi-VN');
    }catch(e){if(ticket!==seq)return;$('sidebar-status').textContent='Chưa tải được dữ liệu';notify(e.message,true);}
  }
  function renderOverview(data){
    const c=data.counts;const stats=[['Người chơi',c.players,'Tài khoản trong hệ thống','♙'],['Kết nối trực tiếp',data.connections,'Người chơi đã đăng nhập và đang online','⌁'],['Trận đang diễn ra',c.activeMatches,'Tất cả trò chơi · không gồm phòng chờ','▶'],['Trận đã ghi nhận',c.matches,'Gồm hoàn thành, hủy, gián đoạn','⚑'],['Câu hỏi',c.questions,'Toàn bộ bản nháp & xuất bản','▤'],['Ô chữ',c.phrases,'Nội dung cho Chiếc Nón','◉'],['Tài khoản bị khóa',c.blocked,'Đang trong thời gian khóa','♧']];
    $('stat-grid').replaceChildren(...stats.map(([title,value,note,icon])=>{const card=el('article',undefined,'stat-card');card.append(el('span',title),el('strong',value),el('small',note),el('span',icon,'stat-icon'));return card;}));
    table($('overview-matches'),[['Trò chơi',r=>textMeta(gameNames[r.game_type],r.room_code)],['Trạng thái',r=>badge(r.status)],['Bắt đầu',r=>date(r.started_at)]],data.recentMatches);
    $('operation-title').textContent=data.settings.maintenance?'Máy chủ đang bảo trì':'Hệ thống đang hoạt động';
    $('operation-message').textContent=data.settings.maintenance?data.settings.message+(data.settings.maintenanceUntil?' · Kết thúc: '+new Date(data.settings.maintenanceUntil).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}):''):'Đang nhận phòng và trận mới cho các trò chơi được bật.';
  }
  function pagination(name,count){$(name+'-page').textContent=`Trang ${offsets[name]/50+1}`;$(name+'-prev').disabled=offsets[name]===0;$(name+'-next').disabled=count<50;}
  function userAction(user,type){
    const titles={lock:'Khóa tài khoản',unlock:'Mở khóa tài khoản',disconnect:'Kết thúc phiên đăng nhập'};
    actionDialog(`${titles[type]} · ${user.username}`,type==='lock'?'Tài khoản sẽ bị đăng xuất và không thể đăng nhập trong thời gian khóa.':type==='disconnect'?'Tất cả phiên chơi và kết nối của tài khoản sẽ bị kết thúc.':'Cho phép tài khoản đăng nhập trở lại.',async(reason,minutes)=>{await api('/users/action',{userId:user.id,action:type,reason,minutes});},type==='lock');
  }
  function renderUsers(rows){
    table($('users-table'),[['Tài khoản',r=>textMeta(r.username,r.email||'Không có email')],['Quyền',r=>r.role==='admin'?'Quản trị viên':'Người chơi'],['Trạng thái tài khoản',r=>{const blocked=Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now();return textMeta(blocked?'Bị khóa':'Được phép đăng nhập',blocked?(Number(r.blocked_until)===-1?'Đến khi mở thủ công':date(r.blocked_until)):'');}],['Kết nối người chơi',r=>r.role==='admin'?el('span','Phiên quản trị riêng','muted'):el('span',r.online?'● Online':'○ Offline','presence-badge '+(r.online?'is-online':'is-offline'))],['Thao tác',r=>r.role==='admin'?el('span','Tài khoản quản trị riêng','muted'):group(button('Lịch sử',()=>{$('matches-query').value=r.username;offsets.matches=0;show('matches');}),button('Ngắt phiên',()=>userAction(r,'disconnect')),button(Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now()?'Mở khóa':'Khóa',()=>userAction(r,Number(r.blocked_until)===-1||Number(r.blocked_until)>Date.now()?'unlock':'lock'),'danger'))]],rows);
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
    $('content-result-count').textContent='Hiển thị '+rows.length+' '+(question?'câu hỏi':'ô chữ')+' phù hợp'+(rows.length===500?' (tối đa 500; có thể thu hẹp thêm bộ lọc)':'')+'.';
    table($('content-table'),[['Nội dung',r=>{const n=textMeta(question?r.text:r.phrase,question?['Dễ','Trung bình','Khó'][['EASY','MEDIUM','HARD'].indexOf(r.difficulty)]:r.hint);n.firstChild.className='truncate';return n;}],['Chủ đề',r=>r.topic],...(question?[['Bộ rank',r=>rankTierNames[r.rank_tier]||'Chung']]:[]),['Trạng thái',r=>badge(r.status)],['Phiên bản',r=>'v'+r.revision],['Thao tác',r=>group(button('Chỉnh sửa',()=>openEditor(r),'secondary'),button('Phiên bản',()=>showRevisions(r)))]],rows);
  }
  function questionRankFields(){
    const tier=$('question-rank-tier').value,allowed={BRONZE:['EASY'],SILVER:['EASY','MEDIUM'],GOLD:['MEDIUM','HARD'],PLATINUM:['MEDIUM','HARD'],DIAMOND:['HARD']}[tier]||['EASY','MEDIUM','HARD'];
    if(tier){$('question-usage').value='RACE';$('editor-topic').value='NETWORKING';}
    $('editor-topic').disabled=Boolean(tier);
    Array.from($('editor-difficulty').options).forEach(option=>option.disabled=!allowed.includes(option.value));
    if(!allowed.includes($('editor-difficulty').value))$('editor-difficulty').value=allowed[0];
    $('question-rank-note').textContent=tier?'Câu hỏi dành riêng cho bậc '+rankTierNames[tier]+', thuộc Lập trình mạng. Vàng ưu tiên Trung bình; Bạch Kim ưu tiên Khó.':'Câu hỏi Chung dùng theo phạm vi sử dụng; chọn một bậc để đưa vào bộ đấu rank riêng.';
  }
  $('question-rank-tier').addEventListener('change',questionRankFields);
  $('question-usage').addEventListener('change',()=>{if($('question-usage').value!=='RACE')$('question-rank-tier').value='';questionRankFields();});
  function openEditor(row=null){
    editor=row;$('content-form').reset();const q=contentKind==='QUESTION';$('content-dialog-title').textContent=(row?'Chỉnh sửa ':'Thêm ')+(q?'câu hỏi':'ô chữ');
    $('question-fields').classList.toggle('hidden',!q);$('wheel-fields').classList.toggle('hidden',q);$('difficulty-field').classList.toggle('hidden',!q);
    const root=$('editor-topic');root.replaceChildren();topics.filter(t=>t.scope===contentKind).forEach(t=>{const o=el('option',t.name+(Number(t.active)?'':' · Đã ẩn'));o.value=t.code;root.append(o);});
    if(row){if(q){$('question-text').value=row.text;row.options.forEach((v,i)=>$('option-'+String.fromCharCode(97+i)).value=v);$('correct-answer').value=row.correct;$('editor-difficulty').value=row.difficulty;}else{$('wheel-phrase').value=row.phrase;$('wheel-hint').value=row.hint;}$('editor-topic').value=row.topic;$('editor-status').value=row.status;$('editor-explanation').value=row.explanation;}
    $('question-usage').value=row?.usage||'LEGACY';$('question-rank-tier').value=row?.rank_tier||'';if(q)questionRankFields();else $('editor-topic').disabled=false;
    $('content-dialog').querySelector('.dialog-error').textContent='';$('content-dialog').showModal();
  }
  async function showRevisions(row){try{const data=await api('/revisions?'+new URLSearchParams({kind:contentKind,id:row.id}));const nodes=[];if(!data.items.length)nodes.push(el('p','Nội dung được nhập từ dữ liệu ban đầu; chưa có chỉnh sửa.','muted'));data.items.forEach(r=>{nodes.push(el('h3',`Phiên bản ${r.revision} · ${r.actor} · ${date(r.created_at)}`),el('pre',JSON.stringify(JSON.parse(r.payload_json),null,2)));});detail('Lịch sử phiên bản',nodes);}catch(e){notify(e.message,true);}}
  function renderTopics(rows){table($('topics-table'),[['Tên chủ đề',r=>textMeta(r.name,r.code)],['Loại',r=>r.scope==='QUESTION'?'Câu hỏi':'Ô chữ'],['Trạng thái',r=>Number(r.active)?badge('PUBLISHED'):badge('ARCHIVED')],['Thao tác',r=>button('Chỉnh sửa',()=>openTopic(r),'secondary')]],rows);}
  function openTopic(row=null){$('topic-form').reset();$('topic-scope').value=row?.scope||'QUESTION';$('topic-code').value=row?.code||'';$('topic-name').value=row?.name||'';$('topic-active').checked=row?!!Number(row.active):true;$('topic-code').readOnly=!!row;$('topic-scope').disabled=!!row;$('topic-dialog').querySelector('.dialog-error').textContent='';$('topic-dialog').showModal();}
  const outcomeNames={WIN:'Chiến thắng',LOSS:'Thua cuộc',DRAW:'Hòa',LEFT:'Đã rời trận',FORFEITED:'Bỏ cuộc · Không tính thành tích',KICKED:'Bị mời rời phòng',CANCELLED:'Trận đã hủy',INTERRUPTED:'Trận bị gián đoạn'};
  const auditNames={ADMIN_LOGIN:'Đăng nhập quản trị',ADMIN_LOGIN_FAILED:'Đăng nhập quản trị thất bại',ADMIN_LOGOUT:'Đăng xuất quản trị',ADMIN_PASSWORD_CHANGE:'Đổi mật khẩu quản trị',ADMIN_BOOTSTRAP:'Khởi tạo quản trị viên',ADMIN_LOCAL_GENERATE:'Tạo tài khoản quản trị tại máy chủ',ADMIN_LOCAL_SETUP:'Thiết lập tài khoản quản trị',PLAYER_PASSWORD_CHANGE:'Người chơi đổi mật khẩu',USER_LOCK:'Khóa tài khoản người chơi',USER_UNLOCK:'Mở khóa tài khoản người chơi',USER_DISCONNECT:'Ngắt phiên đăng nhập người chơi',TOPIC_SAVE:'Lưu chủ đề kiến thức',CONTENT_SAVE:'Lưu câu hỏi / ô chữ',CONTENT_IMPORT:'Nhập câu hỏi / ô chữ từ CSV',SETTINGS_SAVE:'Cập nhật cấu hình máy chủ',DATABASE_BACKUP:'Tạo bản sao lưu dữ liệu',BACKUP_UPLOAD:'Tải bản sao lưu lên máy chủ',BACKUP_DOWNLOAD:'Tải bản sao lưu về máy',BACKUP_CONFIG_APPLY:'Áp dụng cấu hình từ bản sao lưu',SERVER_ANNOUNCE:'Phát thông báo toàn máy chủ',SERVER_ANNOUNCEMENT_CLEAR:'Tắt thông báo toàn máy chủ',SMTP_CONNECTION_TEST:'Kiểm tra kết nối gửi email',ADMIN_KICK:'Quản trị viên mời người chơi rời phòng',HOST_KICK:'Chủ phòng mời người chơi rời phòng',HOST_ROOM_DELETE:'Chủ phòng xóa phòng',RACE_SETTINGS:'Cập nhật cấu hình Đường đua & Rank',RACE_SERVICE_SETTINGS:'Cập nhật dịch vụ Đường đua',DAILY_SETTINGS:'Cập nhật thử thách hằng ngày',RACE_SETRANK:'Điều chỉnh rank và điểm RP',RACE_SETCARDS:'Điều chỉnh thẻ của người chơi',RACE_RESETDAILY:'Cấp lại lượt thử thách hằng ngày'};
  const detailLabels={topic:'Chủ đề',difficulty:'Độ khó',questionCount:'Số câu hỏi',botCount:'Số người chơi máy',timeLimit:'Thời gian mỗi lượt',mainPlayerTimeLimit:'Thời gian lượt người chơi chính',startingHp:'Máu khởi đầu',totalRounds:'Số vòng đấu',phrase:'Đáp án ô chữ',hint:'Gợi ý',explanation:'Lời giải / kiến thức bổ sung',questions:'Danh sách câu hỏi',text:'Nội dung',options:'Các lựa chọn',correct:'Đáp án đúng',id:'Mã bản ghi',remote:'Địa chỉ kết nối đến máy chủ',reason:'Lý do',minutes:'Thời hạn',target:'Người chơi bị tác động',targetUsername:'Người chơi bị tác động',success:'Kết quả thực hiện',code:'Mã thông báo',state:'Trạng thái phòng',operation:'Cách thiết lập',count:'Số nội dung đã nhập',name:'Tên hiển thị',scope:'Loại nội dung',active:'Cho phép sử dụng',status:'Trạng thái',revision:'Phiên bản',kind:'Loại nội dung',usage:'Phạm vi sử dụng',rankTier:'Bộ câu hỏi bậc rank',rank_tier:'Bộ câu hỏi bậc rank',maintenance:'Chế độ bảo trì',maintenanceUntil:'Thời điểm kết thúc bảo trì',maintenanceHours:'Thời lượng bảo trì',message:'Nội dung thông báo',enabled:'Cho phép hoạt động',defaults:'Cấu hình mặc định các trò chơi',rankedEnabled:'Cho phép đấu xếp hạng',dailyEnabled:'Cho phép thử thách hằng ngày',queueSeconds:'Thời gian chờ ghép trận',targetPlayers:'Số người chơi mỗi trận',dailyTimeLimit:'Thời gian mỗi câu thử thách ngày',dailyCorrect:'Số câu đúng để nhận thưởng',firstRP:'RP hạng nhất',secondRP:'RP hạng nhì',duelSecondRP:'RP hạng nhì khi đấu hai người',thirdRP:'RP hạng ba',fourthRP:'RP hạng tư',rankBuffer:'Khoảng điểm ghép rank',demotionRP:'RP sau khi xuống hạng',shieldReward:'Thẻ bảo vệ được thưởng',doubleReward:'Thẻ nhân đôi RP được thưởng',expiresAt:'Thời điểm hết hạn',updatedAt:'Thời điểm cập nhật',updated_at:'Thời điểm cập nhật',size:'Dung lượng file',groups:'Nhóm cấu hình được áp dụng',safetyBackup:'Bản sao lưu an toàn trước khi áp dụng',before:'Dữ liệu trước khi thay đổi',request:'Nội dung điều chỉnh',rank:'Xếp hạng',cards:'Thẻ hỗ trợ',daily:'Lượt thử thách hằng ngày',step:'Bậc / cấp rank',rp:'Điểm RP trong cấp',shields:'Thẻ bảo vệ',doubles:'Thẻ nhân đôi RP',username:'Tên tài khoản',user_id:'Mã người chơi',userId:'Mã người chơi',action:'Thao tác',current_step:'Bậc / cấp rank hiện tại',highest_step:'Bậc / cấp rank cao nhất',current_tier:'Bậc rank hiện tại',highest_tier:'Bậc rank cao nhất',current_points:'Điểm RP hiện tại',highest_points:'Điểm RP cao nhất',day:'Ngày thử thách',match_id:'Mã trận đấu',rewarded:'Đã nhận thưởng',roomCode:'Mã phòng',game:'Trò chơi',mode:'Chế độ chơi',mainSelectionMode:'Cách chọn người chơi chính',mainPlayerUsername:'Người chơi chính'};
  const readableEnums={...gameNames,...rankTierNames,...statusNames,...outcomeNames,ALL:'Tất cả chủ đề',NETWORKING:'Lập trình mạng',GENERAL:'Kiến thức tổng hợp',SCIENCE:'Khoa học',HISTORY:'Lịch sử',GEOGRAPHY:'Địa lý',SPORTS:'Thể thao',TECHNOLOGY:'Công nghệ',EASY:'Dễ',MEDIUM:'Trung bình',HARD:'Khó',INCREASING:'Tăng dần',RANDOM:'Ngẫu nhiên',MANUAL:'Chọn thủ công',QUESTION:'Câu hỏi trắc nghiệm',LEGACY:'Ba game ban đầu và Phòng học',RACE:'Đường đua Kiến thức',BOTH:'Cả ba game ban đầu và Đường đua',RANKED:'Đấu xếp hạng',CASUAL:'Đấu thường',BOT:'Chơi với máy',DAILY:'Thử thách hằng ngày',UNRANKED:'Chưa xếp hạng',CLOSED:'Đã đóng',NONE:'Không có',lock:'Khóa tài khoản',unlock:'Mở khóa tài khoản',disconnect:'Ngắt phiên',setRank:'Điều chỉnh rank và RP',setCards:'Điều chỉnh thẻ',resetDaily:'Cấp lại lượt thử thách',runtime:'Cấu hình máy chủ',race_settings:'Đường đua & Rank',announcement:'Thông báo toàn máy chủ','--create':'Tạo tài khoản','--reset':'Đặt lại mật khẩu','requested-local-setup':'Thiết lập tại máy chủ theo yêu cầu',SMTP_CONFIG:'Cấu hình gửi email chưa hợp lệ',SMTP_CONNECT:'Không kết nối được máy chủ email',SMTP_AUTH:'Xác thực email thất bại',SMTP_TLS:'Không thiết lập được kết nối email bảo mật'};
  Object.assign(auditNames,{RACE_CANCEL:'Hủy trận Đường đua',RACE_KICK:'Mời người chơi rời trận Đường đua',RACE_NOTICE:'Gửi thông báo đến trận Đường đua',RACE_DEQUEUE:'Xóa người chơi khỏi hàng chờ ghép trận'});
  Object.assign(detailLabels,{matchId:'Mã trận đấu',publishedAt:'Thời điểm phát thông báo',publishedBy:'Người phát thông báo',enabledGames:'Trò chơi được phép hoạt động'});
  Object.assign(readableEnums,{IT:'Công nghệ thông tin',CULTURE:'Văn hóa',LITERATURE:'Văn học',MATH:'Toán học',MUSIC:'Âm nhạc',cancel:'Hủy trận đấu',kick:'Mời người chơi rời phòng',notice:'Gửi thông báo',dequeue:'Xóa khỏi hàng chờ ghép trận',SMTP_PROTOCOL:'Phản hồi từ máy chủ email chưa hợp lệ',SMTP_RECIPIENT:'Địa chỉ nhận email chưa hợp lệ',SMTP_SEND:'Kết nối bị gián đoạn khi gửi email',SMTP_REJECTED:'Máy chủ email từ chối yêu cầu'});
  function parsedDetails(value){
    if(typeof value!=='string')return value??{};
    try{return JSON.parse(value);}catch{return null;}
  }
  function humanWords(value){return String(value).replace(/([a-z])([A-Z])/g,'$1 $2').replace(/[_-]+/g,' ').toLocaleLowerCase('vi-VN');}
  function fieldLabel(key){return detailLabels[key]||gameNames[key]||humanWords(key);}
  function readableValue(key,value){
    if(value===null||value===undefined||value==='')return 'Chưa ghi nhận';
    if(typeof value==='boolean')return key==='success'?(value?'Thành công':'Thất bại'):(value?'Có':'Không');
    if(['active','rewarded'].includes(key)&&[0,1].includes(value))return value?'Có':'Không';
    if(['maintenanceUntil','expiresAt','updatedAt','updated_at','publishedAt'].includes(key))return Number(value)>0?date(value):'Không đặt thời hạn';
    if(['timeLimit','mainPlayerTimeLimit','queueSeconds','dailyTimeLimit'].includes(key))return value+' giây';
    if(key==='maintenanceHours')return value+' giờ';
    if(key==='minutes')return Number(value)===-1?'Đến khi mở khóa thủ công':Number(value)===0?'Không đặt thời hạn':value+' phút';
    if(key==='size')return (Number(value)/1024).toLocaleString('vi-VN',{maximumFractionDigits:1})+' KB';
    if(['step','current_step','highest_step'].includes(key)&&Number(value)===15)return 'Cao Thủ';
    if(['step','current_step','highest_step'].includes(key)&&Number.isInteger(Number(value))&&Number(value)>=0&&Number(value)<15)return ['Đồng','Bạc','Vàng','Bạch Kim','Kim Cương'][Math.floor(Number(value)/3)]+' '+(3-Number(value)%3);
    if(key==='remote'&&['::1','0:0:0:0:0:0:0:1','127.0.0.1'].includes(value))return 'Kết nối nội bộ máy chủ ('+value+')';
    if(key==='topic')return topics.find(t=>t.code===value)?.name||readableEnums[value]||String(value);
    // Free text and identifiers must keep their original contents.
    if(['text','phrase','hint','explanation','reason','message','username','target','targetUsername','name','id','userId','user_id','match_id','roomCode','safetyBackup'].includes(key))return String(value);
    return readableEnums[value]||String(value);
  }
  function detailSection(title,...nodes){const section=el('section',undefined,'detail-section');section.append(el('h3',title),...nodes);return section;}
  function factGrid(entries){
    const grid=el('dl',undefined,'detail-facts');
    for(const [label,value] of entries){const field=el('div',undefined,'detail-field');field.append(el('dt',label),value instanceof Node?value:el('dd',value));grid.append(field);}
    return grid;
  }
  function readableFields(value){
    if(value===null)return el('p','Thông tin đã lưu không đọc được.','muted');
    if(Array.isArray(value)){
      if(!value.length)return el('p','Không có dữ liệu được ghi nhận.','muted');
      if(value.length===1&&value[0]&&typeof value[0]==='object')return readableFields(value[0]);
      const list=el('div',undefined,'detail-stack');value.forEach((item,index)=>list.append(item&&typeof item==='object'?detailSection('Mục '+(index+1),readableFields(item)):el('p',readableValue('',item))));return list;
    }
    if(typeof value!=='object')return el('p',readableValue('',value));
    const entries=Object.entries(value),root=el('div',undefined,'detail-stack');
    if(!entries.length){root.append(el('p','Không có thông tin bổ sung được ghi nhận.','muted'));return root;}
    const fields=[],nested=[];
    for(const [key,item] of entries){
      if(item!==null&&typeof item==='object')nested.push(detailSection(fieldLabel(key),readableFields(item)));
      else fields.push([fieldLabel(key),key==='correct'&&Number.isInteger(item)&&Array.isArray(value.options)&&item>=0&&item<value.options.length?String.fromCharCode(65+item)+' · '+value.options[item]:readableValue(key,item)]);
    }
    if(fields.length)root.append(factGrid(fields));root.append(...nested);return root;
  }
  function matchSnapshot(config,game){
    if(!config||typeof config!=='object'||Array.isArray(config))return el('p','Chưa có cấu hình hợp lệ được lưu cho trận này.','muted');
    const root=el('div',undefined,'detail-stack');
    const settings=Object.fromEntries(Object.entries(config).filter(([key])=>!['questions','phrase','hint','explanation'].includes(key)));
    root.append(detailSection('Thiết lập trận đấu',readableFields(settings)));
    if(game==='WHEEL'||Object.hasOwn(config,'phrase'))root.append(detailSection('Ô chữ của trận đấu',factGrid(['phrase','hint','explanation'].map(key=>[fieldLabel(key),readableValue(key,config[key])]))));
    if(Array.isArray(config.questions)){
      const content=detailSection('Nội dung câu hỏi đã sử dụng · '+config.questions.length+' câu');
      config.questions.forEach((q,index)=>{
        if(!q||typeof q!=='object'){content.append(el('p','Câu '+(index+1)+': nội dung lưu chưa hợp lệ.','muted'));return;}
        const item=el('details',undefined,'detail-question');item.open=index===0;
        item.append(el('summary','Câu '+(index+1)+' · '+(q.text||'Chưa có nội dung')));
        item.append(factGrid([['Chủ đề',readableValue('topic',q.topic)],['Độ khó',readableValue('difficulty',q.difficulty)]]));
        const options=el('ol',undefined,'detail-options'),answers=Array.isArray(q.options)?q.options:[],correct=q.correct;
        answers.forEach((answer,i)=>{const option=el('li',undefined,i===correct?'correct-option':'');option.append(el('span',String.fromCharCode(65+i),'option-letter'),el('span',answer));if(i===correct)option.append(el('strong','Đáp án đúng'));options.append(option);});
        item.append(options,el('p',Number.isInteger(correct)&&correct>=0&&correct<answers.length?'Đáp án đúng: '+String.fromCharCode(65+correct)+' · '+answers[correct]:'Đáp án đúng chưa được ghi nhận.','detail-answer'),el('p','Lời giải: '+(q.explanation||'Chưa có lời giải.'),'detail-explanation'));content.append(item);
      });
      root.append(content);
    }
    return root;
  }
  function auditAction(row){
    if(['CONTENT_SAVE','CONTENT_IMPORT'].includes(row.action))return (row.action==='CONTENT_SAVE'?'Lưu ':'Nhập từ CSV · ')+(String(row.target).startsWith('WHEEL')?'ô chữ':'câu hỏi');
    if(auditNames[row.action])return auditNames[row.action];
    const room=/^ROOM_(CANCEL|KICK|NOTICE)_(REQUEST|DONE)$/.exec(row.action||'');
    if(room)return {CANCEL:'Hủy phòng và trận đấu',KICK:'Mời người chơi rời phòng',NOTICE:'Gửi thông báo đến phòng'}[room[1]]+(room[2]==='REQUEST'?' · Đã yêu cầu':' · Đã hoàn tất');
    return 'Thao tác quản trị khác';
  }
  function auditTarget(row){
    const target=String(row.target||''),[kind,...rest]=target.split(':'),value=rest.join(':');
    if(row.action==='TOPIC_SAVE'&&value)return 'Chủ đề '+readableValue('topic',value)+' · '+(kind==='WHEEL'?'Ô chữ':'Câu hỏi');
    if(['QUESTION','WHEEL'].includes(kind)&&row.action.startsWith('CONTENT_'))return value?(kind==='WHEEL'?'Ô chữ số ':'Câu hỏi số ')+value:(kind==='WHEEL'?'Ngân hàng ô chữ':'Ngân hàng câu hỏi');
    if(gameNames[kind]&&value)return 'Phòng '+value+' · '+gameNames[kind];
    if(kind==='QUESTION')return value?'Câu hỏi số '+value:'Ngân hàng câu hỏi';
    if(kind==='WHEEL'&&!value)return 'Ngân hàng ô chữ';
    if(row.action==='RACE_DEQUEUE')return 'Người chơi '+(parsedDetails(row.details)?.targetUsername||target||'chưa ghi nhận');
    if(['RACE_CANCEL','RACE_KICK','RACE_NOTICE'].includes(row.action))return target?'Trận Đường đua · '+target:'Trận Đường đua';
    if(/^(USER_|RACE_SET|RACE_RESET|PLAYER_PASSWORD)/.test(row.action))return 'Tài khoản '+target;
    return {login:'Trang đăng nhập quản trị',session:'Phiên đăng nhập quản trị',account:'Tài khoản quản trị',server:'Máy chủ'}[target]||target||'Chưa ghi nhận đối tượng';
  }
  function auditSummary(row){
    const data=parsedDetails(row.details);
    if(!data||typeof data!=='object')return 'Thông tin chi tiết chưa đọc được.';
    if(data.reason)return 'Lý do: '+data.reason;
    if(data.remote)return readableValue('remote',data.remote);
    if(Object.hasOwn(data,'success'))return 'Kết quả: '+readableValue('success',data.success);
    if(Object.hasOwn(data,'count'))return 'Đã nhập '+data.count+' nội dung.';
    if(data.text)return data.text;
    if(data.name)return 'Tên hiển thị: '+data.name;
    if(data.groups)return 'Áp dụng '+data.groups.length+' nhóm cấu hình.';
    if(row.action==='SETTINGS_SAVE')return 'Đã lưu cấu hình trò chơi và chế độ bảo trì.';
    if(row.action==='SERVER_ANNOUNCEMENT_CLEAR')return 'Dừng hiển thị thông báo toàn máy chủ.';
    return 'Xem thông tin của thao tác này.';
  }
  function auditDetail(row){
    const summary=factGrid([['Thao tác',auditAction(row)],['Người thực hiện',row.actor||'Chưa ghi nhận'],['Thời gian',date(row.created_at)],['Đối tượng',auditTarget(row)]]);
    detail('Chi tiết nhật ký',[summary,detailSection('Thông tin của thao tác',readableFields(parsedDetails(row.details)))]);
  }
  async function matchDetail(id){try{
    const data=await api('/match?'+new URLSearchParams({id})),match=data.match,players=el('div',undefined,'table-wrap');
    table(players,[['Người chơi',r=>r.username],['Điểm',r=>r.score],['Thứ hạng',r=>r.placement||'—'],['Kết quả',r=>outcomeNames[r.outcome]||statusNames[r.outcome]||'Chưa có kết quả']],data.players);
    detail('Chi tiết trận · '+match.room_code,[factGrid([['Trò chơi',gameNames[match.game_type]||'Trò chơi'],['Trạng thái',statusNames[match.status]||'Chưa ghi nhận'],['Bắt đầu',date(match.started_at)],['Kết thúc',date(match.ended_at)]]),detailSection('Kết quả người chơi',players),detailSection('Lý do kết thúc',el('p',match.reason==='Room closed'?'Phòng đã đóng':match.reason||'Chưa ghi nhận')),el('h3','Cấu hình & nội dung tại thời điểm bắt đầu'),matchSnapshot(parsedDetails(match.config_json),match.game_type)]);
  }catch(e){notify(e.message,true);}}
  function renderMatches(rows){table($('matches-table'),[['Phòng / game',r=>textMeta(r.room_code,gameNames[r.game_type])],['Trạng thái',r=>badge(r.status)],['Người chơi',r=>r.player_count],['Bắt đầu',r=>date(r.started_at)],['Thao tác',r=>button('Chi tiết',()=>matchDetail(r.id),'secondary')]],rows);pagination('matches',rows.length);}
  function renderLogs(rows){table($('logs-table'),[['Thời gian',r=>date(r.created_at)],['Người thực hiện',r=>r.actor],['Thao tác',r=>auditAction(r)],['Đối tượng',r=>auditTarget(r)],['Chi tiết',r=>{const cell=el('div',undefined,'audit-summary');cell.append(el('p',auditSummary(r)),button('Xem chi tiết',()=>auditDetail(r),'secondary'));return cell;}]],rows);pagination('logs',rows.length);}
  const settingFields={ARENA:[['questionCount','Số câu hỏi',1,100],['timeLimit','Giây lượt phụ',1,120],['mainPlayerTimeLimit','Giây lượt chính',1,120],['botCount','Số bot',0,100]],WHEEL:[['timeLimit','Giây mỗi lượt',5,120],['botCount','Bot tối đa',0,3]],SURVIVAL:[['startingHp','HP khởi đầu',1,1000],['totalRounds','Số vòng',5,25]]};
  let maintenanceSaved=false,maintenanceBusy=false;
  function maintenanceControls(){
    const enabled=$('maintenance').checked,hours=$('maintenance-time-mode').value==='hours',busy=maintenanceBusy||formDrafts.get('settings-form').saving;
    $('maintenance-hours-field').classList.toggle('hidden',!hours);$('maintenance-until-field').classList.toggle('hidden',hours);
    $('maintenance-hours').disabled=busy||!hours;$('maintenance-hours').required=enabled&&hours;
    $('maintenance-until').disabled=busy||hours;$('maintenance-until').required=enabled&&!hours;
    $('maintenance').disabled=busy;$('maintenance-time-mode').disabled=busy;$('maintenance-message').disabled=busy;$('save-settings').disabled=busy;
  }
  function maintenanceInput(enabled){
    const value={maintenance:enabled};if(!enabled)return value;
    value.message=$('maintenance-message').value.trim();if(!value.message||value.message.length>500)throw new Error('Nhập thông báo bảo trì từ 1–500 ký tự.');
    if($('maintenance-time-mode').value==='hours'){
      value.maintenanceHours=Number($('maintenance-hours').value);if(!Number.isFinite(value.maintenanceHours)||value.maintenanceHours<=0||value.maintenanceHours>8760)throw new Error('Nhập số giờ bảo trì lớn hơn 0, tối đa 8760 giờ.');
    }else{
      value.maintenanceUntil=Date.parse($('maintenance-until').value+':00+07:00');if(!Number.isFinite(value.maintenanceUntil)||value.maintenanceUntil<=Date.now())throw new Error('Chọn ngày giờ kết thúc bảo trì trong tương lai.');
    }
    return value;
  }
  function renderMaintenanceStatus(data){
    maintenanceSaved=Boolean(data.maintenance);$('maintenance').checked=maintenanceSaved;
    $('maintenance-end-status').textContent=maintenanceSaved&&data.maintenanceUntil?'Đang bảo trì đến '+new Date(Number(data.maintenanceUntil)).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'})+' (giờ Việt Nam).':'Server đang mở cho người chơi. Chọn thời hạn và thông báo trước khi bật bảo trì.';
  }
  function renderMaintenance(data){
    renderMaintenanceStatus(data);$('maintenance-message').value=data.message;
    $('maintenance-time-mode').value=maintenanceSaved&&data.maintenanceUntil?'until':'hours';
    $('maintenance-until').value=data.maintenanceUntil?new Date(Number(data.maintenanceUntil)+7*3600000).toISOString().slice(0,16):'';
    maintenanceControls();
  }
  $('maintenance').addEventListener('change',async()=>{
    if(maintenanceBusy)return;
    const enabled=$('maintenance').checked,status=$('maintenance-action-status');
    try{
      const value=maintenanceInput(enabled);maintenanceBusy=true;maintenanceControls();
      status.textContent=enabled?'Đang bật bảo trì và đăng xuất người chơi…':'Đang kết thúc bảo trì…';
      const data=await api('/maintenance',value);clearDraft('settings-form',['maintenance-time-mode','maintenance-hours','maintenance-until','maintenance-message']);renderMaintenance(data.settings);
      status.textContent=data.settings.maintenance?'Đã bật bảo trì: người chơi đã được đăng xuất và đăng nhập tạm đóng.':'Đã tắt bảo trì. Người chơi có thể đăng nhập lại.';notify(status.textContent);
    }catch(error){
      $('maintenance').checked=maintenanceSaved;
      if(maintenanceBusy)try{renderMaintenanceStatus(await api('/settings'));}catch{}
      status.textContent='Thao tác bảo trì chưa thành công: '+error.message;notify(status.textContent,true);
    }finally{maintenanceBusy=false;maintenanceControls();}
  });
  $('maintenance-time-mode').addEventListener('change',maintenanceControls);
  function renderSettings(data,versions){
    if(!canRenderForm('settings-form',versions)){
      const draft=formDrafts.get('settings-form');
      if(!maintenanceBusy&&!draft.saving&&!draft.discarding&&![...draft.fields].some(id=>id.startsWith('maintenance'))&&(!versions||versions.get('settings-form')===draft.revision))renderMaintenance(data);
      return;
    }
    renderMaintenance(data);$('maintenance-action-status').textContent='';$('game-settings').replaceChildren();
    Object.entries(gameNames).forEach(([game,name])=>{const panel=el('article',undefined,'panel');panel.append(el('h2',name));const toggle=el('label',undefined,'toggle-label'),check=el('input');check.type='checkbox';check.id='enabled-'+game;check.checked=data.enabled[game];toggle.append(check,el('span','Nhận trận mới'));panel.append(toggle);settingFields[game].forEach(([key,label,min,max])=>{const id='setting-'+game+'-'+key,l=el('label',label),input=el('input');l.htmlFor=id;input.id=id;input.type='number';input.min=min;input.max=max;input.step=1;input.required=true;input.value=data.defaults[game][key];panel.append(l,input);});$('game-settings').append(panel);});
  }
  function renderBackups(rows){table($('backups-table'),[['Bản sao lưu',r=>r.name],['Thời gian',r=>date(r.created_at)],['Dung lượng',r=>(Number(r.size)/1024).toFixed(1)+' KB'],['Tải xuống',r=>{const a=el('a','↓ Tải bản sao','secondary');a.href='/api/admin/backup-file?'+new URLSearchParams({name:r.name});return a;}],['Cấu hình',r=>button('Áp dụng cấu hình',()=>openBackupConfiguration(r.name),'secondary')]],rows);}
  const configGroups={runtime:'Máy chủ & các trò chơi',race_settings:'Đường đua, rank & thử thách ngày',announcement:'Thông báo toàn server'};
  function configRows(key,value){
    const rows=[],add=(label,v,unit='')=>rows.push({label,value:typeof v==='boolean'?(v?'Bật':'Tắt'):v===undefined||v===null||v===''?'—':String(v)+unit});
    const moment=v=>v?new Date(Number(v)).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'})+' (giờ Việt Nam)':'Không đặt';
    if(key==='runtime'){
      add('Bảo trì',value.maintenance);add('Kết thúc bảo trì',moment(value.maintenanceUntil));add('Thông báo bảo trì',value.message);
      Object.entries(gameNames).forEach(([game,name])=>{add(name+' · Nhận trận mới',value.enabled?.[game]);settingFields[game].forEach(([field,label])=>add(name+' · '+label,value.defaults?.[game]?.[field]));});
    }else if(key==='race_settings'){
      [['enabled','Cho phép trận Đường đua mới'],['rankedEnabled','Mở đấu rank'],['dailyEnabled','Mở thử thách ngày'],['questionCount','Số câu mỗi trận'],['timeLimit','Giây mỗi câu · rank/ghép thường'],['queueSeconds','Giây chờ trước khi điền bot'],['targetPlayers','Số người/bot mục tiêu'],['firstRP','RP hạng 1'],['secondRP','RP hạng 2 · 3–4 người'],['duelSecondRP','RP hạng 2 · 2 người'],['thirdRP','RP hạng 3'],['fourthRP','RP hạng 4'],['rankBuffer','Điểm đệm khi lên bậc'],['demotionRP','RP sau khi tụt cấp'],['dailyTimeLimit','Giây mỗi câu · thử thách ngày'],['dailyCorrect','Số câu đúng để nhận thưởng ngày'],['shieldReward','Thẻ bảo vệ thưởng mỗi ngày'],['doubleReward','Thẻ x2 RP thưởng mỗi ngày']].forEach(([field,label])=>add(label,value[field]));
    }else{add('Phát thông báo',value.enabled);add('Nội dung',value.text);add('Hết hạn',moment(value.expiresAt));}
    return rows;
  }
  async function openBackupConfiguration(name){
    try{
      const data=await api('/backup-config?'+new URLSearchParams({name}));backupConfig=data;
      $('backup-config-name').textContent=data.name+(data.createdAt?' · Sao lưu lúc '+date(data.createdAt):'');$('backup-config-error').textContent='';
      $('backup-config-warnings').replaceChildren(...data.warnings.map(text=>el('p',text,'info-strip')));
      $('backup-config-preview').replaceChildren();
      Object.entries(data.snapshot||data.values).forEach(([key,value])=>{
        const section=el('section'),root=el('div',undefined,'table-wrap'),current=configRows(key,data.current[key]),next=configRows(key,value);
        section.append(el('h3',configGroups[key]),root);table(root,[['Thiết lập',r=>r.label],['Tại thời điểm sao lưu · Cố định',r=>el('strong',r.value,'backup-frozen-value')],['Máy chủ hiện tại · Đối chiếu',r=>textMeta(r.current,r.current!==r.value?'Khác bản sao lưu':'Giống bản sao lưu')]],next.map((r,i)=>({...r,current:current[i]?.value??'—'})));$('backup-config-preview').append(section);
      });
      $('backup-config-dialog').showModal();
    }catch(error){notify(error.message,true);}
  }
  $('backup-config-apply').addEventListener('click',async()=>{
    if(!backupConfig)return;const chosen=backupConfig,b=$('backup-config-apply');b.disabled=true;$('backup-config-error').textContent='';
    try{const result=await api('/backup-config/apply',{name:chosen.name,fingerprint:chosen.fingerprint});backupConfig=null;$('backup-config-dialog').close();await load();notify('Đã áp dụng cấu hình. Bản sao lưu trước khi thay đổi: '+result.safetyBackup);}
    catch(error){$('backup-config-error').textContent=error.message;}
    finally{b.disabled=false;}
  });
  function renderAnnouncement(data,versions){
    if(canRenderForm('announcement-form',versions)){$('announcement-text').value=data.text||'';$('announcement-minutes').value=data.expiresAt>0?Math.max(0,Math.ceil((data.expiresAt-Date.now())/60000)):0;}
    const active=data.enabled&&(!data.expiresAt||data.expiresAt>Date.now());
    $('announcement-status').textContent=active?'Đang phát · '+(data.expiresAt?'Kết thúc '+date(data.expiresAt):'Đến khi tắt'):'Hiện không có thông báo đang phát.';
  }
  function renderIntegrations(data){
    const g=data.google,s=data.smtp;
    $('google-diagnostics').replaceChildren(el('p','Client ID: '+(g.clientIdConfigured?'Đã cấu hình':'Chưa cấu hình')),el('p','Client secret: '+(g.clientSecretConfigured?'Đã cấu hình':'Chưa cấu hình')),el('h3','Authorized redirect URI'),el('pre',g.redirectUri||g.configurationError));
    $('smtp-diagnostics').replaceChildren(el('p','Email gửi: '+(s.senderConfigured?'Đã cấu hình':'Chưa hợp lệ')),el('p','Mật khẩu ứng dụng: '+(s.passwordConfigured?'Đã cấu hình':'Chưa cấu hình')),el('p',`${s.host}:${s.port} · ${s.security}`));
  }
  async function formSubmit(event,fn,dialogId){
    event.preventDefault();const form=event.currentTarget,submit=form.querySelector('button:not([type=button])'),error=dialogId?$(dialogId).querySelector('.dialog-error'):null,draft=formDrafts.get(form.id),revision=draft?.revision;
    if(draft?.saving||draft?.discarding)return;
    if(draft){draft.saving=true;draft.error='';updateDraftNote(form.id);if(form.id==='settings-form')maintenanceControls();}
    if(submit)submit.disabled=true;if(error)error.textContent='';
    try{
      await fn();if(dialogId)$(dialogId).close();
      if(draft){draft.saving=false;if(draft.revision===revision)clearDraft(form.id);else updateDraftNote(form.id);}
      notify(draft?.fields.size?'Đã lưu cấu hình đã gửi. Bạn còn chỉnh sửa mới chưa lưu.':'Đã lưu thay đổi và ghi nhật ký.');await load();
    }catch(e){if(error)error.textContent=e.message;else notify(e.message,true);}
    finally{if(draft){draft.saving=false;updateDraftNote(form.id);}if(submit)submit.disabled=false;if(form.id==='settings-form')maintenanceControls();}
  }
  $('login-form').addEventListener('submit',async event=>{event.preventDefault();$('login-button').disabled=true;$('login-error').textContent='';try{const session=await api('/login',{username:$('login-username').value.trim(),password:$('login-password').value});$('login-password').value='';signedIn(session);}catch(e){$('login-error').textContent=e.message;}finally{$('login-button').disabled=false;}});
  function signedIn(session){username=session.username;csrf=session.csrfToken;$('admin-name').textContent=username;$('login-screen').classList.add('hidden');$('admin-app').classList.remove('hidden');show(location.hash.slice(1)||'overview',false);}
  $('logout').addEventListener('click',async()=>{try{await api('/logout',{});loginScreen();}catch(e){notify(e.message,true);}});
  $('action-form').addEventListener('submit',e=>formSubmit(e,()=>action($('action-reason').value.trim(),Number($('action-minutes').value)),'action-dialog'));
  $('new-content').addEventListener('click',()=>openEditor());
  $('content-form').addEventListener('submit',e=>formSubmit(e,async()=>{
    const v={kind:contentKind,id:editor?.id||0,revision:editor?.revision||0,topic:$('editor-topic').value,status:$('editor-status').value,explanation:$('editor-explanation').value};
    if(contentKind==='QUESTION')Object.assign(v,{text:$('question-text').value,options:['a','b','c','d'].map(k=>$('option-'+k).value),correct:Number($('correct-answer').value),difficulty:$('editor-difficulty').value,usage:$('question-usage').value,rankTier:$('question-rank-tier').value});else Object.assign(v,{phrase:$('wheel-phrase').value,hint:$('wheel-hint').value});
    await api('/content/save',v);
  },'content-dialog'));
  $('new-topic').addEventListener('click',()=>openTopic());
  $('topic-form').addEventListener('submit',e=>formSubmit(e,()=>api('/topics/save',{scope:$('topic-scope').value,code:$('topic-code').value.toUpperCase(),name:$('topic-name').value,active:$('topic-active').checked}),'topic-dialog'));
  for(const name of ['users','matches','logs']){
    $(name+'-search').addEventListener('submit',e=>{e.preventDefault();offsets[name]=0;load();});
    $(name+'-prev').addEventListener('click',()=>{offsets[name]=Math.max(0,offsets[name]-50);load();});$(name+'-next').addEventListener('click',()=>{offsets[name]+=50;load();});
  }
  $('content-search').addEventListener('submit',e=>{e.preventDefault();load();});
  ['content-mode','content-rank-tier','content-difficulty','content-status'].forEach(id=>$(id).addEventListener('change',load));
  $('clear-content-filters').addEventListener('click',()=>{['content-query','content-mode','content-rank-tier','content-difficulty','content-status'].forEach(id=>$(id).value='');load();});
  $('settings-form').addEventListener('submit',e=>formSubmit(e,async()=>{
    const enabled={},defaults={};Object.keys(gameNames).forEach(game=>{enabled[game]=$('enabled-'+game).checked;defaults[game]={};settingFields[game].forEach(([key])=>defaults[game][key]=Number($('setting-'+game+'-'+key).value));});const settings={message:$('maintenance-message').value,enabled,defaults,...maintenanceInput($('maintenance').checked)};
    await api('/settings',settings);
  }));
  $('announcement-form').addEventListener('submit',e=>formSubmit(e,()=>api('/announcement',{enabled:true,text:$('announcement-text').value.trim(),minutes:Number($('announcement-minutes').value)})));
  $('announcement-clear').addEventListener('click',async()=>{const b=$('announcement-clear');b.disabled=true;try{await api('/announcement',{enabled:false,text:'',minutes:0});await load();notify('Đã tắt thông báo trên toàn server.');}catch(e){notify(e.message,true);}finally{b.disabled=false;}});
  $('mail-test').addEventListener('click',async()=>{const b=$('mail-test'),r=$('mail-test-result');b.disabled=true;r.textContent='Đang kiểm tra TLS và xác thực SMTP…';try{const d=await api('/mail-test',{});r.textContent=d.message||'Kết nối và xác thực SMTP thành công.';}catch(e){r.textContent=e.message;}finally{b.disabled=false;}});
  $('password-form').addEventListener('submit',async e=>{e.preventDefault();const form=e.currentTarget;if($('new-password').value!==$('confirm-password').value){notify('Mật khẩu xác nhận không khớp.',true);return;}const b=form.querySelector('button');b.disabled=true;try{await api('/password',{currentPassword:$('old-password').value,newPassword:$('new-password').value});form.reset();loginScreen();$('login-error').textContent='Đã đổi mật khẩu. Đăng nhập lại để tiếp tục.';}catch(error){notify(error.message,true);}finally{b.disabled=false;}});
  $('create-backup').addEventListener('click',async()=>{const b=$('create-backup');b.disabled=true;try{const data=await api('/backup',{});await load();notify('Đã tạo bản sao lưu '+data.name);}catch(e){notify(e.message,true);}finally{b.disabled=false;}});
  $('open-import').addEventListener('click',()=>{$('import-form').reset();previewCsv=null;$('confirm-import').disabled=true;$('import-preview').replaceChildren();$('import-dialog').querySelector('.dialog-error').textContent='';$('import-dialog').showModal();});
  $('csv-text').addEventListener('input',()=>{previewCsv=null;$('confirm-import').disabled=true;$('import-preview').replaceChildren();});
  $('csv-file').addEventListener('change',async()=>{const file=$('csv-file').files[0];if(!file)return;if(file.size>1400000){$('import-dialog').querySelector('.dialog-error').textContent='Tệp quá lớn.';return;}$('csv-text').value=await file.text();$('csv-text').dispatchEvent(new Event('input'));});
  $('csv-template').addEventListener('click',()=>{const csv=contentKind==='QUESTION'?'text,option_a,option_b,option_c,option_d,correct,topic,difficulty,usage,rank_tier,explanation,status\r\nCổng HTTP mặc định là bao nhiêu?,21,443,80,8080,C,NETWORKING,EASY,RACE,BRONZE,HTTP sử dụng cổng 80 theo mặc định.,DRAFT\r\n':'phrase,hint,topic,explanation,status\r\nLAP TRINH MANG,Một môn học chuyên ngành,IT,Kiến thức kết nối và trao đổi dữ liệu giữa các máy.,DRAFT\r\n';const url=URL.createObjectURL(new Blob(['\uFEFF'+csv],{type:'text/csv;charset=utf-8'})),a=el('a');a.href=url;a.download=contentKind==='QUESTION'?'mau-cau-hoi.csv':'mau-o-chu.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  $('validate-import').addEventListener('click',async()=>{const b=$('validate-import');b.disabled=true;const err=$('import-dialog').querySelector('.dialog-error');err.textContent='';try{const csv=$('csv-text').value;const data=await api('/content/import',{kind:contentKind,csv,dryRun:true});previewCsv=csv;$('confirm-import').disabled=false;$('import-preview').replaceChildren(el('p',`${data.count} dòng hợp lệ. Xem trước tối đa 5 dòng:`, 'muted'),el('pre',JSON.stringify(data.preview,null,2)));}catch(e){previewCsv=null;$('confirm-import').disabled=true;err.textContent=e.message;}finally{b.disabled=false;}});
  $('import-form').addEventListener('submit',e=>formSubmit(e,async()=>{if(previewCsv===null||previewCsv!==$('csv-text').value)throw new Error('Hãy kiểm tra CSV trước khi nhập.');await api('/content/import',{kind:contentKind,csv:previewCsv,dryRun:false});previewCsv=null;},'import-dialog'));
  const raceSettings=['questionCount','timeLimit','queueSeconds','targetPlayers','firstRP','secondRP','duelSecondRP','thirdRP','fourthRP','rankBuffer','demotionRP'];
  const dailySettings=['dailyTimeLimit','dailyCorrect','shieldReward','doubleReward'];
  const rankNames=['Đồng 3','Đồng 2','Đồng 1','Bạc 3','Bạc 2','Bạc 1','Vàng 3','Vàng 2','Vàng 1','Bạch Kim 3','Bạch Kim 2','Bạch Kim 1','Kim Cương 3','Kim Cương 2','Kim Cương 1','Cao Thủ'];
  function raceAction(action,matchId,targetUsername){actionDialog('Đường đua · '+({cancel:'Hủy trận',kick:'Kick người chơi',dequeue:'Xóa khỏi hàng chờ',notice:'Thông báo phòng'}[action]),targetUsername||matchId,reason=>api('/race/action',{action,matchId,targetUsername,reason}));}
  function renderRaceSettings(settings,versions){
    if(!canRenderForm('race-settings-form',versions))return;
    $('race-rankedEnabled').checked=settings.rankedEnabled;for(const key of raceSettings)$('race-'+key).value=settings[key];
  }
  function renderRace(data,versions){
    renderRaceSettings(data.settings,versions);
    table($('race-queue-table'),[['Người chơi',r=>r.username],['Chế độ',r=>r.mode],['Vào lúc',r=>date(r.joinedAt)],['Tổng RP',r=>r.rankTotal],['Thao tác',r=>button('Xóa khỏi hàng chờ',()=>raceAction('dequeue','',r.username),'danger')]],data.queue);
    table($('race-rooms-table'),[['Phòng',r=>textMeta(r.roomName,r.roomCode)],['Chế độ',r=>r.mode],['Trạng thái',r=>r.phase],['Câu',r=>r.round+'/'+r.questionCount],['Người chơi',r=>{const root=el('div');r.players.forEach(p=>root.append(group(el('span',p.username+(p.bot?' · BOT':'')),...(!p.bot&&!p.forfeited?[button('Kick',()=>raceAction('kick',r.matchId,p.username),'danger')]:[]))));return root;}],['Điều hành',r=>group(button('Thông báo',()=>raceAction('notice',r.matchId,'')),button('Hủy trận',()=>raceAction('cancel',r.matchId,''),'danger'))]],data.rooms);
    table($('race-players-table'),[['Người chơi',r=>r.username],['Rank hiện tại',r=>(rankNames[r.current_step||0])+' · '+(r.current_points||0)+' RP'],['Cao nhất',r=>(rankNames[r.highest_step||0])+' · '+(r.highest_points||0)+' RP'],['Trận / thắng',r=>(r.ranked_games||0)+' / '+(r.ranked_wins||0)],['Thẻ bảo vệ / x2',r=>r.shields+' / '+r.doubles],['Điều chỉnh',r=>button('Chỉnh sửa',()=>openRacePlayer(r),'secondary')]],data.players);
    table($('race-history-table'),[['Người chơi',r=>r.username],['Phòng',r=>textMeta(r.room_name,r.mode)],['Trạng thái',r=>r.status],['Điểm / hạng',r=>r.score+' / '+r.placement],['RP',r=>r.result.rpDelta||0],['Bắt đầu',r=>date(r.started_at)],['Chi tiết',r=>button('Câu hỏi & đáp án',()=>raceReview(r))]],data.history);
  }
  async function raceReview(row){try{const data=await api('/race/review?'+new URLSearchParams({id:row.id,username:row.username}));const nodes=[el('p',row.username+' · '+data.match.status,'muted')];for(const r of data.questions){const q=r.question;nodes.push(el('h3',r.round_no+'. '+q.text),el('p','Đã chọn: '+(r.answer===null?'Chưa trả lời':q.options[r.answer])),el('p','Đáp án đúng: '+q.options[q.correct]),el('p',q.explanation));}detail('Ôn tập · '+row.room_name,nodes);}catch(e){notify(e.message,true);}}
  function openRacePlayer(row={}){$('race-player-form').reset();$('race-target').value=row.username||'';$('race-step').value=row.current_step||0;$('race-rp').value=row.current_points||0;$('race-shields').value=row.shields||0;$('race-doubles').value=row.doubles||0;racePlayerFields();$('race-player-dialog').querySelector('.dialog-error').textContent='';$('race-player-dialog').showModal();}
  function raceRankLimit(){const master=Number($('race-step').value)===15;$('race-rp').max=master?'':'99';$('race-rp-label').textContent=master?'Điểm Cao Thủ · Không áp trần RP':'RP trong cấp · 0–99';}
  function racePlayerFields(){const action=$('race-player-action').value;$('race-rank-fields').classList.toggle('hidden',action!=='setRank');$('race-card-fields').classList.toggle('hidden',action!=='setCards');raceRankLimit();}
  $('race-step').addEventListener('change',raceRankLimit);
  $('race-open-player').addEventListener('click',()=>openRacePlayer());$('race-player-action').addEventListener('change',racePlayerFields);
  $('race-settings-form').addEventListener('submit',e=>formSubmit(e,()=>{const value={rankedEnabled:$('race-rankedEnabled').checked};for(const key of raceSettings)value[key]=Number($('race-'+key).value);return api('/race/rank-settings',value);}));
  $('race-player-form').addEventListener('submit',e=>formSubmit(e,()=>api('/race/action',{action:$('race-player-action').value,targetUsername:$('race-target').value.trim(),step:Number($('race-step').value),rp:Number($('race-rp').value),shields:Number($('race-shields').value),doubles:Number($('race-doubles').value),reason:$('race-player-reason').value.trim()}),'race-player-dialog'));
  function renderRaceService(data,versions){if(canRenderForm('race-service-form',versions))$('race-service-enabled').checked=data.enabled;}
  function renderDailyForm(data,versions){if(!canRenderForm('daily-settings-form',versions))return;$('daily-enabled').checked=data.dailyEnabled;for(const key of dailySettings)$('daily-'+key).value=data[key];}
  function renderDailySettings(data,versions){renderRaceService(data,versions);renderDailyForm(data,versions);}
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
  setInterval(()=>{if(username&&!document.hidden&&['overview','rooms','race','leaderboard','settings','announcements'].includes(view)&&!document.querySelector('dialog[open]')&&(view!=='leaderboard'||document.activeElement!==$('rank-board-query')))load();},10000);
  setInterval(()=>{if(username&&!document.hidden&&view==='users'&&!document.querySelector('dialog[open]'))load();},3000);
  let lastTouch=0;document.addEventListener('pointerdown',()=>{if(username&&Date.now()-lastTouch>60000){lastTouch=Date.now();api('/session').catch(()=>{});}});
  api('/session').then(signedIn).catch(()=>loginScreen());
})();
