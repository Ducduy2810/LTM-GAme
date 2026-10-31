import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class Element{
  constructor(tag='div'){this.tag=tag;this.children=[];this.events={};this.style={};this.value='';this.disabled=false;const classes=new Set();this.classList={add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c),toggle(c,force){const enabled=force===undefined?!classes.has(c):force;if(enabled)classes.add(c);else classes.delete(c);}};}
  append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=[...nodes];}addEventListener(type,fn){this.events[type]=fn;}get firstChild(){return this.children[0];}querySelector(){return new Element();}
}
const nodes=new Map(),get=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);},requests=[];
const context={document:{getElementById:get,createElement:tag=>new Element(tag),querySelectorAll:selector=>selector==='.question-filter'?[get('content-mode'),get('content-rank-tier'),get('content-difficulty')]:[],addEventListener(){}},Node:Element,Blob,window:{addEventListener(){}},setInterval(){},URLSearchParams,fetch:async url=>{
  requests.push(url);const params=new URL(url,'http://localhost').searchParams;
  const items=url.startsWith('/api/admin/content')&&params.get('kind')==='QUESTION'&&!(params.get('mode')==='DAILY'&&params.get('difficulty')==='EASY')?[{id:1,text:'Câu hỏi mẫu',topic:'NETWORKING',difficulty:'HARD',usage:'BOTH',status:'PUBLISHED',revision:1}]:[];
  return {ok:true,status:200,json:async()=>({items})};
}};
let code=fs.readFileSync('public/admin.js','utf8').replace("api('/session').then(signedIn).catch(()=>loginScreen());","csrf='test';username='operator';window.test={show,load};");vm.runInNewContext(code,context);
let checks=0;const test=(value,message)=>{assert.ok(value,message);checks++;},last=()=>new URL(requests.filter(url=>url.startsWith('/api/admin/content?')).at(-1),'http://localhost').searchParams;
context.window.test.show('questions',false);await context.window.test.load();test(!get('content-mode').disabled&&!get('content-mode').classList.contains('hidden'),'mode filter visible for question bank');test(!get('content-difficulty').disabled,'difficulty filter enabled');test(last().get('kind')==='QUESTION'&&last().get('mode')==='','initial bank has no mode restriction');
get('content-query').value='mạng';get('content-mode').value='RANKED';get('content-difficulty').value='HARD';get('content-status').value='PUBLISHED';await get('content-mode').events.change();
test(last().get('mode')==='RANKED'&&last().get('difficulty')==='HARD','mode change immediately sends both filters');test(last().get('status')==='PUBLISHED'&&last().get('q')==='mạng','filters preserve status and search text');test(get('content-result-count').textContent.includes('1 câu hỏi'),'matching result count shown');
get('content-rank-tier').value='DIAMOND';await get('content-rank-tier').events.change();test(last().get('rankTier')==='DIAMOND'&&last().get('mode')==='RANKED','major rank tier combines with game mode and difficulty');
get('content-difficulty').value='EASY';await get('content-difficulty').events.change();test(last().get('difficulty')==='EASY','difficulty change immediately reloads');
get('content-mode').value='DAILY';await get('content-mode').events.change();test(get('content-result-count').textContent.includes('0 câu hỏi'),'daily plus easy gives clear empty result count');
get('content-status').value='DRAFT';await get('content-status').events.change();test(last().get('mode')==='DAILY'&&last().get('difficulty')==='EASY'&&last().get('status')==='DRAFT','status change combines with current filters');
let prevented=false;get('content-query').value='tìm mới';get('content-search').events.submit({preventDefault(){prevented=true;}});await context.window.test.load();test(prevented&&last().get('q')==='tìm mới','search submit retains filter selection');
get('clear-content-filters').events.click();await context.window.test.load();test(['content-query','content-mode','content-rank-tier','content-difficulty','content-status'].every(id=>get(id).value===''),'clear resets all filters');test(last().get('mode')===''&&last().get('difficulty')==='','clear reloads unrestricted question bank');
get('content-mode').value='BOT';get('content-difficulty').value='MEDIUM';context.window.test.show('wheel',false);await context.window.test.load();
test(get('content-mode').disabled&&get('content-mode').classList.contains('hidden'),'mode filter hidden and disabled in wheel bank');test(get('content-difficulty').disabled&&get('question-filter-note').classList.contains('hidden'),'difficulty and question hint hidden in wheel bank');test(last().get('kind')==='WHEEL'&&!last().has('mode')&&!last().has('difficulty')&&!last().has('rankTier'),'wheel requests never include question filters');test(get('content-result-count').textContent.includes('ô chữ'),'wheel result count uses correct content type');
context.window.test.show('questions',false);await context.window.test.load();test(last().get('mode')==='BOT'&&last().get('difficulty')==='MEDIUM','returning to questions restores selected filters');test(!get('content-mode').disabled&&!get('question-filter-note').classList.contains('hidden'),'question controls restored');
const html=fs.readFileSync('public/admin.html','utf8');for(const mode of ['ARENA','SURVIVAL','PRACTICE','RANKED','CASUAL','BOT','DAILY'])test(html.includes('value="'+mode+'"'),'mode option provided: '+mode);
console.log(`PASS: ${checks} question filter UI checks (instant filtering, combined search, reset, counts and wheel isolation).`);
