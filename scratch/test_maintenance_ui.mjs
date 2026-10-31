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
function player(pathname='/'){
  const document=documentStub(),navigations=[],context={document,window:{addEventListener(){},checkAuth(){}},location:{pathname,assign:href=>navigations.push(href)},sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},fetch:()=>new Promise(()=>{})};
  vm.runInNewContext(fs.readFileSync('public/system_client.js','utf8'),context);return {document,client:context.window.SystemClient,navigations};
}
const value={enabled:true,until:Date.now()+3600000,message:'Server đang bảo trì đến 15:30 ngày 01/11/2026 (giờ Việt Nam). Nâng cấp <img src=x>'};
for(const path of ['/','/race.html']){
  const {document,client,navigations}=player(path);client.setAccount({username:'owner',csrfToken:'token'});client.setMaintenance(value);
  const dialog=document.getElementById('account-notice-dialog');assert.equal(dialog.open,true);checks++;assert.equal(dialog.querySelector('h2').textContent,'Server đang bảo trì');checks++;assert.equal(dialog.querySelector('.account-notice-reason').textContent,value.message);checks++;assert.equal(document.getElementById('maintenance-banner').textContent,value.message);checks++;
  await dialog.querySelector('button').events.click();assert.equal(dialog.open,false);checks++;if(path!=='/'){assert.equal(navigations[0],'/');checks++;}
  client.setMaintenance(value);assert.equal(dialog.open,false);checks++;
  client.setMaintenance({enabled:false,until:value.until,message:''});assert.equal(document.getElementById('maintenance-banner'),null);checks++;assert.equal(document.getElementById('auth-msg').textContent,'');checks++;
}
const admin=player('/admin');admin.client.setMaintenance(value);admin.client.setAccount(null,{kind:'MAINTENANCE',reason:value.message});assert.equal(admin.document.getElementById('account-notice-dialog'),null);checks++;assert.equal(admin.document.getElementById('maintenance-banner'),null);checks++;
const document=documentStub(),requests=[],future=Date.now()+3600000;
const settings={maintenance:true,maintenanceUntil:future,message:'Test',enabled:{ARENA:true,WHEEL:true,SURVIVAL:true},defaults:{ARENA:{questionCount:10,timeLimit:5,mainPlayerTimeLimit:15,botCount:0},WHEEL:{timeLimit:10,botCount:0},SURVIVAL:{startingHp:100,totalRounds:25}}};
const context={document,Node:Element,Blob,window:{addEventListener(){}},setInterval(){},URLSearchParams,fetch:async(url,options)=>{requests.push({url,options});return {ok:true,status:200,json:async()=>options?.method==='POST'?{success:true}:settings};}};
let code=fs.readFileSync('public/admin.js','utf8').replace("api('/session').then(signedIn).catch(()=>loginScreen());","csrf='test';view='settings';username='operator';window.renderSettings=renderSettings;");vm.runInNewContext(code,context);context.window.renderSettings(settings);
const get=id=>document.getElementById(id);
assert.equal(get('maintenance-time-mode').value,'until');checks++;assert.equal(get('maintenance-until').value,new Date(future+7*3600000).toISOString().slice(0,16));checks++;assert.equal(get('maintenance-hours').disabled,true);checks++;
get('maintenance-time-mode').value='hours';get('maintenance-time-mode').events.change();get('maintenance-hours').value='2.5';get('maintenance').checked=true;
await get('settings-form').events.submit({preventDefault(){},currentTarget:get('settings-form')});let sent=JSON.parse(requests.find(r=>r.options?.method==='POST').options.body);assert.equal(sent.maintenanceHours,2.5);checks++;assert.equal(sent.maintenanceUntil,undefined);checks++;
requests.length=0;get('maintenance-time-mode').value='until';get('maintenance-time-mode').events.change();const input=new Date(Date.now()+86400000+7*3600000).toISOString().slice(0,16);get('maintenance-until').value=input;
await get('settings-form').events.submit({preventDefault(){},currentTarget:get('settings-form')});sent=JSON.parse(requests.find(r=>r.options?.method==='POST').options.body);assert.equal(sent.maintenanceUntil,Date.parse(input+':00+07:00'));checks++;
requests.length=0;get('maintenance-until').value='2000-01-01T00:00';await get('settings-form').events.submit({preventDefault(){},currentTarget:get('settings-form')});assert.equal(requests.length,0);checks++;assert.ok(get('notice').textContent.includes('tương lai'));checks++;
get('maintenance').checked=false;get('maintenance').events.change();assert.equal(get('maintenance-until').disabled,true);checks++;assert.equal(get('maintenance-hours').required,false);checks++;
console.log(`PASS: ${checks} maintenance UI checks (hours, date, Vietnam time, expiry, logout notice, admin exemption and invalid date).`);
