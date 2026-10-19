'use strict';
// Read real JPEG packets and count actual canvas submissions; never count a timer as video FPS.
(function(root){
  class FrameRate {
    constructor(){this.times=[];}
    tick(at=performance.now()){this.times.push(at);this.trim(at);}
    trim(at){while(this.times.length&&at-this.times[0]>2000)this.times.shift();}
    value(at=performance.now()){
      this.trim(at);const t=this.times;if(t.length<2||at-t[t.length-1]>1000)return 0;
      return (t.length-1)*1000/Math.max(1,t[t.length-1]-t[0]);
    }
    maxGap(at=performance.now()){this.trim(at);let gap=0;for(let i=1;i<this.times.length;i++)gap=Math.max(gap,this.times[i]-this.times[i-1]);return this.times.length?Math.max(gap,at-this.times[this.times.length-1]):0;}
  }
  class MjpegParser {
    constructor(onFrame){this.onFrame=onFrame;this.buffer=new Uint8Array(0);this.headers=null;this.length=0;}
    push(chunk){
      if(chunk.length>16*1024*1024)throw Error('视频数据块过大');
      const joined=new Uint8Array(this.buffer.length+chunk.length);joined.set(this.buffer);joined.set(chunk,this.buffer.length);this.buffer=joined;
      while(true){
        if(!this.headers){
          let end=-1;for(let i=0;i+3<this.buffer.length;i++)if(this.buffer[i]===13&&this.buffer[i+1]===10&&this.buffer[i+2]===13&&this.buffer[i+3]===10){end=i;break;}
          if(end<0){if(this.buffer.length>8192)throw Error('视频帧头无效');return;}
          if(end>8192)throw Error('视频帧头过大');
          const header=new TextDecoder().decode(this.buffer.subarray(0,end));this.headers={};
          for(const line of header.split('\r\n')){const at=line.indexOf(':');if(at>0)this.headers[line.slice(0,at).toLowerCase()]=line.slice(at+1).trim();}
          this.length=Number(this.headers['content-length']);
          if(!Number.isInteger(this.length)||this.length<4||this.length>8*1024*1024)throw Error('视频帧长度无效');
          this.buffer=this.buffer.slice(end+4);
        }
        if(this.buffer.length<this.length+2)return;
        const bytes=this.buffer.slice(0,this.length);
        if(bytes[0]!==255||bytes[1]!==216||bytes[bytes.length-2]!==255||bytes[bytes.length-1]!==217)throw Error('视频 JPEG 数据无效');
        if(this.buffer[this.length]!==13||this.buffer[this.length+1]!==10)throw Error('视频帧边界无效');
        const packet={bytes,sequence:Number(this.headers['x-frame-sequence'])||0,capturedAt:Number(this.headers['x-captured-at'])||0};
        this.buffer=this.buffer.slice(this.length+2);this.headers=null;this.length=0;this.onFrame(packet);
      }
    }
  }
  class Player {
    constructor(canvas,callbacks={}){
      this.canvas=canvas;this.context=canvas.getContext('2d',{alpha:false});this.callbacks=callbacks;
      this.active=false;this.generation=0;this.pending=null;this.drawing=false;this.received=new FrameRate();this.displayed=new FrameRate();this.dropped=0;this.ageMs=0;this.decodeMs=0;
    }
    start(url){if(this.active)return;this.active=true;const generation=++this.generation;this.controller=new AbortController();this.read(url,generation,this.controller.signal);}
    stop(){this.generation++;this.active=false;this.pending=null;if(this.controller)this.controller.abort();}
    async read(url,generation,signal){
      let reader;
      try{
        const response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal});
        if(response.status===401||(response.redirected&&new URL(response.url,location.href).pathname==='/login')){this.callbacks.unauthorized?.();return;}
        if(!response.ok)throw Error('视频暂不可用（HTTP '+response.status+'）');
        if(!(response.headers.get('Content-Type')||'').startsWith('multipart/x-mixed-replace')||!response.body)throw Error('视频响应格式不正确');
        reader=response.body.getReader();const parser=new MjpegParser(packet=>{
          if(generation!==this.generation)return;
          this.received.tick();if(this.pending)this.dropped++;this.pending=packet;this.schedule();
        });
        while(generation===this.generation){const {value,done}=await reader.read();if(done)break;parser.push(value);}
      }catch(error){if(!signal.aborted&&generation===this.generation)this.callbacks.error?.(error);}
      finally{
        if(reader){try{await reader.cancel();}catch{}reader.releaseLock();}
        if(generation===this.generation){this.active=false;this.pending=null;this.callbacks.ended?.();}
      }
    }
    schedule(){if(!this.drawing&&this.pending){this.drawing=true;requestAnimationFrame(()=>this.draw());}}
    async draw(){
      const generation=this.generation,packet=this.pending;this.pending=null;let bitmap;
      try{
        if(!packet||!this.active)return;
        const started=performance.now();bitmap=await createImageBitmap(new Blob([packet.bytes],{type:'image/jpeg'}));this.decodeMs=performance.now()-started;
        if(generation!==this.generation||!this.active)return;
        if(this.canvas.width!==bitmap.width)this.canvas.width=bitmap.width;if(this.canvas.height!==bitmap.height)this.canvas.height=bitmap.height;
        this.context.drawImage(bitmap,0,0);this.displayed.tick();
        this.ageMs=packet.capturedAt?Math.max(0,Date.now()-packet.capturedAt):0;this.callbacks.frame?.();
      }catch(error){if(generation===this.generation)this.callbacks.error?.(error);}
      finally{if(bitmap)bitmap.close();this.drawing=false;this.schedule();}
    }
    metrics(){return {receiveFps:this.received.value(),displayFps:this.displayed.value(),maxGapMs:this.displayed.maxGap(),ageMs:this.ageMs,decodeMs:this.decodeMs,dropped:this.dropped};}
  }
  root.VideoPreview={FrameRate,MjpegParser,Player};
  if(typeof module!=='undefined'&&module.exports)module.exports=root.VideoPreview;
})(globalThis);
