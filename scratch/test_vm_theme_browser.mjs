import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import {auditVisibleText} from './contrast_audit.mjs';
const profile=path.resolve('scratch',`portal-browser-vm-theme-${Date.now()}`);fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));let ws;
function fixtures(){
  const originalFetch=window.fetch.bind(window),rank={current_step:3,current_points:40,highest_step:3,highest_points:60,current_tier:'SILVER',highest_tier:'SILVER',currentName:'Bạc 3',highestName:'Bạc 3',title:'',highestTitle:''};
  const user={username:'owner',email:'owner@example.com',isAdmin:false,rank,csrfToken:'preview',googleLinked:false};
  const runtime={maintenance:false,maintenanceUntil:0,message:'Bao tri he thong',enabled:{ARENA:true,WHEEL:true,SURVIVAL:true},defaults:{ARENA:{questionCount:10,timeLimit:15,mainPlayerTimeLimit:15,botCount:0},WHEEL:{timeLimit:10,botCount:0},SURVIVAL:{startingHp:100,totalRounds:10}}};
  const raceSettings={enabled:true,rankedEnabled:true,dailyEnabled:true,questionCount:10,timeLimit:15,queueSeconds:60,targetPlayers:4,firstRP:25,secondRP:10,duelSecondRP:0,thirdRP:-10,fourthRP:-15,rankBuffer:20,demotionRP:70,dailyTimeLimit:7,dailyCorrect:8,shieldReward:1,doubleReward:1};
  window.previewPackets=[];window.previewEmit=data=>window.previewSocket.onmessage({data:JSON.stringify(data)});
  class FakeWebSocket{static OPEN=1;static CLOSED=3;readyState=1;constructor(){window.previewSocket=this;setTimeout(()=>this.onopen?.(),0);}send(data){previewPackets.push(JSON.parse(data));}close(){this.readyState=3;}}
  window.WebSocket=FakeWebSocket;window.EventSource=class{addEventListener(){}close(){}};
  window.fetch=async(input,options)=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href);let data;
    if(url.pathname==='/auth/me')data={loggedIn:!location.search.includes('preview=login'),user,maintenance:{enabled:false}};
    else if(url.pathname==='/auth/profile')data={...user,rank};
    else if(url.pathname==='/api/portal/overview')data={history:[],leaderboard:[],rooms:[],stats:{totalGames:12,totalWins:7}};
    else if(url.pathname==='/api/catalog')data={topics:[{scope:'QUESTION',code:'NETWORKING',name:'Lập trình mạng',active:1},{scope:'WHEEL',code:'IT',name:'Công nghệ thông tin',active:1}],settings:{defaults:{ARENA:{questionCount:10,timeLimit:15,mainPlayerTimeLimit:15,botCount:0},WHEEL:{timeLimit:10,botCount:0},SURVIVAL:{startingHp:100,totalRounds:10}}}};
    else if(url.pathname==='/api/games')data={items:[]};
    else if(url.pathname==='/api/leaderboard')data={leaderboard:[]};
    else if(url.pathname==='/api/practice/topics')data={items:[{code:'NETWORKING',name:'Lập trình mạng'}]};
    else if(url.pathname==='/api/practice/next')data={id:1,questionId:1,text:'Giao thức nào bảo mật kết nối web?',options:['HTTP','HTTPS','FTP','SMTP'],topic:'NETWORKING',difficulty:'EASY'};
    else if(url.pathname==='/api/race/overview')data={leaderboard:[{position:1,username:'owner',rankName:'Bạc 3',current_points:40,current_step:3,ranked_games:12,ranked_wins:7},{position:2,username:'guest',rankName:'Đồng 1',current_points:80,current_step:2,ranked_games:8,ranked_wins:4}]};
    else if(url.pathname==='/api/race/leaderboard')data={items:[{position:1,username:'owner',rankName:'Bạc 3',current_points:40,current_step:3,ranked_games:12,ranked_wins:7},{position:2,username:'guest',rankName:'Đồng 1',current_points:80,current_step:2,ranked_games:8,ranked_wins:4}]};
    else if(url.pathname==='/api/admin/session'){if(location.search.includes('preview=login'))return new Response(JSON.stringify({error:'Sign in required'}),{status:401,headers:{'Content-Type':'application/json'}});data={username:'operator',csrfToken:'preview'};}
    else if(url.pathname==='/api/admin/overview')data={counts:{players:2,activeMatches:1,matches:12,questions:50,phrases:15,blocked:0},connections:2,recentMatches:[],settings:runtime};
    else if(url.pathname==='/api/admin/settings')data=runtime;
    else if(url.pathname==='/api/admin/race/settings')data=raceSettings;
    else if(url.pathname==='/api/admin/race')data={settings:raceSettings,queue:[],rooms:[],players:[],history:[]};
    else if(url.pathname==='/api/admin/leaderboard')data={counts:{eligibleCount:0,blockedCount:0},total:0,offset:0,limit:50,items:[]};
    else if(url.pathname==='/api/admin/announcement')data={enabled:false,text:'',expiresAt:0};
    else if(url.pathname==='/api/admin/integrations')data={google:{clientIdConfigured:true,clientSecretConfigured:true,redirectUri:'https://example.com/auth/google/callback'},smtp:{senderConfigured:true,passwordConfigured:true,host:'smtp.example.com',port:587,security:'STARTTLS'}};
    else if(url.pathname==='/api/admin/topics')data={items:[{scope:'QUESTION',code:'NETWORKING',name:'Lập trình mạng',active:1}]};
    else if(url.pathname==='/api/admin/content')data={items:[{id:1,text:'Giao thức nào bảo mật kết nối web?',options_json:JSON.stringify(['HTTP','HTTPS','FTP','SMTP']),correct:1,topic:'NETWORKING',difficulty:'EASY',explanation:'HTTPS bảo vệ dữ liệu bằng TLS.',status:'PUBLISHED',revision:1,usage:'BOTH',rank_tier:''}]};
    else if(url.pathname==='/api/admin/users')data={items:[{id:2,username:'owner',email:'owner@example.com',role:'user',blocked_until:0,online:true},{id:3,username:'guest',email:'guest@example.com',role:'user',blocked_until:0,online:false}]};
    else if(url.pathname.startsWith('/api/'))data={items:[]};
    else return originalFetch(input,options);
    return new Response(JSON.stringify(data),{status:200,headers:{'Content-Type':'application/json'}});
  };
}
try{
  let port;for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(100);}}assert.ok(port);
  const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl.replace('localhost','127.0.0.1'));
  await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let id=0;const pending=new Map(),errors=[];
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);});
  function call(method,params={}){return new Promise((resolve,reject)=>{const n=++id,timer=setTimeout(()=>reject(new Error('CDP timeout '+method)),12000);pending.set(n,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id:n,method,params}));});}
  async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
  async function until(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await sleep(80);}throw new Error('Timed out '+expression+'; '+await evaluate('document.body.innerText.slice(0,500)'));}
  async function navigate(path){await evaluate('window.MatchLeaveGuard?.reset()');await call('Page.navigate',{url:'http://127.0.0.1:3000'+path});await until('document.readyState==="complete"&&document.body.classList.contains("vm-theme")&&!!document.querySelector(".vm-crest-image")');}
  const audits=[];
  async function screenshot(name){if(await evaluate('!document.body.classList.contains("vm-home")'))audits.push({screen:name,failures:await evaluate('('+auditVisibleText.toString()+')()')});const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync(path.join(profile,name+'.png'),Buffer.from(shot.data,'base64'));}
  async function size(width){await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:width<600});}
  async function assertOriginalPalette(selectors){
    const result=await evaluate(`(()=>{const sheet=document.querySelector('link[href="/vm_theme.css"]').sheet;const read=()=>${JSON.stringify(selectors)}.map(selector=>{const style=getComputedStyle(document.querySelector(selector));return [selector,...['backgroundColor','backgroundImage','color','borderColor','filter'].map(property=>style[property])];});const themed=read();try{sheet.disabled=true;return {themed,original:read()};}finally{sheet.disabled=false;}})()`);
    assert.deepEqual(result.themed,result.original,'home, classroom and leaderboard keep their original colors');
  }
  await call('Runtime.enable');await call('Page.enable');await call('Page.addScriptToEvaluateOnNewDocument',{source:'('+fixtures.toString()+')();'});await size(1440);
  await navigate('/admin?preview=login');await until('!document.getElementById("login-screen").classList.contains("hidden")');
  assert.equal(await evaluate('!!document.querySelector(".login-story,.setup-note")'),false,'admin sign-in has no side story or setup notice');
  assert.equal(await evaluate('(()=>{const r=document.querySelector(".login-card").getBoundingClientRect();return Math.abs(r.left+r.width/2-innerWidth/2)<2;})()'),true,'admin sign-in centered');await screenshot('admin-login-desktop');
  await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'admin sign-in fits mobile');await screenshot('admin-login-mobile');await size(1440);
  await navigate('/?preview=login');await assertOriginalPalette(['#auth-section .container','#login-username','#btn-login']);await screenshot('player-login');
  await navigate('/');await until('document.getElementById("display-name").innerText==="owner"');await assertOriginalPalette(['.world-background','.world-title','.island-sign','.player-card','.game-dock']);await screenshot('home-desktop');
  await evaluate('document.querySelector("[data-view=practice]").dispatchEvent(new MouseEvent("click",{bubbles:true}))');await until('document.getElementById("portal-window").open');await evaluate('document.getElementById("practice-next").click()');await until('document.querySelectorAll("#practice-options .answer-button").length===4');await assertOriginalPalette(['.scene-window','.portal-window-header','.study-settings','.study-question','#practice-next','#practice-options .answer-button']);await screenshot('study-desktop');
  await evaluate('document.querySelector(".window-close").click();document.querySelector("[data-view=leaderboard]").dispatchEvent(new MouseEvent("click",{bubbles:true}))');await until('document.getElementById("portal-window").open&&document.querySelectorAll(".podium-player").length===3');await assertOriginalPalette(['.scene-window','.glory-banner','.glory-podium','.glory-list-panel','.glory-footer']);await screenshot('leaderboard-desktop');
  await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'home fits mobile');await screenshot('leaderboard-mobile');await size(1440);
  const players=[{username:'owner',isHost:true,isReady:true,isAlive:true,hp:100,score:0},{username:'guest',isHost:false,isReady:true,isAlive:true,hp:100,score:0}];
  const question={text:'Giao thức nào bảo mật dữ liệu khi truy cập một trang web?',options:['HTTP truyền dữ liệu dạng văn bản','HTTPS sử dụng kết nối được mã hóa','FTP dùng để truyền tệp','SMTP dùng để gửi email'],timeLimit:60};
  for(const game of ['arena','survival','wheel']){
    await navigate('/'+game+'.html?mode=pvp');await until('!!window.previewSocket&&window.previewSocket.readyState===1');
    await evaluate(`previewEmit({type:'${game==='arena'?'room_list':game+'_room_list'}',rooms:[{roomCode:'DEMO',roomName:'Phong cho tranh tai',hostUsername:'owner',gameState:'WAITING',playerCount:2,maxPlayers:4,topic:'NETWORKING',topicName:'Lap trinh mang',difficultyName:'De',botCount:0,timeLimit:60,mainPlayerTimeLimit:60,startingHp:100,totalRounds:10}]})`);await sleep(400);await screenshot(game+'-lobby');
    await evaluate('document.getElementById("btn-open-create-modal").click()');await until('!!document.querySelector(".modal-overlay.active")');await sleep(300);await screenshot(game+'-create-form');await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,game+' creation form fits mobile');await screenshot(game+'-create-form-mobile');await size(1440);await evaluate('document.getElementById("btn-close-modal").click()');
    if(game==='arena'){
      await evaluate(`previewEmit({type:'room_info',roomCode:'DEMO',roomName:'Phòng đấu trí',topic:'NETWORKING',difficulty:'EASY',questionCount:10,timeLimit:60,mainPlayerTimeLimit:60,botCount:0,isHost:true,gameState:'WAITING'});previewEmit({type:'update_arena_players',roomCode:'DEMO',players:${JSON.stringify(players)},hostUsername:'owner',allReady:true,mainSelectionMode:'MANUAL',selectedMainPlayer:'owner'});`);
      await screenshot('arena-waiting');
      await evaluate(`previewEmit({type:'arena_game_started',roomCode:'DEMO',mainPlayer:'owner',totalOpponents:1});previewEmit({type:'arena_new_question',roomCode:'DEMO',questionId:1,totalQuestions:10,phase:'MAIN',mainPlayer:'owner',isMainPlayer:true,isAlive:true,canAnswer:true,...${JSON.stringify(question)}});`);
    }else if(game==='survival'){
      await evaluate(`previewEmit({type:'survival_room_info',roomCode:'DEMO',roomName:'Phòng sinh tồn',startingHp:100,totalRounds:5,isHost:true,gameState:'WAITING'});`);await sleep(350);await screenshot('survival-waiting');await evaluate(`previewEmit({type:'survival_game_started',roomCode:'DEMO',startingHp:100,totalRounds:5,players:${JSON.stringify(players)}});previewEmit({type:'survival_new_question',roomCode:'DEMO',round:1,phaseName:'Khởi động',penalty:5,playerStates:${JSON.stringify(players)},...${JSON.stringify(question)}});`);
    }else{
      await evaluate(`previewEmit({type:'wheel_room_info',roomCode:'DEMO',roomName:'Phòng ô chữ',topic:'IT',topicName:'Công nghệ thông tin',timeLimit:60,isHost:true,gameState:'WAITING'});`);await sleep(350);await screenshot('wheel-waiting');await evaluate(`previewEmit({type:'wheel_state',roomCode:'DEMO',gameState:'PLAYING',hostUsername:'owner',players:${JSON.stringify(players)},turnIndex:0,currentTurnUsername:'owner',currentAction:'SPIN',phrase:'LAP TRINH MANG',revealedLetters:['A','N'],hint:'Môn học kết nối kiến thức và công nghệ',topic:'IT',topicName:'Công nghệ thông tin',timeLimit:60,timeRemaining:60});`);
    }
    await until('document.getElementById("game-screen").classList.contains("active")');
    await sleep(450);
    if(game!=='wheel'){
      assert.equal(await evaluate('getComputedStyle(document.querySelector(".question-frame")).backgroundColor'),'rgb(255, 249, 233)',game+' uses parchment question form');
      await evaluate('document.querySelectorAll(".answer-btn")[1].classList.add("correct");document.querySelectorAll(".answer-btn")[0].classList.add("wrong")');
      await sleep(400);
      assert.notEqual(await evaluate('getComputedStyle(document.querySelector(".answer-btn.correct")).backgroundColor'),await evaluate('getComputedStyle(document.querySelector(".answer-btn.wrong")).backgroundColor'),'correct/wrong states stay distinct');
    }
    await screenshot(game+'-question-desktop');await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,game+' fits mobile');await screenshot(game+'-question-mobile');await size(1440);
    if(game==='wheel'){
      await evaluate(`previewEmit({type:'wheel_state',roomCode:'DEMO',gameState:'PLAYING',hostUsername:'owner',players:${JSON.stringify(players)},turnIndex:0,currentTurnUsername:'owner',currentAction:'GUESS',currentSpinValue:100,phrase:'LAP TRINH MANG',revealedLetters:['A','N'],hint:'Goi y',topic:'IT',topicName:'Cong nghe thong tin',timeLimit:60,timeRemaining:60})`);
      assert.equal(await evaluate('getComputedStyle(document.querySelector("#spin-result-banner strong")).color'),'rgb(25, 91, 128)','spin value stays readable on a light card');await screenshot('wheel-guess-desktop');
    }
    if(game==='survival'){
      await evaluate(`previewEmit({type:'survival_new_question',roomCode:'DEMO',round:2,phaseName:'Thu thach',penalty:5,playerStates:${JSON.stringify(players.map(p=>({...p,hp:20})))},...${JSON.stringify(question)}})`);
      await sleep(500);
      assert.equal(await evaluate('getComputedStyle(document.getElementById("game-hp-fill")).backgroundColor'),'rgb(164, 75, 61)','low HP keeps its warning color');
      assert.equal(await evaluate('getComputedStyle(document.getElementById("game-my-hp")).color'),'rgb(164, 75, 61)','low HP value keeps its warning color');
    }
    await evaluate(`previewEmit({type:'${game}_end_game',roomCode:'DEMO',reason:'Hoan thanh tran dau',mainWon:true,mainPlayer:'owner',finalScore:100,totalEliminated:1,totalInitialOpponents:1,winner:'owner',finalStandings:${JSON.stringify(players)},fullPhrase:'LAP TRINH MANG',winners:['owner']})`);
    await until('document.getElementById("end-screen").classList.contains("active")');await sleep(450);await screenshot(game+'-result-desktop');
    if(game!=='survival')assert.equal(await evaluate('getComputedStyle(document.querySelector("#end-screen .vm-summary-box")).backgroundColor'),'rgb(234, 248, 255)','result summary matches the light sky theme');
  }
  await navigate('/admin#users');await until('document.querySelectorAll("#users-table tbody tr").length===2');await screenshot('admin-users-desktop');await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'admin fits mobile');await screenshot('admin-users-mobile');await size(1440);
  assert.ok(await evaluate('getComputedStyle(document.querySelector(".sidebar")).backgroundImage.includes("rgb(245, 252, 255)")'),'admin has a light sidebar');
  assert.ok(await evaluate('parseFloat(getComputedStyle(document.querySelector(".sidebar nav>button span")).fontSize)>=15'),'admin categories use readable text');
  assert.equal(await evaluate('(()=>{const notice=document.createElement("p");notice.className="notice";document.body.append(notice);const color=getComputedStyle(notice).backgroundColor;notice.remove();return color;})()'),'rgb(255, 243, 204)','notice stays light');
  await evaluate('document.querySelector(".sidebar [data-view=questions]").click()');await until('document.querySelectorAll("#content-table tbody tr").length===1');await evaluate('document.getElementById("new-content").click()');await until('document.getElementById("content-dialog").open');await screenshot('admin-question-form');
  await evaluate('document.getElementById("content-dialog").close()');
  for(const page of ['overview','topics','rooms','matches','logs','race','integrations','announcements','settings','account','leaderboard']){
    await evaluate(`document.querySelector('.sidebar [data-view=${page}]').click()`);await sleep(250);assert.equal(await evaluate('document.getElementById("notice").classList.contains("hidden")'),true,page+' has valid fixture data');await screenshot('admin-'+page);
  }
  for(const page of ['reset','verify-email']){await navigate('/'+page+'.html');await size(390);assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,page+' fits mobile');await screenshot(page+'-mobile');await size(1440);}
  fs.writeFileSync(path.join(profile,'contrast-audit.json'),JSON.stringify(audits,null,2));
  console.log('Contrast audit: '+audits.map(a=>a.screen+': '+a.failures.length).join(', '));
  assert.deepEqual(audits.filter(a=>a.failures.length),[],'sampled text on light surfaces meets the contrast target');
  assert.deepEqual(errors,[],'no browser exceptions');console.log('PASS: centered admin login, readable admin categories, original home/study/leaderboard/login palette preserved, light admin/game/account pages, answer and HP states, wheel spin/guess/results, desktop and mobile. APIs mocked; database unchanged.');console.log('Screenshots: '+profile);
}finally{ws?.close();browser.kill();}
