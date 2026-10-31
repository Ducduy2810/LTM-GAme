import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
let checks=0;
class Element {
  constructor(tag='div'){this.tag=tag;this.children=[];this.events={};this.style={};this.classList={add(){},remove(){},toggle(){}};this.files=[];this.value='';}
  append(...nodes){for(const node of nodes){node.parent=this;this.children.push(node);}}
  prepend(node){node.parent=this;this.children.unshift(node);}
  replaceChildren(...nodes){this.children=[];this.append(...nodes);}
  remove(){if(this.parent)this.parent.children=this.parent.children.filter(n=>n!==this);}
  setAttribute(){} addEventListener(event,fn){this.events[event]=fn;}
  querySelector(selector){return all(this).find(n=>selector.startsWith('.')?n.className===selector.slice(1):selector.startsWith('button')?n.tag==='button':n.tag===selector)||new Element('button');}
  showModal(){this.open=true;} close(){this.open=false;}
}
function all(root){return root.children.flatMap(n=>[n,...all(n)]);}
function documentStub(){
  const body=new Element('body'),nodes=new Map();
  return {body,createElement:tag=>new Element(tag),getElementById(id){let hit=all(body).find(n=>n.id===id);if(hit)return hit;for(const root of nodes.values()){hit=all(root).find(n=>n.id===id);if(hit)return hit;}if(nodes.has(id))return nodes.get(id);if(['maintenance-banner','account-notice-dialog','room-kick-dialog','match-cancel-dialog'].includes(id))return null;const node=new Element();node.id=id;nodes.set(id,node);return node;},querySelectorAll:selector=>selector==='dialog[open]'?all(body).filter(n=>n.tag==='dialog'&&n.open):[],querySelector(){return null;},addEventListener(){}};
}

const document=documentStub(),get=id=>document.getElementById(id),requests=[];
const race={enabled:true,rankedEnabled:true,dailyEnabled:true,questionCount:10,timeLimit:19,queueSeconds:60,targetPlayers:4,dailyTimeLimit:7,firstRP:25,secondRP:10,duelSecondRP:0,thirdRP:-10,fourthRP:-15,rankBuffer:20,demotionRP:70,dailyCorrect:6,shieldReward:2,doubleReward:1};
const runtime={maintenance:false,maintenanceUntil:0,message:'Config <img src=x>',enabled:{ARENA:true,WHEEL:false,SURVIVAL:true},defaults:{ARENA:{questionCount:10,timeLimit:5,mainPlayerTimeLimit:23,botCount:0},WHEEL:{timeLimit:10,botCount:0},SURVIVAL:{startingHp:100,totalRounds:25}}};
const preview={name:'upload-test.db',fingerprint:'a'.repeat(64),warnings:['Bảo trì đã hết hạn sẽ được tắt.'],values:{runtime,race_settings:race,announcement:{enabled:true,text:'Notice <img src=x>',expiresAt:0}},current:{runtime,race_settings:{...race,timeLimit:15},announcement:{enabled:false,text:'',expiresAt:0}}};
let failPreview=false,failApply=false;
const context={document,Node:Element,Blob,window:{addEventListener(){}},setInterval(){},URLSearchParams,fetch:async(url,options)=>{
  requests.push({url,options});
  if(url.startsWith('/api/admin/backup-config?'))return {ok:!failPreview,status:failPreview?400:200,json:async()=>failPreview?{error:'File không có cấu hình'}:preview};
  if(url==='/api/admin/backup-config/apply')return {ok:!failApply,status:failApply?400:200,json:async()=>failApply?{error:'File đã thay đổi. Hãy xem trước lại.'}:{success:true,safetyBackup:'admin-safety.db'}};
  return {ok:true,status:200,json:async()=>({items:[{name:'upload-test.db',size:1024,created_at:Date.now()}]})};
}};
let code=fs.readFileSync('public/admin.js','utf8').replace("api('/session').then(signedIn).catch(()=>loginScreen());","csrf='config-csrf';username='operator';view='backups';window.testRenderBackups=renderBackups;");vm.runInNewContext(code,context);
context.window.testRenderBackups([{name:'upload-test.db',size:1024,created_at:Date.now()}]);
let open=all(get('backups-table')).find(node=>node.tag==='button'&&node.textContent==='Áp dụng cấu hình');assert.ok(open);checks++;
await open.events.click();assert.equal(get('backup-config-dialog').open,true);checks++;assert.equal(get('backup-config-name').textContent,'upload-test.db');checks++;
assert.equal(requests.filter(r=>r.options?.method==='POST').length,0);checks++;
const content=all(get('backup-config-preview'));assert.equal(content.filter(n=>n.tag==='h3').length,3);checks++;
assert.ok(content.some(n=>n.textContent==='Đang dùng')&&content.some(n=>n.textContent==='Trong file'));checks++;
assert.ok(content.some(n=>n.textContent==='15')&&content.some(n=>n.textContent==='19'));checks++;
assert.ok(content.some(n=>n.textContent==='Config <img src=x>'));checks++;assert.equal(content.some(n=>n.tag==='img'),false);checks++;
assert.equal(get('backup-config-warnings').children[0].textContent,preview.warnings[0]);checks++;
await get('backup-config-apply').events.click();const sent=requests.find(r=>r.url==='/api/admin/backup-config/apply');assert.deepEqual(JSON.parse(sent.options.body),{name:preview.name,fingerprint:preview.fingerprint});checks++;
assert.equal(sent.options.headers['X-CSRF-Token'],'config-csrf');checks++;assert.equal(get('backup-config-dialog').open,false);checks++;
assert.ok(get('notice').textContent.includes('admin-safety.db'));checks++;assert.ok(requests.some(r=>r.url==='/api/admin/backups'));checks++;
open=all(get('backups-table')).find(node=>node.tag==='button'&&node.textContent==='Áp dụng cấu hình');await open.events.click();failApply=true;await get('backup-config-apply').events.click();
assert.equal(get('backup-config-dialog').open,true);checks++;assert.ok(get('backup-config-error').textContent.includes('File đã thay đổi'));checks++;assert.equal(get('backup-config-apply').disabled,false);checks++;
get('backup-config-dialog').close();failPreview=true;await open.events.click();assert.equal(get('backup-config-dialog').open,false);checks++;assert.equal(get('notice').textContent,'File không có cấu hình');checks++;
console.log(`PASS: ${checks} backup configuration UI checks (preview comparison, no auto-apply, explicit apply, CSRF, safety backup, refresh and errors).`);
