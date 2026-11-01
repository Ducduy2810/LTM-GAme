(() => {
  const game=location.pathname.includes('wheel')?'WHEEL':location.pathname.includes('survival')?'SURVIVAL':'ARENA';
  let catalog;
  fetch('/api/catalog').then(r=>{if(!r.ok)throw new Error();return r.json();}).then(data=>{catalog=data;
    const select=document.getElementById('create-topic');if(!select)return;const selected=select.value;
    const specials=[...select.options].filter(o=>['ALL','MIX','RANDOM'].includes(o.value));select.replaceChildren(...specials);
    data.topics.filter(t=>t.scope===(game==='WHEEL'?'WHEEL':'QUESTION')).forEach(t=>{const o=document.createElement('option');o.value=t.code;o.textContent=t.name;select.append(o);});
    if([...select.options].some(o=>o.value===selected))select.value=selected;
  }).catch(()=>{});
  const fields={ARENA:{questionCount:'create-question-count',timeLimit:'create-opponent-time',mainPlayerTimeLimit:'create-main-time',botCount:'create-bot-count'},WHEEL:{timeLimit:'create-time-limit',botCount:'create-bot-count'},SURVIVAL:{startingHp:'create-starting-hp',totalRounds:'create-total-rounds'}};
  document.getElementById('btn-open-create-modal')?.addEventListener('click',()=>{
    if(!catalog)return;const mode=new URLSearchParams(location.search).get('mode');
    Object.entries(fields[game]).forEach(([key,id])=>{if(key==='botCount'&&['bot','pvp'].includes(mode))return;const input=document.getElementById(id),value=catalog.settings.defaults[game][key];if(!input)return;if(input.tagName==='SELECT'&&![...input.options].some(o=>o.value===String(value))){const o=document.createElement('option');o.value=value;o.textContent=value+' (mặc định server)';input.append(o);}input.value=value;});
  });
})();
