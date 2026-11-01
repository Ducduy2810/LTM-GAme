import fs from 'node:fs';
import assert from 'node:assert/strict';
const base=process.argv[2];if(!/^http:\/\/localhost:\d+$/.test(base||''))throw Error('Supply isolated preview URL');
const bytes=fs.readFileSync('public/assets/login/login-background.mp4'),url=base+'/assets/login/login-background.mp4';
const full=await fetch(url);assert.equal(full.status,200);assert.equal(full.headers.get('content-type'),'video/mp4');assert.equal(full.headers.get('accept-ranges'),'bytes');assert.deepEqual(Buffer.from(await full.arrayBuffer()),bytes);
for(const [header,start,end] of [['bytes=0-63',0,63],['bytes=-16',bytes.length-16,bytes.length-1],[`bytes=${bytes.length-32}-`,bytes.length-32,bytes.length-1],[`bytes=${bytes.length-16}-${bytes.length+500}`,bytes.length-16,bytes.length-1]]){
  const r=await fetch(url,{headers:{Range:header}});assert.equal(r.status,206);assert.equal(r.headers.get('content-range'),`bytes ${start}-${end}/${bytes.length}`);assert.equal(Number(r.headers.get('content-length')),end-start+1);assert.deepEqual(Buffer.from(await r.arrayBuffer()),bytes.subarray(start,end+1));
}
for(const header of [`bytes=${bytes.length}-`,'bytes=7-3','bytes=-0','bytes=0-3,8-10','bytes=9999999999999999999999999-']){const r=await fetch(url,{headers:{Range:header}});assert.equal(r.status,416);assert.equal(r.headers.get('content-range'),`bytes */${bytes.length}`);assert.equal((await r.arrayBuffer()).byteLength,0);}
const head=await fetch(url,{method:'HEAD',headers:{Range:'bytes=0-15'}});assert.equal(head.status,200);assert.equal(Number(head.headers.get('content-length')),bytes.length);assert.equal((await head.arrayBuffer()).byteLength,0);
console.log('PASS: full video bytes and MIME, initial/suffix/open/clamped ranges, invalid/overflow ranges, HEAD without body.');
