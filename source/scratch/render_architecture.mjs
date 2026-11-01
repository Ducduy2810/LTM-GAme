import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {spawn} from 'node:child_process';
const profile=path.resolve('scratch',`portal-browser-architecture-${Date.now()}`);fs.mkdirSync(profile,{recursive:true});
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));let socket;
try{
  let port;for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await sleep(100);}}
  if(!port)throw new Error('Chrome did not start');
  const pages=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();socket=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl.replace('localhost','127.0.0.1'));
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  let counter=0;const pending=new Map();
  socket.addEventListener('message',event=>{const message=JSON.parse(event.data),waiter=pending.get(message.id);if(waiter){pending.delete(message.id);message.error?waiter.reject(new Error(JSON.stringify(message.error))):waiter.resolve(message.result);}});
  function call(method,params={}){return new Promise((resolve,reject)=>{const id=++counter;const timer=setTimeout(()=>reject(new Error('CDP timeout '+method)),12000);pending.set(id,{resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}});socket.send(JSON.stringify({id,method,params}));});}
  await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{width:1600,height:1390,deviceScaleFactor:2,mobile:false});
  await call('Page.navigate',{url:pathToFileURL(path.resolve('report/images/architecture.svg')).href});
  for(let i=0;i<60;i++){const state=await call('Runtime.evaluate',{expression:'document.readyState',returnByValue:true});if(state.result.value==='complete')break;await sleep(100);}
  await call('Runtime.evaluate',{expression:'document.fonts.ready',awaitPromise:true});
  const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  fs.writeFileSync('report/images/architecture.png',Buffer.from(shot.data,'base64'));
  console.log('Created report/images/architecture.png (3200 x 2780) from the editable SVG.');
}finally{socket?.close();browser.kill();}
