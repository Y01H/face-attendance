const assert=require('node:assert/strict');
const {FrameRate,MjpegParser,Player}=require('../main/resources/static/preview.js');
const jpeg=Buffer.from([255,216,13,10,13,10,123,45,255,217]);
function wire(seq=1,bytes=jpeg){return Buffer.concat([Buffer.from(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.length}\r\nX-Frame-Sequence: ${seq}\r\nX-Captured-At: 123456\r\n\r\n`),bytes,Buffer.from('\r\n')]);}
let tests=0;
function test(name,fn){fn();tests++;console.log('PASS',name);}
test('multipart parser handles every byte boundary and closing delimiter',()=>{
  const packets=[],parser=new MjpegParser(p=>packets.push(p));const bytes=Buffer.concat([wire(1),wire(2),Buffer.from('--frame--\r\n')]);
  for(const byte of bytes)parser.push(new Uint8Array([byte]));
  assert.equal(packets.length,2);assert.deepEqual(Buffer.from(packets[0].bytes),jpeg);assert.equal(packets[1].sequence,2);assert.equal(packets[0].capturedAt,123456);
});
test('multiple packets in one network chunk are parsed independently',()=>{const packets=[];new MjpegParser(p=>packets.push(p)).push(Buffer.concat([wire(11),wire(12),wire(13)]));assert.deepEqual(packets.map(p=>p.sequence),[11,12,13]);});
test('invalid frame length and corrupt JPEG are rejected',()=>{
  assert.throws(()=>new MjpegParser(()=>{}).push(Buffer.from('--frame\r\nContent-Length: 99999999\r\n\r\n')),/长度/);
  assert.throws(()=>new MjpegParser(()=>{}).push(wire(1,Buffer.from([1,2,3,4]))),/JPEG/);
});
test('FPS follows received frames, goes to zero when stalled and exposes long gaps',()=>{
  const meter=new FrameRate();for(let at=0;at<=1000;at+=40)meter.tick(at);assert.equal(meter.value(1000),25);assert.equal(meter.maxGap(1000),40);meter.tick(1300);assert.equal(meter.maxGap(1300),300);assert.equal(meter.value(2401),0);
});
(async()=>{
  const originals={raf:global.requestAnimationFrame,bitmap:global.createImageBitmap,fetch:global.fetch};const raf=[],decoders=[],closed=[],drawn=[];
  global.requestAnimationFrame=fn=>raf.push(fn);global.createImageBitmap=()=>new Promise(resolve=>decoders.push(resolve));
  const canvas={width:640,height:480,getContext:()=>({drawImage:bitmap=>drawn.push(bitmap.id)})};const player=new Player(canvas);player.active=true;
  const packet=id=>({bytes:jpeg,sequence:id,capturedAt:Date.now()});const bitmap=id=>({id,width:640,height:480,close:()=>closed.push(id)});
  player.pending=packet(1);player.schedule();const first=raf.shift()();
  player.pending=packet(2);player.schedule();player.pending=packet(3);player.schedule();assert.equal(raf.length,0,'Only one decoder can run');
  decoders.shift()(bitmap(1));await first;assert.deepEqual(drawn,[1]);assert.equal(raf.length,1);
  const next=raf.shift()();decoders.shift()(bitmap(3));await next;assert.deepEqual(drawn,[1,3]);assert.deepEqual(closed,[1,3]);
  tests++;console.log('PASS slow decoder keeps one latest pending frame and closes bitmaps');
  player.pending=packet(4);player.schedule();const stale=raf.shift()();player.stop();decoders.shift()(bitmap(4));await stale;assert.deepEqual(drawn,[1,3]);assert(closed.includes(4));
  tests++;console.log('PASS stopping playback prevents stale async decoder from drawing');
  let challenge=0;global.location={href:'http://127.0.0.1:8080/'};global.fetch=async()=>({status:401,redirected:false});const unauth=new Player(canvas,{unauthorized:()=>challenge++});unauth.start('/api/camera/stream.mjpg');await new Promise(setImmediate);assert.equal(challenge,1);assert.equal(unauth.active,false);
  tests++;console.log('PASS unauthorized video returns to login without a browser challenge');
  Object.assign(global,{requestAnimationFrame:originals.raf,createImageBitmap:originals.bitmap,fetch:originals.fetch});
  console.log(tests+' preview tests passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
