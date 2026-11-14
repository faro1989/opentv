/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

/**
 * The phone remote itself — one self-contained page in the app's Obsidian Stream look: a big
 * circular D-pad with OK, back / menu / guide, play controls, channel and volume rockers, a number
 * pad and a text field that types into the TV. Taps vibrate. Every command is sealed with the key
 * from the QR code before it leaves the phone ([LanCrypto]).
 */
object RemotePage {
    fun html(): String = """
<!DOCTYPE html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#0B0F14"><meta name="apple-mobile-web-app-capable" content="yes">
<title>OpenTV Fernbedienung</title>
<style>
:root{--c:#00F2FE;--bg:#0B0F14;--s:#141A23;--e:#1E2633;--m:#94A3B8;--v:#7928CA;--g:#FF007A}
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;user-select:none}
body{background:var(--bg);color:#F2F4F8;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;min-height:100vh;padding:max(16px,env(safe-area-inset-top)) 16px max(20px,env(safe-area-inset-bottom));display:flex;flex-direction:column;gap:14px;max-width:440px;margin:0 auto}
header{display:flex;align-items:center;gap:10px}
.logo{width:32px;height:32px;border-radius:9px;background:linear-gradient(135deg,var(--c),var(--v));display:grid;place-items:center;color:var(--bg);font-weight:900}
header b{flex:1;font-size:18px}.lock{font-size:12px;color:var(--c);border:1px solid rgba(0,242,254,.35);background:rgba(0,242,254,.1);padding:4px 9px;border-radius:99px}
.status{font-size:12px;color:var(--m);text-align:center;min-height:16px}
.status.err{color:var(--g)}
button{font:inherit;color:inherit;border:1px solid rgba(255,255,255,.08);background:var(--e);border-radius:16px;cursor:pointer;touch-action:manipulation}
button:active{background:rgba(0,242,254,.18);border-color:var(--c);transform:scale(.97)}
.row{display:grid;gap:10px}.r3{grid-template-columns:repeat(3,1fr)}.r4{grid-template-columns:repeat(4,1fr)}
.row button{height:52px;font-size:15px;font-weight:600}
.pad{position:relative;width:min(78vw,300px);aspect-ratio:1;margin:4px auto;border-radius:50%;background:var(--s);border:1px solid rgba(255,255,255,.08);box-shadow:0 0 40px rgba(0,242,254,.12)}
.pad button{position:absolute;border-radius:50%;font-size:26px;background:transparent;border:none;color:#cbd5e1}
.pad .u{top:4%;left:33%;width:34%;height:28%}.pad .d{bottom:4%;left:33%;width:34%;height:28%}
.pad .l{left:4%;top:33%;width:28%;height:34%}.pad .r{right:4%;top:33%;width:28%;height:34%}
.pad .ok{top:30%;left:30%;width:40%;height:40%;background:var(--c);color:var(--bg);font-weight:800;font-size:20px;box-shadow:0 0 24px rgba(0,242,254,.45)}
.pad button:active{background:rgba(0,242,254,.2)}.pad .ok:active{background:#7ff8ff}
.rock{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.rk{display:flex;flex-direction:column;background:var(--s);border-radius:20px;border:1px solid rgba(255,255,255,.08);overflow:hidden}
.rk button{border:none;border-radius:0;height:52px;font-size:22px;background:transparent}
.rk span{text-align:center;font-size:11px;color:var(--m);font-weight:700;letter-spacing:.08em;padding:2px 0}
.num{display:none}.num.on{display:grid}
.txt{display:flex;gap:8px}.txt input{flex:1;background:var(--s);border:1px solid rgba(255,255,255,.12);border-radius:14px;padding:13px;color:#fff;font-size:16px;user-select:text}
.txt input:focus{outline:none;border-color:var(--c)}.txt button{padding:0 16px;background:var(--c);color:var(--bg);font-weight:700}
.tabs{display:flex;gap:8px}.tabs button{flex:1;height:40px;font-size:13px;font-weight:600;color:var(--m)}.tabs button.on{color:#fff;border-color:rgba(0,242,254,.4);background:rgba(0,242,254,.12)}
.hint{font-size:11px;color:#64748B;text-align:center}
</style></head><body>
<header><div class="logo">▶</div><b>OpenTV</b><span class="lock" id="lock">🔒 verschlüsselt</span></header>
<div class="status" id="st">Verbunden mit deinem Fernseher</div>
<div class="row r4">
 <button data-k="back">↩<br><small>Zurück</small></button><button data-k="menu">☰<br><small>Menü</small></button>
 <button data-k="guide">▦<br><small>EPG</small></button><button data-k="info">ⓘ<br><small>Info</small></button>
</div>
<div class="pad">
 <button class="u" data-k="up">▲</button><button class="l" data-k="left">◀</button>
 <button class="ok" data-k="ok" data-long="1">OK</button>
 <button class="r" data-k="right">▶</button><button class="d" data-k="down">▼</button>
</div>
<div class="rock">
 <div class="rk"><button data-k="volup">＋</button><span>LAUTER</span><button data-k="mute" style="height:36px;font-size:15px">🔇</button><span>LEISER</span><button data-k="voldown">－</button></div>
 <div class="rk"><button data-k="chup">▲</button><span>KANAL</span><button data-k="rec" style="height:36px;font-size:15px;color:var(--g)">●</button><span>AUFNAHME</span><button data-k="chdown">▼</button></div>
</div>
<div class="row r4"><button data-k="rew">⏪</button><button data-k="play">⏯</button><button data-k="stop">⏹</button><button data-k="ff">⏩</button></div>
<div class="tabs"><button id="tN">123 Ziffern</button><button id="tT">⌨ Text</button></div>
<div class="row r3 num" id="num">
 <button data-k="1">1</button><button data-k="2">2</button><button data-k="3">3</button>
 <button data-k="4">4</button><button data-k="5">5</button><button data-k="6">6</button>
 <button data-k="7">7</button><button data-k="8">8</button><button data-k="9">9</button>
 <button data-k="del">⌫</button><button data-k="0">0</button><button data-k="ok">OK</button>
</div>
<div class="txt" id="txt" style="display:none"><input id="ti" placeholder="Text an den Fernseher …" autocomplete="off" autocapitalize="off"><button id="ts">Senden</button></div>
<p class="hint">Tipp: Zum Startbildschirm hinzufügen – dann ist die Fernbedienung immer griffbereit.</p>
<script>${LanCrypto.JS}</script>
<script>
var T=new URLSearchParams(location.search).get('t'),N=Date.now()*1000,st=document.getElementById('st');
if(!OTVC.hasKey()){st.textContent='Bitte den QR-Code auf dem Fernseher scannen.';st.className='status err';document.getElementById('lock').style.display='none';}
function send(o){if(!OTVC.hasKey())return;o.n=++N;
 fetch('/cmd?t='+encodeURIComponent(T),{method:'POST',headers:{'Content-Type':'text/plain'},body:OTVC.seal(JSON.stringify(o))})
 .then(function(r){if(r.status==403){st.textContent='Kopplung ungültig – QR-Code neu scannen.';st.className='status err';}else{st.textContent='Verbunden mit deinem Fernseher';st.className='status';}})
 .catch(function(){st.textContent='Fernseher nicht erreichbar – OpenTV offen und im selben WLAN?';st.className='status err';});
 if(navigator.vibrate)navigator.vibrate(12);}
var hold=null;
document.querySelectorAll('[data-k]').forEach(function(b){
 var k=b.getAttribute('data-k');
 if(b.hasAttribute('data-long')){
  b.addEventListener('pointerdown',function(){hold=setTimeout(function(){hold='done';send({k:k,long:true});},550);});
  b.addEventListener('pointerup',function(){if(hold!=='done'){clearTimeout(hold);send({k:k});}hold=null;});
  b.addEventListener('pointerleave',function(){if(hold&&hold!=='done')clearTimeout(hold);hold=null;});
 } else {
  var rep=null;
  b.addEventListener('pointerdown',function(e){e.preventDefault();send({k:k});
   if(['up','down','left','right','volup','voldown'].indexOf(k)>=0)rep=setTimeout(function loop(){send({k:k});rep=setTimeout(loop,140);},450);});
  ['pointerup','pointerleave','pointercancel'].forEach(function(ev){b.addEventListener(ev,function(){clearTimeout(rep);});});
 }
});
var tN=document.getElementById('tN'),tT=document.getElementById('tT'),num=document.getElementById('num'),txt=document.getElementById('txt'),ti=document.getElementById('ti');
tN.onclick=function(){num.classList.toggle('on');txt.style.display='none';tN.classList.toggle('on',num.classList.contains('on'));tT.classList.remove('on');};
tT.onclick=function(){var on=txt.style.display=='none';txt.style.display=on?'flex':'none';num.classList.remove('on');tN.classList.remove('on');tT.classList.toggle('on',on);if(on)ti.focus();};
function sendText(){if(ti.value){send({text:ti.value});ti.value='';}}
document.getElementById('ts').onclick=sendText;ti.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();sendText();}});
</script></body></html>
"""
}
