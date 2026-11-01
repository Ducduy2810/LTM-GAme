(() => {
  'use strict';
  const root=document.getElementById('auth-section')||document.getElementById('login-screen');if(!root)return;
  root.classList.add('login-scene');
  const video=document.createElement('video');video.className='login-scene-video';video.muted=true;video.defaultMuted=true;video.autoplay=false;video.loop=true;video.playsInline=true;video.preload='metadata';video.poster='/assets/world/sky-world.png';video.setAttribute('aria-hidden','true');video.setAttribute('tabindex','-1');video.setAttribute('muted','');video.setAttribute('playsinline','');
  const source=document.createElement('source');source.src='/assets/login/login-background.mp4';source.type='video/mp4';video.append(source);root.prepend(video);
  const toggle=document.createElement('button');toggle.type='button';toggle.className='login-scene-toggle';toggle.hidden=true;root.append(toggle);
  const motion=matchMedia('(prefers-reduced-motion: reduce)');let userPaused=motion.matches,failed=false;
  function visible(){return !root.classList.contains('hidden')&&getComputedStyle(root).display!=='none'&&!document.hidden;}
  function label(){toggle.textContent=video.paused?'▶ Phát nền chuyển động':'Ⅱ Dừng nền chuyển động';toggle.setAttribute('aria-label',toggle.textContent);toggle.setAttribute('aria-pressed',String(!video.paused));}
  async function sync(){
    if(!visible()||userPaused||failed){video.pause();label();return;}
    try{await video.play();}catch{video.pause();}label();
  }
  toggle.addEventListener('click',()=>{userPaused=!video.paused;if(!userPaused&&motion.matches)video.style.visibility='visible';sync();});
  video.addEventListener('loadedmetadata',()=>{toggle.hidden=false;label();});video.addEventListener('playing',label);video.addEventListener('pause',label);
  video.addEventListener('error',()=>{failed=true;video.hidden=true;toggle.hidden=true;});
  source.addEventListener('error',()=>{failed=true;video.hidden=true;toggle.hidden=true;});
  new MutationObserver(sync).observe(root,{attributes:true,attributeFilter:['class','style']});
  document.addEventListener('visibilitychange',sync);motion.addEventListener('change',()=>{userPaused=motion.matches;video.style.visibility='';sync();});sync();
})();
