(() => {
  'use strict';
  const brand='Vũ Môn Tranh Hùng',crest='/assets/brand/vu-mon-crest.svg';
  function mark(){const image=document.createElement('img');image.src=crest;image.alt='';image.width=48;image.height=51;image.className='vm-crest-image';return image;}
  function words(context=''){
    const copy=document.createElement('span');copy.className='vm-wordmark';
    const name=document.createElement('strong'),subtitle=document.createElement('b');name.textContent='VŨ MÔN';subtitle.textContent='TRANH HÙNG';copy.append(name,subtitle);
    if(context){const small=document.createElement('small');small.className='vm-brand-context';small.textContent=context;copy.append(small);}return copy;
  }
  document.querySelectorAll('.world-brand,.brand').forEach(container=>{
    const context=container.querySelector('small');
    if(context?.id){
      const label=context,copy=words();label.classList.add('vm-brand-context');copy.append(label);container.replaceChildren(mark(),copy);
    }else container.replaceChildren(mark(),words(document.body.classList.contains('vm-admin')?'TRUNG TÂM QUẢN TRỊ':''));
    container.classList.add('vm-brand');container.setAttribute('aria-label',brand+(container.matches('a')?' · Trang chủ':''));
  });
  const scene=document.querySelector('.scene-brand');
  if(scene){const subtitle=document.getElementById('scene-subtitle');scene.replaceChildren(mark(),words());if(subtitle)scene.querySelector('.vm-wordmark').append(subtitle);scene.classList.add('vm-brand');}
  if(document.body.classList.contains('vm-game')||document.body.classList.contains('vm-account')){
    const bar=document.createElement('nav');bar.className='vm-brand-bar';bar.setAttribute('aria-label','Điều hướng Vũ Môn Tranh Hùng');
    const home=document.createElement('a');home.href='/';home.className='vm-brand';home.setAttribute('aria-label',brand+' · Trang chủ');home.append(mark(),words());
    const context=document.createElement('span');context.className='vm-brand-bar-context';context.textContent=document.body.classList.contains('vm-game')?'ĐẤU TRƯỜNG TRI THỨC':'TÀI KHOẢN NGƯỜI CHƠI';
    const back=document.createElement('a');back.href='/';back.className='vm-back-home';back.textContent='← Trang chủ';bar.append(home,context,back);document.body.prepend(bar);
  }
  const login=document.querySelector('.auth-layout .container');
  if(login&&!login.querySelector('.vm-auth-brand')){const emblem=document.createElement('div');emblem.className='vm-auth-brand';emblem.append(mark(),words());login.prepend(emblem);}
  const survival=document.querySelector('.survival-logo-icon');if(survival)survival.replaceChildren(mark());
})();
