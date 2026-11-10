package com.zhousl.aether.data.pi

/** Served by the existing noVNC origin so its ES modules resolve against the right origin. */
val AgentBrowserObserverHtml = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#screen{margin:0;width:100%;height:100%;background:#101114;overflow:hidden}
#status{position:absolute;bottom:12px;left:12px;color:#ddd;font:14px system-ui;pointer-events:none}</style></head>
<body><div id="screen"></div><div id="status" role="status"></div><script type="module">
import RFB from './core/rfb.js';
let disposed=false, retry, rfb;
const screen=document.querySelector('#screen'), status=document.querySelector('#status');
function connect(){
  if(disposed) return;
  rfb=new RFB(screen,'ws://'+location.host+'/websockify',{shared:true});
  rfb.viewOnly=true; rfb.scaleViewport=true; rfb.resizeSession=false; rfb.clipViewport=false;
  rfb.addEventListener('connect',()=>{status.textContent='';});
  rfb.addEventListener('disconnect',()=>{
    if(disposed) return;
    status.textContent=navigator.language.startsWith('zh')?'观察连接已断开，正在重连…':'Observer disconnected. Reconnecting…';
    retry=setTimeout(connect,2000);
  });
}
window.addEventListener('pagehide',()=>{disposed=true;clearTimeout(retry);rfb?.disconnect();},{once:true});
connect();
</script></body></html>
""".trimIndent()

const val AgentBrowserObserverUrl = "http://127.0.0.1:6080/aether-observer.html"
