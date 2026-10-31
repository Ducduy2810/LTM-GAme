import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class Element{
  constructor(tag='div'){this.tag=tag;this.children=[];this.events={};this.style={};this.value='';this.disabled=false;const classes=new Set();this.classList={add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c),toggle(c,force){const enabled=force===undefined?!classes.has(c):force;if(enabled)classes.add(c);else classes.delete(c);}};}
  append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=[...nodes];}addEventListener(type,fn){this.events[type]=fn;}get firstChild(){return this.children[0];}reset(){}showModal(){this.open=true;}close(){this.open=false;}querySelector(){return new Element();}
}

const nodes=new Map(),get=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);},requests=[];
get('editor-difficulty').options=['EASY','MEDIUM','HARD'].map(value=>({value,disabled:false}));
const context={document:{getElementById:get,createElement:tag=>new Element(tag),querySelectorAll:()=>[],addEventListener(){}},Node:Element,Blob,window:{addEventListener(){}},setInterval(){},URLSearchParams,fetch:async(url,options)=>{
  requests.push({url,options});if(options?.method==='POST')return {ok:true,status:200,json:async()=>({id:10})};
  return {ok:true,status:200,json:async()=>({items:url==='/api/admin/topics'?[{scope:'QUESTION',code:'NETWORKING',name:'Lập trình mạng',active:1},{scope:'QUESTION',code:'SCIENCE',name:'Khoa học',active:1}]:[]})};
}};
let code=fs.readFileSync('public/admin.js','utf8').replace("api('/session').then(signedIn).catch(()=>loginScreen());","csrf='test';username='operator';view='questions';window.test={load,openEditor};");vm.runInNewContext(code,context);await context.window.test.load();
let checks=0;const test=(value,message)=>{assert.ok(value,message);checks++;};
const row={id:10,revision:1,text:'Câu hỏi rank',topic:'NETWORKING',difficulty:'HARD',options:['A','B','C','D'],correct:1,explanation:'Lời giải',status:'DRAFT',usage:'RACE',rank_tier:'DIAMOND'};
context.window.test.openEditor(row);test(get('question-rank-tier').value==='DIAMOND','editor opens existing rank assignment');test(get('editor-difficulty').value==='HARD'&&get('editor-topic').disabled,'rank question preserves HARD and locks topic to networking');test(get('editor-difficulty').options.filter(o=>!o.disabled).map(o=>o.value).join(',')==='HARD','Diamond only offers HARD');
get('question-rank-tier').value='BRONZE';get('question-rank-tier').events.change();test(get('editor-difficulty').value==='EASY','Bronze automatically selects a valid difficulty');test(get('editor-difficulty').options.filter(o=>!o.disabled).map(o=>o.value).join(',')==='EASY','Bronze only offers EASY');
get('question-rank-tier').value='SILVER';get('question-rank-tier').events.change();test(get('editor-difficulty').options.filter(o=>!o.disabled).map(o=>o.value).join(',')==='EASY,MEDIUM','Silver offers EASY and MEDIUM');
get('question-rank-tier').value='GOLD';get('question-rank-tier').events.change();test(get('editor-difficulty').value==='MEDIUM','Gold moves invalid EASY choice to MEDIUM');test(get('editor-difficulty').options.filter(o=>!o.disabled).map(o=>o.value).join(',')==='MEDIUM,HARD','Gold offers MEDIUM and HARD');
get('question-usage').value='LEGACY';get('question-usage').events.change();test(get('question-rank-tier').value===''&&!get('editor-topic').disabled,'legacy usage clears rank and unlocks topic');test(get('editor-difficulty').options.every(o=>!o.disabled),'shared questions restore all difficulty choices');
get('editor-topic').value='SCIENCE';get('question-rank-tier').value='PLATINUM';get('question-rank-tier').events.change();test(get('question-usage').value==='RACE'&&get('editor-topic').value==='NETWORKING','selecting a rank sets compatible scope and topic');test(get('question-rank-note').textContent.includes('Bạch Kim'),'editor explains the selected bank');
get('question-rank-tier').value='DIAMOND';get('question-rank-tier').events.change();await get('content-form').events.submit({preventDefault(){},currentTarget:get('content-form')});
const sent=JSON.parse(requests.find(r=>r.url==='/api/admin/content/save').options.body);test(sent.rankTier==='DIAMOND'&&sent.usage==='RACE'&&sent.difficulty==='HARD','save includes rank tier with valid difficulty and scope');test(sent.topic==='NETWORKING','disabled topic field still submitted correctly');
console.log(`PASS: ${checks} ranked question editor UI checks (rank assignment, allowed difficulty, scope, topic and save).`);
