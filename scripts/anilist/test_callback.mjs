import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const page = readFileSync('docs/anilist-oauth/callback.html', 'utf8');
const source = page.match(/<script>([\s\S]*)<\/script>/)[1];
const keypair = await webcrypto.subtle.generateKey({name:'RSA-OAEP',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['encrypt','decrypt']);
const encode = value => Buffer.from(value).toString('base64url');
const publicKey = encode(await webcrypto.subtle.exportKey('spki',keypair.publicKey));
const token = 'test-oauth-token-without-real-account';
async function run(endpoint, expires=3600) {
  const state=encode(JSON.stringify({v:1,nonce:'n'.repeat(43),key:publicKey,endpoint,redirect:'https://example.org/callback.html'}));
  const fragment=new URLSearchParams({access_token:token,expires_in:String(expires),state}).toString();
  const location={href:'https://example.org/callback.html#'+fragment,hash:'#'+fragment,pathname:'/callback.html',search:'',protocol:'https:'};
  const button={hidden:true}, status={}, elements=[];
  const document={getElementById:id=>id==='send'?button:status,
    createElement:tag=>{ const element={tag,children:[],append(child){this.children.push(child)},submit(){this.submitted=true}};elements.push(element);return element; },
    body:{append(){}}};
  await vm.runInNewContext(source,{crypto:webcrypto,window:{isSecureContext:true},location,
    history:{replaceState(_a,_b,path){location.hash='';location.href='https://example.org'+path}},
    document,URL,URLSearchParams,TextEncoder,TextDecoder,atob,btoa,Uint8Array,JSON,Error,Number,Object});
  return {state,button,status,form:elements.find(e=>e.tag==='form'),location};
}
const valid=await run('http://192.168.1.10:18767/anilist/'+'n'.repeat(43));
assert.equal(valid.button.hidden,false);assert.equal(valid.location.hash,'');
const fields=Object.fromEntries(valid.form.children.map(input=>[input.name,input.value]));
assert(!JSON.stringify(fields).includes(token));
const aesBytes=await webcrypto.subtle.decrypt({name:'RSA-OAEP'},keypair.privateKey,Buffer.from(fields.key,'base64url'));
const aesKey=await webcrypto.subtle.importKey('raw',aesBytes,{name:'AES-GCM'},false,['decrypt']);
const plain=await webcrypto.subtle.decrypt({name:'AES-GCM',iv:Buffer.from(fields.iv,'base64url'),additionalData:new TextEncoder().encode(valid.state)},aesKey,Buffer.from(fields.data,'base64url'));
assert.deepEqual(JSON.parse(new TextDecoder().decode(plain)),{token,expiresIn:3600,state:valid.state});
valid.button.onclick();assert.equal(valid.form.submitted,true);
const publicTarget=await run('http://203.0.113.10:18767/anilist/'+'n'.repeat(43));
assert.equal(publicTarget.button.hidden,true);assert.equal(publicTarget.form,undefined);
const expired=await run('http://192.168.1.10:18767/anilist/'+'n'.repeat(43),0);
assert.equal(expired.button.hidden,true);assert.equal(expired.form,undefined);
console.log(JSON.stringify({tests:3,passed:3,failed:0,skipped:0,scope:'static OAuth callback encryption and destination validation'}));
