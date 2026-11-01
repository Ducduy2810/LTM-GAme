import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

const base='http://127.0.0.1:3000';
const profile=path.resolve('scratch',`admin-browser-readable-${Date.now()}`);
fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let ws;
try {
  let port;
  for(let i=0;i<80;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(100);}}
  assert.ok(port,'browser starts');
  const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl.replace('localhost','127.0.0.1'));
  await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id,timer=setTimeout(()=>reject(new Error('CDP timeout '+method)),12000);pending.set(n,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const result=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error(JSON.stringify(result.exceptionDetails));return result.result.value;}
  async function until(expression){for(let i=0;i<80;i++){if(await evaluate(expression))return;await sleep(100);}throw new Error('Timed out: '+expression);}
  async function screenshot(name){const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync(path.join(profile,name+'.png'),Buffer.from(shot.data,'base64'));}
  await call('Runtime.enable');await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument',{source:`(() => {
    const originalFetch=window.fetch.bind(window),time=1793485056000;
    const question={text:'Giao thức nào bảo mật kết nối web?',topic:'NETWORKING',difficulty:'EASY',options:['HTTP','HTTPS','FTP','SMTP'],correct:1,explanation:'HTTPS bảo vệ dữ liệu bằng TLS.'};
    const matches=[{id:'wheel',room_code:'W7877',game_type:'WHEEL',status:'CANCELLED',started_at:time,ended_at:time+10000,reason:'Không còn người chơi',player_count:1,config_json:JSON.stringify({timeLimit:10,hint:'Mô hình phân tán lưu trữ chuỗi khối bất biến',topic:'ALL',explanation:'',phrase:'CONG NGHE BLOCKCHAIN'})},{id:'arena',room_code:'A100',game_type:'ARENA',status:'COMPLETED',started_at:time,player_count:2,config_json:JSON.stringify({topic:'NETWORKING',difficulty:'EASY',questionCount:2,botCount:0,questions:[question,question]})}];
    const logs=[{actor:'admin',action:'ADMIN_LOGIN',target:'login',details:JSON.stringify({remote:'0:0:0:0:0:0:0:1'}),created_at:time},{actor:'admin',action:'ADMIN_KICK',target:'WHEEL:W7877',details:JSON.stringify({target:'ducbuitrung',reason:'Vi phạm quy định phòng chơi'}),created_at:time},{actor:'admin',action:'RACE_SETRANK',target:'ducbuitrung',details:JSON.stringify({reason:'Điều chỉnh kết quả trận',before:{rank:[{current_step:3,current_points:50}]},request:{step:4,rp:20}}),created_at:time}];
    window.fetch=async(input,options)=>{
      const url=new URL(typeof input==='string'?input:input.url,location.href);
      if(!url.pathname.startsWith('/api/admin'))return originalFetch(input,options);
      const route=url.pathname.slice('/api/admin'.length);let data={items:[]};
      if(route==='/session')data={username:'admin',csrfToken:'preview-only'};
      if(route==='/matches')data={items:matches};
      if(route==='/match')data={match:matches.find(m=>m.id===url.searchParams.get('id')),players:[{username:'ducbuitrung',score:0,placement:0,outcome:'CANCELLED'}]};
      if(route==='/logs')data={items:logs};
      if(route==='/users'){window.previewUsersReads=(window.previewUsersReads||0)+1;data={items:[{id:2,username:'ducbuitrung',email:'player@example.com',role:'user',blocked_until:0,online:window.previewUsersReads===1}]};}
      return new Response(JSON.stringify(data),{status:200,headers:{'Content-Type':'application/json'}});
    };
  })();`});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:base+'/admin#matches'});
  await until('document.querySelectorAll("#matches-table tbody tr").length===2');
  await evaluate('document.querySelector("#matches-table tbody button").click()');
  await until('document.getElementById("detail-dialog").open');
  assert.equal(await evaluate('document.querySelectorAll("#detail-body pre").length'),0);
  await screenshot('wheel-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'mobile page fits');
  assert.equal(await evaluate('document.getElementById("detail-dialog").scrollWidth<=document.getElementById("detail-dialog").clientWidth'),true,'mobile detail fits');
  await screenshot('wheel-mobile');
  await evaluate('document.getElementById("detail-dialog").close();document.querySelectorAll("#matches-table tbody button")[1].click()');
  await until('!!document.querySelector(".detail-question")');
  assert.equal(await evaluate('document.querySelectorAll(".correct-option").length'),2);
  await screenshot('arena-mobile');
  await evaluate('document.getElementById("detail-dialog").close();document.querySelector(".sidebar [data-view=logs]").click()');
  await until('document.querySelectorAll("#logs-table tbody tr").length===3');
  assert.equal(await evaluate('document.getElementById("logs-table").innerText.includes("ADMIN_LOGIN")'),false);
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});
  await screenshot('logs-desktop');
  await evaluate('document.querySelector("#logs-table tbody button").click()');
  await until('document.getElementById("detail-dialog").open');
  await screenshot('login-detail-desktop');
  await evaluate('document.getElementById("detail-dialog").close();document.querySelectorAll("#logs-table tbody button")[2].click()');
  await until('document.getElementById("detail-body").innerText.includes("Bạc 2")');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  assert.equal(await evaluate('document.getElementById("detail-dialog").scrollWidth<=document.getElementById("detail-dialog").clientWidth'),true,'nested audit fits mobile');
  await screenshot('rank-detail-mobile');
  await evaluate('document.getElementById("detail-dialog").close();document.querySelector(".sidebar [data-view=users]").click()');
  await until('document.querySelector(".presence-badge")?.innerText.includes("Online")');
  assert.equal(await evaluate('document.getElementById("users-table").innerText.includes("Được phép đăng nhập")'),true);
  await until('document.querySelector(".presence-badge")?.innerText.includes("Offline")');
  assert.equal(await evaluate('window.previewUsersReads>=2'),true,'presence updates automatically without reloading');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});
  await screenshot('users-presence-desktop');
  assert.deepEqual(errors,[],'no browser exceptions');
  console.log('PASS: desktop/mobile match cards, question accordions, audit table and nested audit dialog. APIs mocked; live database unchanged.');
  console.log('Screenshots: '+profile);
} finally { ws?.close();browser.kill(); }
