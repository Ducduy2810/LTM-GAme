// Browser-side audit of visible text on solid/gradient surfaces. Artwork itself is
// checked in screenshots; it is not treated as a uniform color by this helper.
export function auditVisibleText(){
  const parse=value=>{const parts=value.match(/[\d.]+/g)?.map(Number);return parts?.length>=3?[...parts.slice(0,3),parts[3]??1]:[0,0,0,0];};
  const blend=(fg,bg)=>fg.slice(0,3).map((v,i)=>v*fg[3]+bg[i]*(1-fg[3]));
  const luminance=rgb=>rgb.map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((a,v,i)=>a+v*[.2126,.7152,.0722][i],0);
  const ratio=(a,b)=>{const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
  function backgrounds(element){
    const chain=[];for(let p=element;p;p=p.parentElement)chain.unshift(p);
    let samples=[[255,255,255]];
    for(const p of chain){
      const style=getComputedStyle(p),color=parse(style.backgroundColor);samples=samples.map(bg=>blend(color,bg));
      if(style.backgroundImage.includes('gradient(')){
        const stops=[...style.backgroundImage.matchAll(/rgba?\([^)]+\)/g)].map(m=>parse(m[0]));
        if(stops.length)samples=stops.flatMap(stop=>samples.map(bg=>blend(stop,bg)));
      }
      // Bound the samples for deeply nested gradients; retain light/dark extremes.
      if(samples.length>16){samples.sort((a,b)=>luminance(a)-luminance(b));samples=[...samples.slice(0,8),...samples.slice(-8)];}
    }
    return samples;
  }
  const walker=document.createTreeWalker(document.querySelector('dialog[open],.modal-overlay.active')||document.body,NodeFilter.SHOW_TEXT),failures=[],seen=new Set();
  let text;
  while(text=walker.nextNode()){
    const label=text.textContent.trim().replace(/\s+/g,' '),node=text.parentElement;
    if(!/[\p{L}\p{N}]/u.test(label)||!node||node.closest('script,style,svg,[aria-hidden="true"],button:disabled,.rank-emblem,.stat-icon,.trophy-glow'))continue;
    const rect=node.getBoundingClientRect(),style=getComputedStyle(node);
    if(!rect.width||!rect.height||style.visibility==='hidden')continue;
    let hidden=false;for(let p=node;p;p=p.parentElement){const s=getComputedStyle(p);if(Number(s.opacity)<.9){hidden=true;break;}if(s.overflowX!=='visible'||s.overflowY!=='visible'){const r=p.getBoundingClientRect();if(rect.left>=r.right||rect.right<=r.left||rect.top>=r.bottom||rect.bottom<=r.top){hidden=true;break;}}}
    if(hidden)continue;
    const size=parseFloat(style.fontSize),required=size>=24||(size>=18.66&&Number(style.fontWeight)>=700)?3:4.5;
    const bg=backgrounds(node),foreground=parse(style.color),contrast=Math.min(...bg.map(b=>ratio(blend(foreground,b),b)));
    if(contrast+.02<required){const selector=node.id?'#'+node.id:node.tagName.toLowerCase()+'.'+[...node.classList].join('.'),key=selector+style.color+label.slice(0,45);if(seen.has(key))continue;seen.add(key);failures.push({selector,text:label.slice(0,80),size,color:style.color,ratio:Number(contrast.toFixed(2)),required});}
  }
  return failures;
}
