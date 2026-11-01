import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class Element{
  constructor(){this.children=[];this.style={};this.dataset={};this.attributes={};}
  append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}
  setAttribute(name,value){this.attributes[name]=value;}removeAttribute(name){delete this.attributes[name];}
}
let frames=[],next=0,reduced=false;
const context={document:{createElement:()=>new Element()},window:{matchMedia:()=>({matches:reduced})},requestAnimationFrame:fn=>{frames.push({id:++next,fn});return next;},cancelAnimationFrame:id=>{frames=frames.filter(f=>f.id!==id);}};
vm.runInNewContext(fs.readFileSync('public/rank_progress.js','utf8'),context);
const render=context.window.RankProgress.render,container=new Element();
const all=n=>[n,...n.children.flatMap(all)],find=(n,cls)=>all(n).find(v=>v.className===cls),text=n=>all(n).map(v=>v.textContent||'').join(' ');
function finish(){let time=0;while(frames.length){const batch=frames;frames=[];batch.forEach(frame=>frame.fn(time));time+=110;if(time>3000)throw new Error('Animation did not finish');}}
render(container,{step:3,rp:70});assert.ok(text(container).includes('70 / 100 RP'));assert.equal(frames.length,0);
render(container,{step:3,rp:95},{before:{step:3,rp:70},delta:25,key:'gain'});assert.ok(frames.length>0);finish();assert.equal(find(container,'rank-meter-fill').style.width,'95%');assert.ok(text(container).includes('+25 RP'));assert.equal(container.children[0].dataset.direction,'gain');
render(container,{step:3,rp:95},{before:{step:3,rp:70},delta:25,key:'gain'});assert.equal(frames.length,0,'same result never replays the animation');
render(container,{step:3,rp:80},{before:{step:3,rp:95},delta:-15,key:'loss'});finish();assert.equal(find(container,'rank-meter-fill').style.width,'80%');assert.ok(text(container).includes('−15 RP'));assert.equal(container.children[0].dataset.direction,'loss');
render(container,{step:15,rp:68,name:'Cao Thủ'},{before:{step:14,rp:98},delta:50,key:'promote',title:'Huyền Thoại'});finish();assert.ok(text(container).includes('Huyền Thoại'));assert.ok(text(container).includes('Không giới hạn RP'));assert.equal(find(container,'rank-meter-track').attributes['aria-valuemax'],undefined);
render(container,{step:15,rp:10_000_000_025},{before:{step:15,rp:10_000_000_000},delta:25,key:'unlimited'});finish();assert.ok(text(container).includes('10.000.000.025 RP'));assert.equal(find(container,'rank-meter-fill').style.width,'25%');
render(container,{step:15,rp:10_000_000_025},{title:'Cao Thủ'});assert.ok(text(container).includes('Cao Thủ'),'title updates even without changing points');
reduced=true;render(container,{step:15,rp:10_000_000_010},{before:{step:15,rp:10_000_000_025},delta:-15,key:'reduced'});assert.equal(frames.length,0);assert.equal(find(container,'rank-meter-fill').style.width,'10%');
console.log('PASS: animated RP gain/loss, promotion to Master, unbounded milestones, title refresh, duplicate result prevention and reduced motion.');
