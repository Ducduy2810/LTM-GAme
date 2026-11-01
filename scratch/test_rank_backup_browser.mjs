import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import {auditVisibleText} from './contrast_audit.mjs';
const profile=path.resolve('scratch',`race-browser-rank-backup-${Date.now()}`);fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));let ws;
function fixtures(){
  const originalFetch=window.fetch.bind(window);
  const initial={step:0,rp:70,total:70,tier:'BRONZE',name:'Đồng 3'};window.previewRank=initial;window.previewTitle='';
  const settings={enabled:true,rankedEnabled:true,dailyEnabled:true,questionCount:10,timeLimit:15,queueSeconds:60,targetPlayers:4,dailyTimeLimit:7,dailyCorrect:8,shieldReward:1,doubleReward:1,firstRP:25,secondRP:10,duelSecondRP:0,thirdRP:-10,fourthRP:-15,rankBuffer:20,demotionRP:70};
  window.previewEmit=(before,after,delta,id,title='')=>{
    window.previewRank=after;window.previewTitle=title;
    window.previewSocket.onmessage({data:JSON.stringify({type:'race_state',phase:'COMPLETED',mode:'RANKED',matchId:id,roomCode:'DEMO',roomName:'Trận đấu xếp hạng',me:'preview',host:'',round:10,questionCount:10,serverTime:Date.now(),players:[{username:'preview',bot:false,forfeited:false,placement:1,score:25,correct:9,online:true,result:{before,after,rpDelta:delta,title,promoted:after.step>before.step,demoted:after.step<before.step,card:''}}]})});
  };
  class FakeWebSocket{static OPEN=1;readyState=1;constructor(){window.previewSocket=this;setTimeout(()=>this.onopen?.(),0);}send(){}close(){this.readyState=3;}}
  window.WebSocket=FakeWebSocket;window.EventSource=class{addEventListener(){}close(){}};
  window.fetch=async(input,options)=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href);let data;
    if(url.pathname==='/auth/me')data={loggedIn:true,user:{username:'preview',csrfToken:'preview'},maintenance:{enabled:false}};
    else if(url.pathname==='/api/race/overview')data={settings,account:{current:window.previewRank,highest:window.previewRank,title:window.previewTitle,highestTitle:window.previewTitle,cards:{shields:0,doubles:0},daily:{used:false}},leaderboard:[],rooms:[]};
    else if(url.pathname==='/api/race/history')data={items:[]};
    else if(url.pathname==='/api/admin/session')data={username:'operator',csrfToken:'preview'};
    else if(url.pathname==='/api/admin/backups')data={items:[{name:'admin-ten.db',created_at:1793490000000,size:240000},{name:'admin-fifteen.db',created_at:1793490020000,size:240000}]};
    else if(url.pathname==='/api/admin/backup-config'){
      const saved=url.searchParams.get('name')==='admin-ten.db'?10:15;
      data={name:url.searchParams.get('name'),createdAt:1793490000000,fingerprint:'a'.repeat(64),warnings:[],snapshot:{race_settings:{...settings,questionCount:saved}},values:{race_settings:{...settings,questionCount:saved}},current:{race_settings:{...settings,questionCount:15}}};
    }else if(url.pathname.startsWith('/api/admin/'))data={items:[]};
    else return originalFetch(input,options);
    if(options?.method==='POST')throw new Error('Browser preview must not mutate data');
    return new Response(JSON.stringify(data),{status:200,headers:{'Content-Type':'application/json'}});
  };
}
try{
  let port;for(let i=0;i<80;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(100);}}assert.ok(port);
  const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl.replace('localhost','127.0.0.1'));
  await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id,timer=setTimeout(()=>reject(new Error('CDP timeout '+method)),12000);pending.set(n,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
  async function until(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await sleep(80);}throw new Error('Timed out: '+expression);}
  const audits=[];
  async function screenshot(name){audits.push({screen:name,failures:await evaluate('('+auditVisibleText.toString()+')()')});const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync(path.join(profile,name+'.png'),Buffer.from(shot.data,'base64'));}
  await call('Runtime.enable');await call('Page.enable');await call('Page.addScriptToEvaluateOnNewDocument',{source:'('+fixtures.toString()+')();'});
  await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'no-preference'}]});
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:'http://127.0.0.1:3000/race.html?mode=ranked'});await until('!!window.previewSocket&&!!document.querySelector("#rank-summary-meter .rank-meter")');
  await evaluate(`previewSocket.onmessage({data:JSON.stringify({type:'race_state',phase:'IDLE',mode:'RANKED'})})`);await until('!document.getElementById("menu-panel").classList.contains("hidden")');assert.ok(await evaluate('parseFloat(getComputedStyle(document.getElementById("play-button")).fontSize)>=14'),'rank menu has readable text');await screenshot('rank-menu-desktop');
  await evaluate(`previewSocket.onmessage({data:JSON.stringify({type:'race_state',phase:'QUESTION',mode:'RANKED',matchId:'question',roomCode:'DEMO',roomName:'Tran dau xep hang',me:'preview',host:'',round:1,questionCount:10,seconds:60,deadline:Date.now()+60000,serverTime:Date.now(),myAnswer:-1,question:{token:'preview-question',difficulty:'EASY',text:'HTTPS bao ve du lieu nhu the nao?',options:['Ma hoa ket noi','Gui tep tin','Gui thu dien tu','Luu mat khau cong khai']},players:[{username:'preview',bot:false,score:0,correct:0,placement:1,online:true},{username:'opponent',bot:false,score:0,correct:0,placement:2,online:true}]})})`);
  await until('document.querySelectorAll(".answer-option").length===4');await sleep(1000);assert.ok(await evaluate('parseFloat(getComputedStyle(document.querySelector(".answer-option")).fontSize)>=16'),'rank answers have readable text');await screenshot('rank-question-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(300);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'rank question fits mobile');await screenshot('rank-question-mobile');await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});await evaluate('window.scrollTo(0,0)');
  await evaluate('previewEmit({step:0,rp:70,name:"Đồng 3"},{step:0,rp:95,name:"Đồng 3",tier:"BRONZE"},25,"gain")');
  await until('document.querySelector("#result-rank-meter .rank-meter-points")?.innerText.includes("95 / 100")');
  assert.equal(await evaluate('document.querySelector("#result-rank-meter .rank-meter").dataset.direction'), 'gain');await screenshot('rank-gain-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  await evaluate('previewEmit({step:0,rp:95,name:"Đồng 3"},{step:0,rp:80,name:"Đồng 3",tier:"BRONZE"},-15,"loss")');
  await until('document.querySelector("#result-rank-meter .rank-meter-points")?.innerText.includes("80 / 100")');
  assert.equal(await evaluate('document.querySelector("#result-rank-meter .rank-meter").dataset.direction'), 'loss');
  assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'rank page fits mobile');await screenshot('rank-loss-mobile');
  await evaluate('previewEmit({step:14,rp:99,name:"Kim Cương 1"},{step:15,rp:44,name:"Cao Thủ",tier:"MASTER"},25,"master","Huyền Thoại")');
  await until('document.querySelector("#result-rank-meter .rank-meter-points")?.innerText==="44 RP"&&document.querySelector("#rank-summary-meter .rank-meter-points")?.innerText==="44 RP"');assert.equal(await evaluate('document.getElementById("result-rank-meter").innerText.includes("Không giới hạn RP")'),true);assert.equal(await evaluate('document.querySelector("#rank-summary-meter .rank-meter-change").innerText'),'+25 RP');await screenshot('master-mobile');
  await call('Page.navigate',{url:'http://127.0.0.1:3000/admin#backups'});await until('document.querySelectorAll("#backups-table tbody tr").length===2');
  assert.equal(await evaluate('document.getElementById("backup-upload-form")'),null,'upload form removed');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});await screenshot('backups-desktop');
  await evaluate('document.querySelector("#backups-table button").click()');await until('document.getElementById("backup-config-dialog").open');
  const values=await evaluate('[...document.querySelectorAll("#backup-config-preview tbody tr")].find(r=>r.children[0].innerText==="Số câu mỗi trận")?.innerText');assert.ok(values.includes('10')&&values.includes('15'),'frozen ten shown separately from current fifteen');await screenshot('backup-frozen-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'backup page fits mobile');await screenshot('backup-frozen-mobile');
  fs.writeFileSync(path.join(profile,'contrast-audit.json'),JSON.stringify(audits,null,2));console.log('Contrast audit: '+audits.map(a=>a.screen+': '+a.failures.length).join(', '));
  assert.deepEqual(audits.filter(a=>a.failures.length),[],'sampled rank/admin text meets the contrast target');
  assert.deepEqual(errors,[],'no browser exceptions');console.log('PASS: readable rank menu/questions, RP gain/loss, Master promotion, mobile layout, removed upload UI and frozen snapshot comparison; APIs mocked.');console.log('Screenshots: '+profile);
}finally{ws?.close();browser.kill();}
