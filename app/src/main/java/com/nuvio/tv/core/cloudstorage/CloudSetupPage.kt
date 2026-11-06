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
.step{font-weight:600;margin:4px 0 6px}ol.hint{padding-left:20px;margin:6px 0}ol.hint li{margin:4px 0}a{color:#8fa8ff}
a.btn{display:block;text-align:center;margin-top:14px;padding:11px 16px;border-radius:8px;background:#2e9d5b;color:#fff;text-decoration:none}
textarea.short{min-height:70px}details{margin-top:16px}summary{color:#bbb;cursor:pointer}
.file{padding:8px;border-bottom:1px solid #333;cursor:pointer}.file.sel{background:#28304a}
</style></head><body><main>
<h1 id="t-title"></h1>
<label for="code" id="t-code"></label><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6">
<p class="hint" id="t-safety"></p>
<div id="accounts" hidden>
 <div class="tabs"><button data-tab="drive" class="on">Google Drive</button><button data-tab="terabox">TeraBox</button><button data-tab="rclone">rclone.conf</button></div>
 <div class="card" data-pane="drive">
  <p class="step" id="t-d1"></p>
  <ol class="hint" id="t-guide"></ol>
  <label id="t-name1"></label><input id="d-name" autocomplete="off">
  <label>Client ID</label><input id="d-id" autocomplete="off" spellcheck="false" placeholder="1234-abcd.apps.googleusercontent.com">
  <label>Client secret</label><input id="d-secret" type="password" autocomplete="off">
  <button onclick="driveStart()" id="t-signin"></button>
  <a id="d-open" class="btn" target="_blank" rel="noopener noreferrer" hidden></a>
  <div id="d-step2" hidden>
   <p class="step" id="t-d2"></p>
   <p class="hint" id="t-d2-hint"></p>
   <textarea id="d-redirect" class="short" spellcheck="false" autocomplete="off" placeholder="http://127.0.0.1:53682/?state=…&amp;code=…"></textarea>
   <button onclick="driveFinish()" id="t-finish"></button>
  </div>
  <details><summary id="t-manual"></summary>
   <label>Refresh token</label><input id="d-token" type="password" autocomplete="off">
   <button onclick="driveManual()" id="t-add1"></button>
  </details>
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
d1:'Paso 1 · Tu cliente de Google (una sola vez)',
guide:['<a href="https://console.cloud.google.com/apis/library/drive.googleapis.com" target="_blank" rel="noopener">Activa Google Drive API</a> en tu proyecto de Google Cloud.',
'En <a href="https://console.cloud.google.com/auth/audience" target="_blank" rel="noopener">Público</a> pulsa «Publicar aplicación» (si se queda en prueba, el acceso caduca a los 7 días).',
'En <a href="https://console.cloud.google.com/apis/credentials" target="_blank" rel="noopener">Credenciales</a> crea un ID de cliente OAuth de tipo <b>App de escritorio</b> y copia aquí su ID y su secreto.'],
signin:'Iniciar sesión con Google',open:'Abrir Google y aceptar',d2:'Paso 2 · Pega la dirección',
d2hint:'Tras aceptar, el navegador irá a una página 127.0.0.1 que no carga: es normal. Copia la dirección completa de esa pestaña y pégala aquí.',
finish:'Terminar y añadir a la TV',manual:'Ya tengo un refresh token',
tbHint:'Experimental. Pega la cookie de TeraBox o solo el valor ndus de una sesión nueva.',rcHint:'Pega tu rclone.conf sin cifrar. Solo se leen los remotos drive y terabox; el resto se ignora.',
name:'Nombre (opcional)',cookie:'Cookie o ndus',domain:'Dominio (opcional)',add:'Añadir a la TV',search:'Buscar archivo',find:'Buscar',
content:'ID del título',season:'Temporada (solo series; 0 = especiales)',episode:'Episodio (solo series)',link:'Asociar',
ok:'Hecho. Ya puedes volver a la TV.',linked:'Asociado.',pick:'Elige un archivo.',none:'Sin resultados.',
errors:{wrong_code:'Código incorrecto.',closed:'La TV cerró esta página. Vuelve a abrirla desde Ajustes.',auth:'Credenciales rechazadas por el proveedor.',
network:'Sin conexión con el proveedor.',unsupported:'Dominio no admitido.',nothing_added:'No se añadió ningún remoto (¿cliente propio y refresh token?).',
rclone_encrypted:'El rclone.conf está cifrado.',missing_client_id:'Falta el client ID.',missing_refresh_token:'Falta el refresh token. Usa «Iniciar sesión con Google».',
bad_client_id:'El client ID no tiene el formato de Google (…apps.googleusercontent.com).',invalid_client:'Client ID o secreto incorrectos.',
invalid_grant:'El código o el token ya no vale (caducado, ya usado o app en modo prueba). Vuelve a iniciar sesión.',
redirect_uri_mismatch:'El cliente debe ser de tipo «App de escritorio».',unauthorized_client:'Este cliente no puede usar este inicio de sesión: crea uno de tipo «App de escritorio».',
access_denied:'Has cancelado el permiso en Google.',state_mismatch:'Esa dirección no corresponde a este inicio de sesión. Pulsa otra vez «Iniciar sesión con Google».',
no_code:'No encuentro el código en lo que has pegado: copia la dirección completa que empieza por http://127.0.0.1.',
no_refresh_token:'Google no ha dado acceso permanente. Quita el acceso de la app en tu cuenta de Google y repite.',
drive_api_disabled:'Google Drive API no está activada en tu proyecto (paso 1).',insufficient_scope:'Falta el permiso de lectura de Drive: repite el inicio de sesión y acepta todo.',
token_failed:'Google rechazó el intercambio del código.',google_error:'Google devolvió un error.',bad_content_id:'ID no válido.',bad_episode:'Indica temporada y episodio juntos.'}}
:{title:MODE==='LINK'?'Link files':'Cloud storage',code:'Code shown on the TV',
safety:'What you type here goes straight to your TV over your local network and is stored encrypted on it only. Do not share it in chats or screenshots.',
d1:'Step 1 · Your Google client (once)',
guide:['<a href="https://console.cloud.google.com/apis/library/drive.googleapis.com" target="_blank" rel="noopener">Enable the Google Drive API</a> in your Google Cloud project.',
'Under <a href="https://console.cloud.google.com/auth/audience" target="_blank" rel="noopener">Audience</a> press "Publish app" (in testing, access expires after 7 days).',
'Under <a href="https://console.cloud.google.com/apis/credentials" target="_blank" rel="noopener">Credentials</a> create an OAuth client ID of type <b>Desktop app</b> and copy its ID and secret here.'],
signin:'Sign in with Google',open:'Open Google and accept',d2:'Step 2 · Paste the address',
d2hint:'After you accept, the browser goes to a 127.0.0.1 page that does not load: that is expected. Copy the full address of that tab and paste it here.',
finish:'Finish and add to the TV',manual:'I already have a refresh token',
tbHint:'Experimental. Paste the TeraBox cookie, or just the ndus value, of a fresh session.',rcHint:'Paste an unencrypted rclone.conf. Only drive and terabox remotes are read; the rest is ignored.',
name:'Name (optional)',cookie:'Cookie or ndus',domain:'Domain (optional)',add:'Add to the TV',search:'Find a file',find:'Search',
content:'Title id',season:'Season (series only; 0 = specials)',episode:'Episode (series only)',link:'Link',
ok:'Done. You can go back to the TV.',linked:'Linked.',pick:'Pick a file.',none:'No results.',
errors:{wrong_code:'Wrong code.',closed:'The TV closed this page. Open it again from Settings.',auth:'The provider rejected the credentials.',
network:'Could not reach the provider.',unsupported:'Domain not supported.',nothing_added:'No remote was added (own client and refresh token?).',
rclone_encrypted:'The rclone.conf is encrypted.',missing_client_id:'The client ID is missing.',missing_refresh_token:'The refresh token is missing. Use "Sign in with Google".',
bad_client_id:'That is not a Google client ID (…apps.googleusercontent.com).',invalid_client:'Wrong client ID or secret.',
invalid_grant:'The code or token is no longer valid (expired, used, or app in testing). Sign in again.',
redirect_uri_mismatch:'The client must be of type "Desktop app".',unauthorized_client:'This client cannot use this sign-in: create a "Desktop app" client.',
access_denied:'You cancelled the permission at Google.',state_mismatch:'That address is not from this sign-in. Press "Sign in with Google" again.',
no_code:'No code found in what you pasted: copy the full address starting with http://127.0.0.1.',
no_refresh_token:'Google gave no lasting access. Remove the app from your Google account access and retry.',
drive_api_disabled:'The Google Drive API is not enabled in your project (step 1).',insufficient_scope:'Drive read permission missing: sign in again and accept everything.',
token_failed:'Google refused the code exchange.',google_error:'Google returned an error.',bad_content_id:'Invalid id.',bad_episode:'Give season and episode together.'}};
const set=(id,t)=>{const e=document.getElementById(id);if(e)e.textContent=t};
set('t-title',T.title);set('t-d1',T.d1);set('t-signin',T.signin);set('t-d2',T.d2);set('t-d2-hint',T.d2hint);set('t-finish',T.finish);set('t-manual',T.manual);
const g=document.getElementById('t-guide');T.guide.forEach(h=>{const li=document.createElement('li');li.innerHTML=h;g.appendChild(li)});set('t-code',T.code);set('t-safety',T.safety);set('t-tb-hint',T.tbHint);set('t-rc-hint',T.rcHint);
['t-name1','t-name2'].forEach(i=>set(i,T.name));set('t-cookie',T.cookie);set('t-domain',T.domain);['t-add1','t-add2','t-add3'].forEach(i=>set(i,T.add));
set('t-search',T.search);set('t-find',T.find);set('t-content',T.content);set('t-season',T.season);set('t-episode',T.episode);set('t-link',T.link);
document.getElementById(MODE==='LINK'?'link':'accounts').hidden=false;
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x===b));
document.querySelectorAll('[data-pane]').forEach(p=>p.hidden=p.dataset.pane!==b.dataset.tab)});
const v=id=>document.getElementById(id).value.trim();
function show(ok,text){const m=document.getElementById('msg');m.style.display='block';m.className=ok?'ok':'err';m.textContent=text}
async function post(path,data){data.code=v('code');const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
let j={};try{j=await r.json()}catch(e){}return j}
async function send(path,data){try{const j=await post(path,data);if(j.ok){show(true,T.ok);document.querySelectorAll('input[type=password],textarea').forEach(e=>e.value='');const a=document.getElementById('d-open');if(a){a.hidden=true;a.removeAttribute('href')}}
else show(false,T.errors[j.message]||j.message||'Error')}catch(e){show(false,T.errors.closed)}}
async function driveStart(){try{const j=await post('/api/drive/start',{clientId:v('d-id'),clientSecret:v('d-secret')});
if(!j.ok||!j.url){show(false,T.errors[j.message]||j.message||'Error');return}
const a=document.getElementById('d-open');a.href=j.url;a.textContent=T.open;a.hidden=false;document.getElementById('d-step2').hidden=false;
document.getElementById('msg').style.display='none'}catch(e){show(false,T.errors.closed)}}
function driveFinish(){send('/api/drive/finish',{name:v('d-name'),redirect:v('d-redirect')})}
function driveManual(){send('/api/drive',{name:v('d-name'),clientId:v('d-id'),clientSecret:v('d-secret'),refreshToken:v('d-token')})}
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
