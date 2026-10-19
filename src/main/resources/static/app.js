'use strict';
let csrf,csrfPromise,busy=false,redirecting=false,streamRetryAt=0,statusBusy=false;
const polling=[];
const $=id=>document.getElementById(id);
const video=new VideoPreview.Player($('camera'),{
  unauthorized:()=>loginRequired(),
  error:error=>{$('cameraNotice').hidden=false;$('cameraNotice').textContent=error.message;streamRetryAt=Date.now()+1000;},
  ended:()=>{streamRetryAt=Math.max(streamRetryAt,Date.now()+250);},
  frame:()=>{$('cameraNotice').hidden=true;}
});
function loginRequired(){
  if(!redirecting){redirecting=true;polling.forEach(clearInterval);video.stop();location.replace('/login');}
  throw Error('登录已失效，请重新登录');
}
async function fetchJson(url,options={}){
  if(redirecting)throw Error('正在返回登录页面');
  const response=await fetch(url,{...options,credentials:'same-origin',cache:'no-store'});
  if(response.status===401||(response.redirected&&new URL(response.url,location.href).pathname==='/login'))loginRequired();
  const type=response.headers.get('Content-Type')||'';
  const result=type.includes('application/json')?await response.json():null;
  if(!response.ok){
    if(response.status===403){csrf=null;csrfPromise=null;throw Error('请求验证失败，请刷新页面后重试');}
    throw Error(result?.message||'请求失败（HTTP '+response.status+'）');
  }
  if(!result)throw Error('服务返回了非 JSON 响应，请刷新页面');
  return result;
}
async function api(path,options={}){
  if(!csrf){
    if(!csrfPromise)csrfPromise=fetchJson('/api/csrf').then(value=>{csrf=value;return value;}).finally(()=>{csrfPromise=null;});
    await csrfPromise;
  }
  options.headers=options.headers||{};
  if(options.method&&options.method!=='GET')options.headers[csrf.header]=csrf.token;
  return fetchJson('/api'+path,options);
}
function preview(){
  if(redirecting||Date.now()<streamRetryAt||document.hidden)return;
  video.start('/api/camera/stream.mjpg?t='+Date.now());
}
document.addEventListener('visibilitychange',()=>{
  if(document.hidden)video.stop();
  else update();
});
function notice(message){$('notice').textContent=message;}
function textCell(row,value){const td=document.createElement('td');td.textContent=value==null?'—':value;row.appendChild(td);return td;}
function buttonCell(row,label,action){const td=document.createElement('td');const button=document.createElement('button');button.textContent=label;button.className='danger';button.onclick=()=>run(action);td.appendChild(button);row.appendChild(td);}
function time(value){return value?new Date(value).toLocaleString():'尚未同步';}
function stateCell(row,state){const td=document.createElement('td');const span=document.createElement('span');span.className='badge '+(state==='PENDING'?'pending':state==='FAILED'?'failed':'');span.textContent=({PENDING:'待同步',SYNCED:'已同步',FAILED:'需核查'})[state]||state;td.appendChild(span);row.appendChild(td);}
async function run(action){if(busy)return;busy=true;document.querySelectorAll('button').forEach(b=>b.disabled=true);try{await action();await update();await lists();}catch(error){notice(error.message);}finally{busy=false;document.querySelectorAll('button').forEach(b=>b.disabled=false);}}
async function update(){
  if(statusBusy||redirecting)return;statusBusy=true;
  try{
    const status=await api('/status');
    $('message').textContent=status.recognition.message;const result=status.recognition.recognition;
    $('identity').textContent=result.employeeId?`${result.employeeName} · ${result.employeeId} · 相似度 ${result.similarity.toFixed(3)}`:'';
    $('cameraState').textContent=status.camera.state+' · 采集 '+(status.camera.captureFps||0).toFixed(1)+' FPS · 预览编码 '+(status.camera.previewFps||0).toFixed(1)+' FPS · 识别 '+(status.recognition.inferenceMs||0).toFixed(0)+' ms';
    $('cameraNotice').hidden=status.camera.lastFrame>0&&Date.now()-status.camera.lastFrame<1500;$('cameraNotice').textContent=status.camera.state;
    if(status.camera.lastFrame>0&&Date.now()-status.camera.lastFrame<1500){
      preview();
    }else if(video.active)video.stop();
    $('cloudState').textContent=status.gallery.state+(status.gallery.cacheUsable?' · 缓存可用':' · 暂不可识别');$('cacheTime').textContent=time(status.gallery.lastSync);
    $('templates').textContent=status.gallery.templates+' 个 / '+status.gallery.employees+' 名员工';
    $('cloudSync').textContent=status.sync.cloud;$('remoteSync').textContent=status.sync.thirdPartyEnabled?status.sync.thirdParty:'未启用';
    const c=status.sync.counts;$('count').textContent=`本地记录 ${c.TOTAL||0} · 云端待传 ${c.CLOUD_PENDING||0} · 第三方待传 ${c.REMOTE_PENDING||0}`;
    const canvas=$('boxes'),ctx=canvas.getContext('2d');canvas.width=status.camera.width;canvas.height=status.camera.height;
    if(result.box&&status.camera.lastFrame>0){const b=result.box;ctx.strokeStyle=result.accepted?'#32deb2':'#f0c268';ctx.lineWidth=3;ctx.strokeRect(...b);}
  }catch(error){notice(error.message);}finally{statusBusy=false;}
}
async function lists(){
  const [employees,events]=await Promise.all([api('/employees'),api('/events')]);$('employees').replaceChildren();$('events').replaceChildren();
  for(const e of employees){const tr=document.createElement('tr');textCell(tr,e.employeeId);textCell(tr,e.name);textCell(tr,e.templates);textCell(tr,e.active?'启用':'停用');if(e.active)buttonCell(tr,'停用并删除模板',async()=>{if(confirm('确认停用 '+e.name+' 并删除其人脸模板？')){const r=await api('/employees/'+encodeURIComponent(e.employeeId),{method:'DELETE'});notice(r.message);}});else textCell(tr,'—');$('employees').appendChild(tr);}
  for(const e of events){const tr=document.createElement('tr');textCell(tr,time(e.occurredAt));textCell(tr,e.employeeName+' / '+e.employeeId);textCell(tr,e.punchType);textCell(tr,e.similarity.toFixed(3));stateCell(tr,e.cloudStatus);stateCell(tr,e.remoteStatus);if(e.remoteStatus==='FAILED')buttonCell(tr,'核查后重试',async()=>{if(confirm('请先确认第三方是否已接收此记录。确认重试？')){await api('/events/'+e.eventId+'/retry',{method:'POST'});notice('已重新加入队列，沿用原事件 ID');}});else textCell(tr,e.lastError||'—');$('events').appendChild(tr);}
}
$('refresh').onclick=()=>run(async()=>{await api('/gallery/refresh',{method:'POST'});notice('云端模板同步完成');});
$('enroll').onsubmit=event=>{event.preventDefault();run(async()=>{const data=new FormData(event.target);const r=await api('/enroll/camera',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({employeeId:data.get('employeeId'),name:data.get('name')})});notice(r.message);});};
$('upload').onclick=()=>run(async()=>{if(!$('enroll').reportValidity())return;const data=new FormData($('enroll'));if(!data.get('file').size)throw Error('请选择照片');const r=await api('/enroll/photo',{method:'POST',body:data});notice(r.message);});
$('check').onclick=()=>run(async()=>{const data=new FormData($('enroll'));if(!data.get('file').size)throw Error('请选择待测试照片');const r=await api('/face/check',{method:'POST',body:data});notice(r.message+(r.employeeId?'：'+r.employeeName+' / '+r.employeeId+' / '+r.similarity.toFixed(3):''));});
polling.push(setInterval(update,1000),setInterval(()=>lists().catch(e=>notice(e.message)),5000));
update();lists().catch(e=>notice(e.message));
