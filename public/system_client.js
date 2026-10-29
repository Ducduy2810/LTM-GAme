(() => {
  'use strict';
  let account=null,source=null,identity='',expectedLogout=false,invalid=false,currentNotice=null;
  const seen=new Set();
  const element=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
  const atAdmin=()=>location.pathname.startsWith('/admin');
  function inlineReason(message){const box=document.getElementById('auth-msg');if(box){box.textContent=message;box.style.color='#b4533b';}}
  function remember(data){try{sessionStorage.setItem('account-last-reason',JSON.stringify({message:data.message||data.reason||'',username:data.username||account?.username||''}));}catch{}}
  function announcement(value){
    let banner=document.getElementById('server-announcement');
    if(!value.enabled||!value.text){banner?.remove();document.body.classList.remove('has-server-announcement');return;}
    if(!banner){banner=element('aside',undefined,'server-announcement');banner.id='server-announcement';banner.setAttribute('aria-label','Thông báo toàn máy chủ');const label=element('strong','📢 THÔNG BÁO');const track=element('div',undefined,'announcement-track'),text=element('span',undefined,'announcement-text');track.append(text);banner.append(label,track);document.body.prepend(banner);}
    const text=banner.querySelector('.announcement-text');if(text.textContent!==value.text){text.textContent=value.text;text.style.animationDuration=Math.max(18,Math.min(90,value.text.length/4))+'s';}
    document.body.classList.add('has-server-announcement');
  }
  async function acknowledge(){
    const notice=currentNotice;currentNotice=null;
    if(account?.csrfToken&&notice?.id&&!invalid)try{await fetch('/auth/notice/ack',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':account.csrfToken},body:JSON.stringify({noticeId:notice.id})});}catch{}
    document.getElementById('account-notice-dialog')?.close();
    if(invalid&&!atAdmin()){
      if(location.pathname==='/'||location.pathname.endsWith('/index.html')){window.checkAuth?.();}
      else location.assign('/');
    }
  }
  function showNotice(value={},fallback=''){
    if(!value||(!Object.keys(value).length&&!fallback))return;
    const key=value.id?'notice-'+value.id:JSON.stringify([value.kind,value.reason,value.blocked_until,fallback]);if(seen.has(key))return;seen.add(key);currentNotice=value;
    let dialog=document.getElementById('account-notice-dialog');
    if(!dialog){dialog=element('dialog',undefined,'account-notice-dialog');dialog.id='account-notice-dialog';const heading=element('h2'),reason=element('p',undefined,'account-notice-reason'),time=element('p',undefined,'account-notice-until'),button=element('button','Đã hiểu');button.type='button';button.addEventListener('click',acknowledge);dialog.append(heading,reason,time,button);dialog.addEventListener('cancel',e=>{e.preventDefault();acknowledge();});document.body.append(dialog);}
    dialog.querySelector('h2').textContent={LOCK:'Tài khoản đã bị khóa',DISCONNECT:'Phiên đăng nhập đã bị ngắt',UNLOCK:'Tài khoản đã được mở khóa'}[value.kind]||'Thông báo tài khoản';
    dialog.querySelector('.account-notice-reason').textContent=value.reason?'Lý do: '+value.reason:fallback;
    const until=Number(value.blocked_until||0);dialog.querySelector('.account-notice-until').textContent=value.kind==='LOCK'?(until===-1?'Khóa đến khi quản trị viên mở lại.':until>Date.now()?'Thời hạn: '+new Date(until).toLocaleString('vi-VN'):''):'';
    if(!dialog.open)dialog.showModal();
  }
  function authError(data){
    if(expectedLogout)return;invalid=true;remember(data);inlineReason(data.message||'Phiên đã kết thúc.');showNotice(data.accountNotice||{},data.message||'Phiên đăng nhập đã kết thúc.');
    if(!atAdmin()&&typeof window.checkAuth==='function')window.checkAuth();
  }
  function connect(){
    if(source)source.close();if(!window.EventSource)return;
    const active=new EventSource('/api/events');source=active;
    active.addEventListener('announcement',e=>{if(source===active)try{announcement(JSON.parse(e.data));}catch{}});
    active.addEventListener('account_notice',e=>{if(source===active)try{authError(JSON.parse(e.data));}catch{}});
    active.addEventListener('session_ended',()=>{if(source===active&&!expectedLogout&&!invalid){if(typeof window.checkAuth==='function')window.checkAuth();else if(!atAdmin())authError({message:'Phiên đăng nhập đã kết thúc. Vui lòng đăng nhập lại.'});}});
  }
  function setAccount(user,notice){
    const next=(user?.username||'')+':'+(user?.csrfToken||'');
    if(user&&!account&&invalid)seen.clear();
    account=user||null;
    if(next!==identity){identity=next;expectedLogout=false;connect();}
    if(user){invalid=false;try{const last=JSON.parse(sessionStorage.getItem('account-last-reason')||'null');if(last&&last.username!==user.username)sessionStorage.removeItem('account-last-reason');}catch{}}
    if(notice&&Object.keys(notice).length)showNotice(notice);
  }
  window.SystemClient={setAccount,showNotice,handleAuthError:authError,expectLogout(value=true){expectedLogout=value;},refresh(){fetch('/auth/me',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(data=>{if(data)setAccount(data.loggedIn?data.user:null,data.accountNotice);}).catch(()=>{});}};
  try{const previous=JSON.parse(sessionStorage.getItem('account-last-reason')||'null');if(previous){inlineReason(previous.message);sessionStorage.removeItem('account-last-reason');}}catch{}
  connect();window.SystemClient.refresh();
  window.addEventListener('beforeunload',()=>source?.close());
})();
