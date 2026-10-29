import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

const base=process.argv[2];
if(!/^http:\/\/localhost:\d+$/.test(base||''))throw new Error('Pass the isolated preview URL');
const profile=path.resolve('scratch',`portal-browser-${Date.now()}`);
fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',[
  '--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',
  '--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'
],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let ws;
try{
  let port;
  for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(150);}}
  assert.ok(port,'Chrome debugging port is available');
  const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const page=tabs.find(t=>t.type==='page'&&t.url==='about:blank') || tabs.find(t=>t.type==='page');
  ws=new WebSocket(page.webSocketDebuggerUrl.replace('localhost','127.0.0.1'));
  await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text+': '+m.params.exceptionDetails.exception?.description);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id;const timer=setTimeout(()=>{pending.delete(n);reject(new Error('CDP timeout: '+method));},10000);pending.set(n,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
  async function until(expression){for(let i=0;i<60;i++){if(await evaluate(expression))return;await sleep(100);}throw new Error('Timed out: '+expression+'; '+await evaluate('JSON.stringify({url:location.href,title:document.title,text:document.body?.innerText.slice(0,400)})'));}
  async function navigate(url){await call('Page.navigate',{url});await until('document.readyState==="complete"');}
  await call('Runtime.enable');await call('Page.enable');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1120,deviceScaleFactor:1,mobile:false});
  await navigate(base);
  await until('!!document.getElementById("btn-login")');
  await evaluate('document.getElementById("login-username").value="learner";document.getElementById("login-password").value="TestOnlyPass123";document.getElementById("btn-login").click()');
  await until('!document.getElementById("portal-section").classList.contains("hidden")');
  await until('document.getElementById("server-state").textContent.includes("trực tuyến")');
  assert.equal(await evaluate('document.getElementById("admin-nav").getAttribute("href")'),'/admin');
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'desktop has no horizontal overflow');
  await sleep(600);
  let shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
  fs.writeFileSync('scratch/portal-desktop.png',Buffer.from(shot.data,'base64'));
  await evaluate('document.querySelector(".nav-item[data-view=practice]").click();document.getElementById("practice-next").click()');
  await until('document.querySelectorAll(".answer-button").length===4');
  await evaluate('document.querySelector(".answer-button").click()');
  await until('!!document.querySelector(".answer-button.correct")');
  await evaluate('document.querySelector(".nav-item[data-view=history]").click()');
  await until('document.querySelectorAll("#full-history .history-row").length>=3');
  await evaluate('document.querySelector(".nav-item[data-view=rank]").click()');
  assert.equal(await evaluate(`!!document.querySelector('#view-rank a[href="/race.html?mode=ranked"]')`),true);
  assert.equal(await evaluate(`!!document.querySelector('#view-rank a[href*="mode=daily"]')`),false,'daily removed from ranked section');
  await evaluate('document.querySelector(".nav-item[data-view=daily]").click()');assert.equal(await evaluate('!document.getElementById("view-daily").classList.contains("hidden")'),true);assert.equal(await evaluate(`!!document.querySelector('#view-daily a[href="/race.html?mode=daily"]')`),true);
  assert.equal(await evaluate(`!!document.querySelector('#bot-list a[href="/race.html?mode=bot"]')`),true,'knowledge race listed among bot games');assert.equal(await evaluate(`!!document.querySelector('#pvp-list a[href="/race.html?mode=casual"]')`),true,'knowledge race listed among casual games');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  await evaluate('document.querySelector(".nav-item[data-view=home]").click()');
  await sleep(200);
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'mobile has no horizontal overflow');
  shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
  fs.writeFileSync('scratch/portal-mobile.png',Buffer.from(shot.data,'base64'));
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  for(const game of ['arena','wheel']){
    await navigate(`${base}/${game}.html?mode=bot`);
    await until('!!document.getElementById("btn-open-create-modal")');
    await evaluate('document.getElementById("btn-open-create-modal").click()');
    assert.ok(Number(await evaluate('document.getElementById("create-bot-count").value'))>0,`${game} bot settings prefilled`);
  }
  await navigate(`${base}/wheel.html?mode=pvp`);
  await until('!!document.getElementById("btn-open-create-modal")');
  await evaluate('document.getElementById("btn-open-create-modal").click()');
  assert.equal(await evaluate('document.getElementById("create-bot-count").value'),'0');
  assert.deepEqual(errors,[],'no browser JavaScript exceptions');
  console.log('PASS: browser login, map, practice, history, rank availability, desktop/mobile layout, and bot/PvP room preferences.');
  console.log('Screenshots: scratch/portal-desktop.png, scratch/portal-mobile.png');
}finally{ws?.close();browser.kill();}
