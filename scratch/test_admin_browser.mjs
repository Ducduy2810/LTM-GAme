import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
const base=process.argv[2];
if(!/^http:\/\/localhost:\d+$/.test(base||''))throw new Error('Use an isolated admin preview URL');
const profile=path.resolve('scratch',`admin-browser-${Date.now()}`);fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws;
try{
  let port;for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(150);}}
  assert.ok(port);const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json(),page=tabs.find(t=>t.type==='page'&&t.url==='about:blank');
  ws=new WebSocket(page.webSocketDebuggerUrl.replace('localhost','127.0.0.1'));await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id,t=setTimeout(()=>{pending.delete(n);reject(new Error('CDP timeout '+method));},10000);pending.set(n,{resolve:r=>{clearTimeout(t);resolve(r);},reject:e=>{clearTimeout(t);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
  async function until(expression){for(let i=0;i<80;i++){if(await evaluate(expression))return;await sleep(100);}throw new Error('Timed out '+expression+'; '+await evaluate('document.body.innerText.slice(0,1200)'));}
  async function navigate(url){await call('Page.navigate',{url});await until('document.readyState==="complete"');}
  await call('Runtime.enable');await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1040,deviceScaleFactor:1,mobile:false});
  await navigate(base+'/admin');await until('!!document.getElementById("login-button")');
  await evaluate('document.getElementById("login-username").value="operator";document.getElementById("login-password").value="AdminTestOnlyPass456!";document.getElementById("login-button").click()');
  await until('!document.getElementById("admin-app").classList.contains("hidden")');await until('document.querySelectorAll(".stat-card").length===6');
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true);
  await sleep(400);let shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync('scratch/admin-desktop.png',Buffer.from(shot.data,'base64'));
  for(const view of ['users','rooms','questions','wheel','topics','matches','logs','settings','backups','account']){
    await evaluate(`document.querySelector('.sidebar [data-view="${view}"]').click()`);await sleep(200);assert.equal(await evaluate('document.getElementById("notice").classList.contains("error-notice")&&!document.getElementById("notice").classList.contains("hidden")'),false,view+' loads');
  }
  await evaluate('document.querySelector(".sidebar [data-view=questions]").click()');await until('document.querySelectorAll("#content-table tr").length>1');
  await evaluate('document.getElementById("new-content").click()');await until('document.getElementById("content-dialog").open');
  await evaluate('document.getElementById("question-text").value="Câu hỏi tạo từ giao diện trình duyệt";for(const k of ["a","b","c","d"])document.getElementById("option-"+k).value=k;document.getElementById("editor-status").value="DRAFT";document.getElementById("content-form").requestSubmit()');
  await until('!document.getElementById("content-dialog").open');await until('document.getElementById("content-table").textContent.includes("Câu hỏi tạo từ giao diện")');
  await evaluate('document.getElementById("open-import").click()');await until('document.getElementById("import-dialog").open');await evaluate('document.querySelector("[data-close=import-dialog]").click()');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await evaluate('document.querySelector(".sidebar [data-view=overview]").click()');await sleep(200);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'admin mobile does not overflow');shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync('scratch/admin-mobile.png',Buffer.from(shot.data,'base64'));
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1040,deviceScaleFactor:1,mobile:false});
  for(const [game,create,join,code] of [['arena','create_room','join_room','UI_ARENA'],['wheel','create_wheel_room','join_wheel_room','UI_WHEEL'],['survival','create_survival_room','join_survival_room','UI_SURVIVAL']]){
    await evaluate('fetch("/auth/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:"owner",password:"TestPlayerPass123"})}).then(r=>r.json())');
    await navigate(base+`/${game}.html?mode=pvp`);await until('!!document.getElementById("btn-open-create-modal")');await sleep(250);
    await evaluate(`document.getElementById('btn-open-create-modal').click();document.getElementById('create-room-name').value='Browser host test';document.getElementById('create-room-code').value='${code}';document.getElementById('btn-submit-create-room').click()`);
    await until('!!document.querySelector(".host-tools:not([hidden])")');
    const forbidden=game==='arena'?['btn-delete-room-game','pause-controls']:game==='survival'?['btn-survival-pause']:[];
    for(const id of forbidden)assert.equal(await evaluate(`getComputedStyle(document.getElementById('${id}')).display`),'none',game+' owner control removed: '+id);
    await evaluate(`fetch('/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'guest',password:'TestPlayerPass123'})}).then(r=>r.json()).then(()=>new Promise(resolve=>{window.guestPackets=[];window.guestSocket=new WebSocket('ws://'+location.host+'/ws');guestSocket.onopen=()=>{guestSocket.send(JSON.stringify({type:'${join}',roomCode:'${code}'}));resolve();};guestSocket.onmessage=e=>guestPackets.push(JSON.parse(e.data));}))`);
    await until('document.querySelector(".host-tools:not([hidden])").textContent.includes("guest")');
    await evaluate('window.confirm=()=>true;window.alert=()=>{};document.querySelector(".host-tools").open=true;document.querySelector(".host-tools-member button").click()');
    await until('guestPackets.some(p=>p.type==="room_kicked")');
    assert.equal(await evaluate('document.querySelectorAll(".host-tools-member button").length'),0,game+' kicked member removed');
    await evaluate(`guestSocket.send(JSON.stringify({type:'${join}',roomCode:'${code}'}))`);await sleep(150);assert.equal(await evaluate('document.querySelectorAll(".host-tools-member button").length'),0,game+' kicked member cannot rejoin');
    await evaluate('guestSocket.close()');
    assert.equal(await evaluate('!!document.querySelector(".host-tools-delete")'),true,game+' host can delete waiting room');
    await evaluate('document.querySelector(".host-tools-delete").click()');
    await until('document.getElementById("lobby-screen").classList.contains("active")');
    assert.equal(await evaluate('document.querySelector(".host-tools").hidden'),true,game+' waiting deletion returns to lobby');
  }
  assert.deepEqual(errors,[],'no unhandled JavaScript errors');
  console.log('PASS: admin modules, responsive layout, real host kick and waiting-room deletion in all 3 games.');
  console.log('Screenshots: scratch/admin-desktop.png and scratch/admin-mobile.png');
}finally{ws?.close();browser.kill();}
