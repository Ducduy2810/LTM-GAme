import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class Element{
  constructor(){this.children=[];this.events={};this.style={};this.checked=false;const classes=new Set();this.classList={add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c),toggle(c,force){const enabled=force===undefined?!classes.has(c):force;if(enabled)classes.add(c);else classes.delete(c);}};}
  append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=[...nodes];}addEventListener(type,fn){this.events[type]=fn;}scrollIntoView(){}querySelector(){return new Element();}
}
const nodes=new Map(),get=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
const requests=[],pending=[];
const context={document:{getElementById:get,createElement:()=>new Element(),querySelectorAll:()=>[],body:{dataset:{}}},window:{addEventListener(){}},location:{search:'?mode=ranked'},URLSearchParams,setInterval(){},requestAnimationFrame(){},fetch:url=>{requests.push(url);return new Promise(resolve=>pending.push({url,resolve}));}};
let code=fs.readFileSync('public/race.js','utf8');code=code.slice(0,code.lastIndexOf('  (async()=>'))+'window.test={render,loadReview,choose};\n})();';vm.runInNewContext(code,context);
let checks=0;const hidden=id=>get(id).classList.contains('hidden'),test=(value,message)=>{assert.ok(value,message);checks++;};
const player={username:'me',score:0,correct:0,placement:1,result:{rpDelta:0}};
const state=(phase,id='new')=>({phase,mode:'RANKED',matchId:id,roomName:'Rank',roomCode:'ROOM',players:[player],me:'me',round:1,questionCount:10,seconds:15,deadline:Date.now()+15000,question:{token:id+'-question',text:'Current question',difficulty:'EASY',options:['A','B','C','D']},myAnswer:-1,solution:phase==='RESULT'?{correctIndex:0,correct:false,explanation:'Current solution'}:undefined});
const review=id=>({match:{mode:'RANKED'},questions:[{round_no:1,correct:false,answer:1,question:{text:id+' review',correct:0,options:['A','B','C','D'],explanation:'Review explanation'}}]});
function resolveReview(id){const index=pending.findIndex(p=>p.url==='/api/race/review?id='+id);assert.ok(index>=0);pending.splice(index,1)[0].resolve({ok:true,status:200,json:async()=>review(id)});}
context.window.test.render(state('COMPLETED','old'));get('review-panel').classList.add('hidden');
let task=context.window.test.loadReview('old');resolveReview('old');await task;
test(!hidden('review-panel')&&get('review-list').children.length===1,'completed match review visible');
context.window.test.render(state('QUEUED'));
for(const phase of ['QUEUED','QUESTION','RESULT']){
  context.window.test.render(state(phase));test(hidden('post-match-panels'),'history and leaderboard wrapper hidden during '+phase);test(hidden('rank-board-panel'),'Top 100 hidden during '+phase);test(hidden('review-panel')&&get('review-list').children.length===0,'old review cleared during '+phase);
}
let count=requests.length;await context.window.test.loadReview('old');test(requests.length===count,'cannot load an old review during live rank');
context.window.test.render(state('COMPLETED'));
test(!hidden('post-match-panels')&&!hidden('rank-board-panel'),'history and Top 100 return after match');test(hidden('review-panel'),'previous review stays closed after new match ends');
task=context.window.test.loadReview('new');resolveReview('new');await task;test(!hidden('review-panel'),'current match can be reviewed after completion');test(get('review-list').children[0].children[0].textContent.includes('new review'),'review contains latest match');
context.window.test.render({phase:'IDLE'});task=context.window.test.loadReview('slow-old');context.window.test.render(state('QUESTION','next'));context.window.test.render(state('COMPLETED','next'));resolveReview('slow-old');await task;
test(hidden('review-panel')&&get('review-list').children.length===0,'late old response cannot reopen review even after new match finishes');
context.window.test.render({phase:'IDLE'});task=context.window.test.loadReview('slow-queue');context.window.test.render(state('QUEUED','another'));resolveReview('slow-queue');await task;test(hidden('review-panel'),'late review response remains hidden while matchmaking');
context.window.test.render({phase:'IDLE'});test(!hidden('post-match-panels')&&!hidden('rank-board-panel'),'canceling queue restores lobby history and Top 100');
context.window.test.choose('CASUAL');context.window.test.render({...state('QUESTION'),mode:'CASUAL'});test(!hidden('post-match-panels'),'non-ranked history layout preserved');test(hidden('rank-board-panel'),'Top 100 stays rank-only');
const html=fs.readFileSync('public/race.html','utf8');test(html.includes('id="post-match-panels" class="bottom-grid"'),'post-match cards share visibility wrapper');test(fs.readFileSync('public/race.css','utf8').includes('.hidden{display:none!important}'),'hidden state takes precedence over grid layout');
console.log(`PASS: ${checks} ranked post-match UI checks (new queue, live rounds, completion, canceled queue, late review responses and casual mode).`);
