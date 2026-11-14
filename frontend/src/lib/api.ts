export type Row = Record<string, any>;
let csrf = '';
const pending = new Map<string,string>();
export async function token(){const r=await fetch('/api/auth/csrf',{credentials:'same-origin'});const j=await r.json();csrf=j.token;return csrf;}
export async function api(path:string,body?:unknown,key?:string):Promise<any>{
 const headers:Record<string,string>={};if(body!==undefined){if(!csrf)await token();headers['X-CSRF-TOKEN']=csrf;const fingerprint=path+JSON.stringify(body);if(!pending.has(fingerprint))pending.set(fingerprint,crypto.randomUUID());headers['Idempotency-Key']=key||pending.get(fingerprint)!;if(!(body instanceof FormData))headers['Content-Type']='application/json';}
 const r=await fetch(path,{method:body===undefined?'GET':'POST',credentials:'same-origin',headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
 if(body!==undefined)pending.delete(path+JSON.stringify(body));
 if(!r.ok){let j:Row={};try{j=await r.json();}catch{}if(r.status===401)window.dispatchEvent(new Event('session-expired'));throw new Error(j.message||`Không thực hiện được yêu cầu (${r.status}).`);}
 return r.status===204?null:r.json();
}
export async function login(username:string,password:string){await token();const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','X-CSRF-TOKEN':csrf},body:new URLSearchParams({username,password})});if(!r.ok)throw new Error('Tên đăng nhập hoặc mật khẩu không đúng.');await token();}
export async function logout(){await api('/api/auth/logout',{});csrf='';}
