(() => {
  'use strict';
  const canvas=document.getElementById('world-canvas'),viewport=document.getElementById('world-viewport');
  if(!canvas||!viewport)return;
  const ns='http://www.w3.org/2000/svg',svg=canvas.querySelector('.world-hotspots');
  const drift=document.createElement('div');drift.className='world-drift';
  const parallax=document.createElement('div');parallax.className='world-parallax';
  while(canvas.firstChild)parallax.append(canvas.firstChild);drift.append(parallax);canvas.append(drift);
  const defs=document.createElementNS(ns,'defs'),effects=document.createElementNS(ns,'g');effects.classList.add('world-island-effects');effects.setAttribute('aria-hidden','true');effects.setAttribute('pointer-events','none');svg.prepend(defs);svg.append(effects);
  const feather=document.createElementNS(ns,'filter'),blur=document.createElementNS(ns,'feGaussianBlur');feather.id='island-edge-feather';blur.setAttribute('stdDeviation','5');feather.append(blur);defs.append(feather);
  const centers={daily:[995,240],rank:[1090,565],leaderboard:[665,640],pvp:[748,438],practice:[388,240],bot:[360,530]};
  // Crop the visible island cores separately from the larger, accessible click targets.
  const silhouettes={
    practice:'228,140 335,122 355,81 397,83 413,124 463,123 511,165 546,173 545,241 563,260 551,295 505,320 451,340 368,341 296,331 239,317 218,288 219,222',
    bot:'188,476 228,475 248,445 296,430 343,450 349,405 375,395 394,370 427,364 464,377 482,405 487,446 502,470 511,503 507,541 479,562 430,590 360,616 310,623 257,609 231,582 212,545',
    pvp:'609,333 639,311 689,309 716,327 726,268 783,255 823,280 842,337 882,350 912,408 899,447 865,478 865,539 826,616 796,681 735,670 687,616 666,545 641,484 616,449 595,419',
    leaderboard:'610,512 640,517 681,541 711,574 711,596 746,619 759,651 746,688 719,744 678,764 623,747 597,718 561,680 551,644 567,614 609,597 624,591 608,562',
    rank:'1052,381 1093,384 1126,421 1119,464 1123,496 1172,510 1219,561 1212,608 1178,647 1163,703 1122,760 1039,750 997,704 961,653 944,601 916,571 941,533 991,504 1041,490',
    daily:'955,26 1035,23 1053,63 1083,80 1086,125 1063,153 1096,174 1109,216 1136,240 1146,277 1110,307 1102,361 1065,410 1026,445 981,408 944,380 934,335 895,321 862,301 830,275 840,233 858,211 875,172 924,155 936,124 950,105'
  };
  for(const zone of svg.querySelectorAll('.map-zone')){
    const view=zone.dataset.view,mask=document.createElementNS(ns,'mask'),outline=document.createElementNS(ns,'polygon');
    mask.id='island-mask-'+view;mask.setAttribute('maskUnits','userSpaceOnUse');mask.setAttribute('x','0');mask.setAttribute('y','0');mask.setAttribute('width','1376');mask.setAttribute('height','768');outline.setAttribute('points',silhouettes[view]);outline.setAttribute('fill','white');outline.setAttribute('filter','url(#island-edge-feather)');mask.append(outline);defs.append(mask);
    const group=document.createElementNS(ns,'g'),crop=document.createElementNS(ns,'g'),image=document.createElementNS(ns,'image');
    group.classList.add('island-zoom');group.dataset.island=view;group.style.transformOrigin=centers[view].join('px ')+'px';crop.setAttribute('mask',`url(#${mask.id})`);
    image.setAttribute('href','/assets/world/sky-world.png');image.setAttribute('width','1376');image.setAttribute('height','768');crop.append(image);group.append(crop);effects.append(group);
    zone.style.transformOrigin=centers[view].join('px ')+'px';
  }
  const atmosphere=document.createElement('div');atmosphere.className='world-atmosphere';atmosphere.setAttribute('aria-hidden','true');
  for(let i=0;i<3;i++){const cloud=document.createElement('span');cloud.className='world-cloud cloud-'+i;atmosphere.append(cloud);}
  document.getElementById('world-map').append(atmosphere);
  const toggle=document.createElement('button');toggle.id='btn-world-motion';toggle.type='button';toggle.innerHTML='<span aria-hidden="true">〰</span><span class="motion-label">Chuyển động</span>';document.getElementById('game-dock').insertBefore(toggle,document.getElementById('btn-fullscreen'));
  const reduce=matchMedia('(prefers-reduced-motion: reduce)'),fine=matchMedia('(hover:hover) and (pointer:fine)');
  let paused=false;try{paused=localStorage.getItem('world-motion-paused')==='true';}catch{}
  let hovered='',focused='',frame=0,pointer=null,idle=true;
  const targets=[...canvas.querySelectorAll('.map-zone,.island-sign')];
  function active(view){
    for(const n of targets)n.classList.toggle('is-hovered',n.dataset.view===view);
    for(const n of effects.children)n.classList.toggle('is-hovered',n.dataset.island===view);
    if(view){const effect=effects.querySelector(`[data-island="${view}"]`);if(effect&&effect!==effects.lastChild)effects.append(effect);}
  }
  function resetParallax(){if(frame)cancelAnimationFrame(frame);frame=0;pointer=null;parallax.style.removeProperty('--world-x');parallax.style.removeProperty('--world-y');}
  function sync(){
    idle=!document.body.classList.contains('signed-in')||!!document.querySelector('dialog[open]')||document.hidden;
    document.body.classList.toggle('world-motion-idle',idle);document.body.classList.toggle('world-motion-paused',paused||reduce.matches);
    toggle.disabled=reduce.matches;toggle.setAttribute('aria-pressed',String(!paused&&!reduce.matches));toggle.setAttribute('aria-label',reduce.matches?'Chuyển động đã tắt theo cài đặt giảm chuyển động':paused?'Bật chuyển động bản đồ':'Dừng chuyển động bản đồ');toggle.title=toggle.getAttribute('aria-label');toggle.querySelector('.motion-label').textContent=paused||reduce.matches?'Bật chuyển động':'Dừng chuyển động';
    if(idle||paused||reduce.matches){resetParallax();if(idle){hovered=focused='';active('');}}
  }
  for(const node of targets){
    node.addEventListener('pointerenter',e=>{if(e.pointerType==='touch'||idle)return;hovered=node.dataset.view;active(hovered||focused);});
    node.addEventListener('pointerleave',()=>{hovered='';active(focused);});
    node.addEventListener('focus',()=>{if(idle)return;focused=node.dataset.view;active(hovered||focused);});
    node.addEventListener('blur',()=>{focused='';active(hovered);});
    node.addEventListener('pointerdown',e=>{if(e.pointerType==='touch'&&!idle)active(node.dataset.view);});
    node.addEventListener('pointerup',e=>{if(e.pointerType==='touch')active('');});node.addEventListener('pointercancel',()=>active(''));
  }
  viewport.addEventListener('pointermove',e=>{
    if(idle||paused||reduce.matches||!fine.matches||e.pointerType==='touch')return;
    pointer={x:e.clientX,y:e.clientY};if(frame)return;
    frame=requestAnimationFrame(()=>{frame=0;if(!pointer||idle||paused||reduce.matches)return;const r=viewport.getBoundingClientRect();parallax.style.setProperty('--world-x',((pointer.x-r.left)/r.width-.5)*-8+'px');parallax.style.setProperty('--world-y',((pointer.y-r.top)/r.height-.5)*-5+'px');});
  },{passive:true});viewport.addEventListener('pointerleave',resetParallax);
  toggle.addEventListener('click',()=>{paused=!paused;try{localStorage.setItem('world-motion-paused',String(paused));}catch{}sync();});
  new MutationObserver(sync).observe(document.body,{attributes:true,attributeFilter:['class']});
  new MutationObserver(sync).observe(document.body,{subtree:true,attributes:true,attributeFilter:['open']});
  document.addEventListener('visibilitychange',sync);reduce.addEventListener('change',sync);fine.addEventListener('change',resetParallax);sync();
})();
