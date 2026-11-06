package com.nuvio.tv.core.cloudstorage

/**
 * The phone page of cloud setup. Static: it holds no data from the TV, and the TV never sends a
 * stored credential back to it. Spanish or English after the phone's language.
 */
internal object CloudSetupPage {
    fun html(mode: CloudSetupMode): String = TEMPLATE.replace("__MODE__", mode.name)

    private val TEMPLATE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>NuvioTV · Cloud</title>
<style>
:root{color-scheme:dark}body{margin:0;font:16px system-ui,sans-serif;background:#101114;color:#eee}
main{max-width:560px;margin:0 auto;padding:16px}h1{font-size:20px}h2{font-size:17px;margin:20px 0 8px}
label{display:block;margin:12px 0 4px;color:#bbb;font-size:14px}
input,textarea{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #444;background:#1b1d22;color:#eee;font:inherit}
textarea{min-height:140px;font-family:monospace;font-size:13px}
button{margin-top:14px;padding:11px 16px;border:0;border-radius:8px;background:#5b7cfa;color:#fff;font:inherit;width:100%}
.tabs{display:flex;gap:6px;margin-top:14px}.tabs button{margin:0;background:#2a2d35}.tabs button.on{background:#5b7cfa}
.card{background:#16181d;border-radius:12px;padding:12px 14px;margin-top:12px}.hint{color:#999;font-size:13px}
#msg{margin-top:14px;padding:10px;border-radius:8px;display:none}.ok{background:#173d24}.err{background:#4a1d1d}
.file{padding:8px;border-bottom:1px solid #333;cursor:pointer}.file.sel{background:#28304a}
</style></head><body><main>
<h1 id="t-title"></h1>
<label for="code" id="t-code"></label><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6">
<p class="hint" id="t-safety"></p>
<div id="accounts" hidden>
 <div class="tabs"><button data-tab="drive" class="on">Google Drive</button><button data-tab="terabox">TeraBox</button><button data-tab="rclone">rclone.conf</button></div>
 <div class="card" data-pane="drive">
  <p class="hint" id="t-drive-hint"></p>
  <label id="t-name1"></label><input id="d-name" autocomplete="off">
  <label>Client ID</label><input id="d-id" autocomplete="off" spellcheck="false">
  <label>Client secret</label><input id="d-secret" type="password" autocomplete="off">
  <label>Refresh token</label><input id="d-token" type="password" autocomplete="off">
  <button onclick="send('/api/drive',{name:v('d-name'),clientId:v('d-id'),clientSecret:v('d-secret'),refreshToken:v('d-token')})" id="t-add1"></button>
 </div>
 <div class="card" data-pane="terabox" hidden>
  <p class="hint" id="t-tb-hint"></p>
  <label id="t-name2"></label><input id="t-name" autocomplete="off">
  <label id="t-cookie"></label><input id="t-ck" type="password" autocomplete="off">
  <label id="t-domain"></label><input id="t-dom" autocomplete="off" placeholder="https://www.terabox.com">
  <button onclick="send('/api/terabox',{name:v('t-name'),cookie:v('t-ck'),domain:v('t-dom')})" id="t-add2"></button>
 </div>
 <div class="card" data-pane="rclone" hidden>
  <p class="hint" id="t-rc-hint"></p>
  <textarea id="rc" spellcheck="false" autocomplete="off"></textarea>
  <button onclick="send('/api/rclone',{config:v('rc')})" id="t-add3"></button>
 </div>
</div>
<div id="link" hidden>
 <div class="card">
  <label id="t-search"></label><input id="q" autocomplete="off">
  <button onclick="search()" id="t-find"></button>
  <div id="files"></div>
 </div>
 <div class="card">
  <label id="t-content"></label><input id="cid" autocomplete="off" placeholder="tt0903747 · tmdb:1399 · anilist:20 · kitsu:12">
  <label id="t-season"></label><input id="sn" inputmode="numeric">
  <label id="t-episode"></label><input id="ep" inputmode="numeric">
  <button onclick="link()" id="t-link"></button>
 </div>
</div>
<div id="msg"></div>
</main><script>
const MODE='__MODE__';const es=(navigator.language||'').toLowerCase().startsWith('es');
const T=es?{title:MODE==='LINK'?'Asociar archivos':'Almacenamiento en la nube',code:'Código que ves en la TV',
safety:'Lo que escribas aquí va directo a tu TV por tu red local y se guarda cifrado solo en ella. No lo compartas en chats ni capturas.',
driveHint:'Usa tu propio cliente OAuth de Google Cloud (alcance drive.readonly) y su refresh token, como un remoto drive de rclone.',
tbHint:'Experimental. Pega la cookie de TeraBox o solo el valor ndus de una sesión nueva.',rcHint:'Pega tu rclone.conf sin cifrar. Solo se leen los remotos drive y terabox; el resto se ignora.',
name:'Nombre (opcional)',cookie:'Cookie o ndus',domain:'Dominio (opcional)',add:'Añadir a la TV',search:'Buscar archivo',find:'Buscar',
content:'ID del título',season:'Temporada (solo series; 0 = especiales)',episode:'Episodio (solo series)',link:'Asociar',
ok:'Hecho. Ya puedes volver a la TV.',linked:'Asociado.',pick:'Elige un archivo.',none:'Sin resultados.',
errors:{wrong_code:'Código incorrecto.',closed:'La TV cerró esta página. Vuelve a abrirla desde Ajustes.',auth:'Credenciales rechazadas por el proveedor.',
network:'Sin conexión con el proveedor.',unsupported:'Dominio no admitido.',nothing_added:'No se añadió ningún remoto (¿cliente propio y refresh token?).',
rclone_encrypted:'El rclone.conf está cifrado.',bad_content_id:'ID no válido.',bad_episode:'Indica temporada y episodio juntos.'}}
:{title:MODE==='LINK'?'Link files':'Cloud storage',code:'Code shown on the TV',
safety:'What you type here goes straight to your TV over your local network and is stored encrypted on it only. Do not share it in chats or screenshots.',
driveHint:'Use your own Google Cloud OAuth client (drive.readonly scope) and its refresh token, like an rclone drive remote.',
tbHint:'Experimental. Paste the TeraBox cookie, or just the ndus value, of a fresh session.',rcHint:'Paste an unencrypted rclone.conf. Only drive and terabox remotes are read; the rest is ignored.',
name:'Name (optional)',cookie:'Cookie or ndus',domain:'Domain (optional)',add:'Add to the TV',search:'Find a file',find:'Search',
content:'Title id',season:'Season (series only; 0 = specials)',episode:'Episode (series only)',link:'Link',
ok:'Done. You can go back to the TV.',linked:'Linked.',pick:'Pick a file.',none:'No results.',
errors:{wrong_code:'Wrong code.',closed:'The TV closed this page. Open it again from Settings.',auth:'The provider rejected the credentials.',
network:'Could not reach the provider.',unsupported:'Domain not supported.',nothing_added:'No remote was added (own client and refresh token?).',
rclone_encrypted:'The rclone.conf is encrypted.',bad_content_id:'Invalid id.',bad_episode:'Give season and episode together.'}};
const set=(id,t)=>{const e=document.getElementById(id);if(e)e.textContent=t};
set('t-title',T.title);set('t-code',T.code);set('t-safety',T.safety);set('t-drive-hint',T.driveHint);set('t-tb-hint',T.tbHint);set('t-rc-hint',T.rcHint);
['t-name1','t-name2'].forEach(i=>set(i,T.name));set('t-cookie',T.cookie);set('t-domain',T.domain);['t-add1','t-add2','t-add3'].forEach(i=>set(i,T.add));
set('t-search',T.search);set('t-find',T.find);set('t-content',T.content);set('t-season',T.season);set('t-episode',T.episode);set('t-link',T.link);
document.getElementById(MODE==='LINK'?'link':'accounts').hidden=false;
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x===b));
document.querySelectorAll('[data-pane]').forEach(p=>p.hidden=p.dataset.pane!==b.dataset.tab)});
const v=id=>document.getElementById(id).value.trim();
function show(ok,text){const m=document.getElementById('msg');m.style.display='block';m.className=ok?'ok':'err';m.textContent=text}
async function post(path,data){data.code=v('code');const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
let j={};try{j=await r.json()}catch(e){}return j}
async function send(path,data){try{const j=await post(path,data);if(j.ok){show(true,T.ok);document.querySelectorAll('input[type=password],textarea').forEach(e=>e.value='')}
else show(false,T.errors[j.message]||j.message||'Error')}catch(e){show(false,T.errors.closed)}}
let picked=null;
async function search(){try{const j=await post('/api/files',{query:v('q')});const box=document.getElementById('files');box.textContent='';picked=null;
if(!j.ok){show(false,T.errors[j.message]||'Error');return}if(!j.files.length){show(false,T.none);return}
j.files.forEach(f=>{const d=document.createElement('div');d.className='file';d.textContent=f.folder+' / '+f.name;d.onclick=()=>{picked=f.id;
box.querySelectorAll('.file').forEach(x=>x.classList.remove('sel'));d.classList.add('sel')};box.appendChild(d)})}catch(e){show(false,T.errors.closed)}}
async function link(){if(!picked){show(false,T.pick);return}const s=v('sn'),e=v('ep');
try{const j=await post('/api/link',{fileId:picked,contentId:v('cid'),season:s?parseInt(s):null,episode:e?parseInt(e):null});
show(j.ok,j.ok?T.linked:(T.errors[j.message]||j.message||'Error'))}catch(x){show(false,T.errors.closed)}}
</script></body></html>
""".trimIndent()
}
