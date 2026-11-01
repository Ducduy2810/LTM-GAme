(() => {
  'use strict';
  const meters=new WeakMap(),names=['Đồng','Bạc','Vàng','Bạch Kim','Kim Cương'];
  const node=(tag,cls,text)=>{const n=document.createElement(tag);n.className=cls;if(text!==undefined)n.textContent=text;return n;};
  const total=p=>Number(p.step)*100+Number(p.rp);
  const position=value=>{const step=Math.min(15,Math.floor(Math.max(0,value)/100));return {step,rp:Math.max(0,Math.round(value-step*100)),name:step===15?'Cao Thủ':names[Math.floor(step/3)]+' '+(3-step%3)};};
  const number=value=>Math.round(value).toLocaleString('vi-VN');
  function render(container,after,options={}){
    if(!container||!after)return;
    let state=meters.get(container);
    if(!state){
      const root=node('div','rank-meter'),header=node('div','rank-meter-heading'),points=node('strong','rank-meter-points'),change=node('span','rank-meter-change'),track=node('div','rank-meter-track'),fill=node('i','rank-meter-fill'),caption=node('p','rank-meter-caption');
      change.setAttribute('role','status');change.setAttribute('aria-live','polite');track.setAttribute('role','progressbar');track.setAttribute('aria-label','Điểm xếp hạng');track.setAttribute('aria-valuemin','0');header.append(points,change);track.append(fill);root.append(header,track,caption);container.replaceChildren(root);state={root,points,change,track,fill,caption,target:null,frame:0,display:after,key:null};meters.set(container,state);
    }
    const destination=total(after),key=options.key||String(destination);
    if(state.target===destination&&state.key===key&&state.title===options.title)return;
    if(state.frame)cancelAnimationFrame(state.frame);
    const unchanged=state.target===destination&&state.key===key;
    const before=unchanged?after:options.before||state.display||after,start=total(before),delta=options.delta??(destination-start);
    state.target=destination;state.key=key;state.title=options.title;
    const direction=delta>0?'gain':delta<0?'loss':'steady';state.root.dataset.direction=direction;
    state.change.textContent=delta===0?(options.before?'RP không thay đổi':''):(delta>0?'+':'−')+number(Math.abs(delta))+' RP';
    function paint(value,finished){
      const p=finished?after:position(value),master=Number(p.step)>=15,rp=Number(p.rp),name=master?(options.title||'Cao Thủ'):(p.name||position(total(p)).name);
      state.display=p;state.points.textContent=number(rp)+(master?' RP':' / 100 RP');
      state.fill.style.width=(master?rp%100:Math.min(100,rp))+'%';
      state.root.dataset.tier=master?'master':'standard';
      state.track.setAttribute('aria-valuenow',String(rp));state.track.setAttribute('aria-valuetext',name+', '+number(rp)+' RP');
      if(master)state.track.removeAttribute('aria-valuemax');else state.track.setAttribute('aria-valuemax','100');
      state.caption.textContent=master?name+' · Không giới hạn RP · Mốc tiếp theo '+number((Math.floor(rp/100)+1)*100)+' RP':name+' · '+number(100-rp)+' RP đến cấp tiếp theo';
    }
    const reduced=window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if(start===destination||reduced||typeof requestAnimationFrame!=='function'){paint(destination,true);return;}
    paint(start,false);let began;
    const tick=time=>{if(began===undefined)began=time;const t=Math.min(1,(time-began)/1100),eased=1-Math.pow(1-t,3);paint(start+(destination-start)*eased,t===1);if(t<1)state.frame=requestAnimationFrame(tick);else state.frame=0;};
    state.frame=requestAnimationFrame(tick);
  }
  window.RankProgress={render};
})();
