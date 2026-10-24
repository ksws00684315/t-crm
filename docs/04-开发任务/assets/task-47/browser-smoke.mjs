import fs from 'node:fs/promises';
const dir=new URL('./',import.meta.url); const targets=await(await fetch('http://127.0.0.1:9227/json/list')).json();
const ws=new WebSocket(targets.find(x=>x.type==='page'&&x.url==='about:blank').webSocketDebuggerUrl); await new Promise(r=>ws.onopen=r);
let id=0;const pending=new Map();ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){pending.get(m.id)?.(m);pending.delete(m.id)}};
const cdp=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,m=>m.error?reject(m.error):resolve(m.result));ws.send(JSON.stringify({id:n,method,params}))});
const pause=ms=>new Promise(r=>setTimeout(r,ms));const evaluate=async expression=>(await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true})).result.value;
await cdp('Page.enable');const results=[];
for(const [app,port,path,key] of [['admin',8897,'/fund/prepays','tcrm.admin.locale'],['portal',8898,'/income/prepays','tcrm.kol.locale']]){
 for(const language of ['zh-CN','en-US'])for(const [width,height] of (app==='admin'?[[1440,900],[1280,800],[2560,1440]]:[[1440,900],[1280,800],[390,844],[375,812]])){
 await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<500});
 await cdp('Page.navigate',{url:`http://127.0.0.1:${port}${path}`});await pause(1800);
 await evaluate(`localStorage.setItem(${JSON.stringify(key)},${JSON.stringify(language)})`);await cdp('Page.reload');await pause(1200);
 for(let attempt=0;attempt<30;attempt++){if(await evaluate('document.body.innerText.includes("SparkTrack")'))break;await pause(300);}
 const state=await evaluate(`({url:location.href,title:document.title,text:document.body.innerText,overflow:document.documentElement.scrollWidth>innerWidth})`);
 const file=`${app}-unauth-prepays-${width}-${language==='zh-CN'?'zh':'en'}.png`;
 const shot=await cdp('Page.captureScreenshot',{format:'png'});await fs.writeFile(new URL(file,dir),Buffer.from(shot.data,'base64'));
 results.push({app,width,height,language,file,...state});
 }
}
await fs.writeFile(new URL('browser-smoke-results.json',dir),JSON.stringify(results,null,2));console.log(JSON.stringify(results.map(({text,...x})=>({...x,text:text.slice(0,220)})),null,2));ws.close();
