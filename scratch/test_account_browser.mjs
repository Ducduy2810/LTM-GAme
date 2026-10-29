import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
const base=process.argv[2];
if(!/^http:\/\/localhost:\d+$/.test(base||''))throw new Error('Use the isolated account test preview URL');
const profile=path.resolve('scratch',`account-browser-${Date.now()}`);fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws;
try {
  let port;for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(150);}}
  assert.ok(port);const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json(),page=tabs.find(t=>t.type==='page'&&t.url==='about:blank');
  ws=new WebSocket(page.webSocketDebuggerUrl.replace('localhost','127.0.0.1'));await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id,t=setTimeout(()=>{pending.delete(n);reject(new Error('CDP timeout '+method));},12000);pending.set(n,{resolve:r=>{clearTimeout(t);resolve(r);},reject:e=>{clearTimeout(t);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
  async function until(expression){for(let i=0;i<90;i++){if(await evaluate(expression))return;await sleep(100);}throw new Error('Timed out '+expression+'; '+await evaluate('document.body.innerText.slice(0,2000)'));}
  async function navigate(url){await call('Page.navigate',{url});await until('document.readyState==="complete"');}
  async function login(password){await evaluate(`document.getElementById('login-username').value='owner';document.getElementById('login-password').value=${JSON.stringify(password)};document.getElementById('btn-login').click()`);await until('!document.getElementById("portal-section").classList.contains("hidden")');}
  async function adminPost(path,body){const r=await evaluate(`fetch(${JSON.stringify('/api/admin'+path)},{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':${JSON.stringify(adminCsrf)}},body:JSON.stringify(${JSON.stringify(body)})}).then(async r=>({status:r.status,data:await r.json()}))`);assert.equal(r.status,200,JSON.stringify(r));return r.data;}
  async function dismiss(){if(await evaluate('!!document.querySelector("#account-notice-dialog[open]")')){await evaluate('document.querySelector("#account-notice-dialog button").click()');await sleep(150);}}
  await call('Runtime.enable');await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1040,deviceScaleFactor:1,mobile:false});
  await navigate(base+'/admin');await until('!!document.getElementById("login-button")');
  await evaluate('document.getElementById("login-username").value="operator";document.getElementById("login-password").value="AdminOnlyPass123!";document.getElementById("login-button").click()');await until('!document.getElementById("admin-app").classList.contains("hidden")');
  const adminSession=await evaluate('fetch("/api/admin/session").then(r=>r.json())'),adminCsrf=adminSession.csrfToken;
  assert.ok(adminCsrf);
  await evaluate('document.querySelector(".sidebar [data-view=announcements]").click()');await until('!document.getElementById("view-announcements").classList.contains("hidden")');
  const text='Chúc các bạn học tốt! <img src=x onerror=alert(1)>';
  await evaluate(`document.getElementById('announcement-text').value=${JSON.stringify(text)};document.getElementById('announcement-minutes').value='0';document.getElementById('announcement-form').requestSubmit()`);
  await until('!!document.getElementById("server-announcement")');assert.equal(await evaluate('document.querySelector(".announcement-text").textContent'),text);assert.equal(await evaluate('document.querySelectorAll("#server-announcement img").length'),0,'announcement safely rendered as text');
  await evaluate('document.querySelector(".sidebar [data-view=integrations]").click()');await until('document.getElementById("google-diagnostics").textContent.includes("/auth/google/callback")');
  await navigate(base+'/');await until('!!document.getElementById("server-announcement")');await login('NextPassword456');await dismiss();
  await evaluate('document.querySelector(".nav-item[data-view=profile]").click()');await until('document.getElementById("profile-current-rank").textContent==="Vàng"');
  assert.equal(await evaluate('document.getElementById("profile-highest-rank").textContent'),'Kim Cương');
  let shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync('scratch/account-profile-desktop.png',Buffer.from(shot.data,'base64'));
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(200);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'profile mobile has no horizontal overflow');
  shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync('scratch/account-profile-mobile.png',Buffer.from(shot.data,'base64'));
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1040,deviceScaleFactor:1,mobile:false});
  await evaluate('document.getElementById("player-current-password").value="incorrect";document.getElementById("player-new-password").value="BrowserChanged789";document.getElementById("player-confirm-password").value="BrowserChanged789";document.getElementById("player-password-form").requestSubmit()');
  await until('document.getElementById("player-password-message").textContent.includes("không đúng")');
  await evaluate('document.getElementById("player-current-password").value="NextPassword456";document.getElementById("player-password-form").requestSubmit()');await until('!document.getElementById("auth-section").classList.contains("hidden")');await login('BrowserChanged789');await dismiss();
  for(const game of ['arena','wheel','survival']){await navigate(base+`/${game}.html`);await until('!!document.getElementById("server-announcement")');assert.equal(await evaluate('document.querySelector(".announcement-text").textContent'),text,game+' banner');}
  const owner=(await evaluate('fetch("/api/admin/users?q=owner").then(r=>r.json())')).items.find(u=>u.username==='owner');
  await adminPost('/users/action',{userId:owner.id,action:'lock',minutes:60,reason:'Khóa thử từ trình duyệt'});
  await until('!!document.querySelector("#account-notice-dialog[open]")');assert.ok((await evaluate('document.getElementById("account-notice-dialog").textContent')).includes('Khóa thử từ trình duyệt'),'online game receives lock reason');
  await dismiss();await until('location.pathname==="/"');await until('!!document.getElementById("btn-login")');
  await evaluate('document.getElementById("login-username").value="owner";document.getElementById("login-password").value="BrowserChanged789";document.getElementById("btn-login").click()');
  await until('!!document.querySelector("#account-notice-dialog[open]")');assert.ok((await evaluate('document.getElementById("account-notice-dialog").textContent')).includes('Khóa thử từ trình duyệt'),'locked login shows reason');await dismiss();
  await adminPost('/users/action',{userId:owner.id,action:'unlock',reason:'Đã kiểm tra xong'});await login('BrowserChanged789');await dismiss();
  await adminPost('/users/action',{userId:owner.id,action:'disconnect',reason:'Ngắt phiên tại trang chủ'});await until('!!document.querySelector("#account-notice-dialog[open]")');assert.ok((await evaluate('document.getElementById("account-notice-dialog").textContent')).includes('Ngắt phiên tại trang chủ'),'homepage receives exact disconnect reason');await dismiss();
  await adminPost('/announcement',{enabled:false,text:'',minutes:0});await until('!document.getElementById("server-announcement")');
  assert.deepEqual(errors,[],'no unhandled JS errors');
  console.log('PASS: real browser announcement publishing, safe text on all 3 games, responsive ranks, password change, online and login moderation reasons.');
}finally{ws?.close();browser.kill();console.log('BROWSER_PROFILE='+profile);}
