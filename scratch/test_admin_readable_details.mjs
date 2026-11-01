import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const nodes=new Map();
class Element {
  constructor(tag='div'){this.tag=tag;this.children=[];this.events={};this.style={};this.value='';const classes=new Set();this.classList={add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c),toggle(c,on){if(on)classes.add(c);else classes.delete(c);}};}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=[...nodes];}
  addEventListener(event,fn){this.events[event]=fn;}
  querySelector(){return new Element();}
  showModal(){this.open=true;}
  close(){this.open=false;}
  get firstChild(){return this.children[0];}
}
const get=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
const text=node=>[node.textContent??'',...node.children.map(text)].join(' ');
const tags=node=>[node.tag,...node.children.flatMap(tags)];
const question={id:'q1',text:'Giao thức nào bảo mật kết nối web?',topic:'NETWORKING',difficulty:'EASY',options:['HTTP','HTTPS','FTP','SMTP'],correct:1,explanation:'HTTPS sử dụng TLS.'};
const fixture={match:{room_code:'W7877',game_type:'WHEEL',status:'CANCELLED',started_at:1793485119000,ended_at:1793485129000,reason:'Không còn người chơi',config_json:JSON.stringify({timeLimit:10,hint:'Mô hình phân tán lưu trữ chuỗi khối bất biến',topic:'ALL',explanation:'',phrase:'CONG NGHE BLOCKCHAIN'})},players:[{username:'ducbuitrung',score:0,placement:0,outcome:'CANCELLED'}]};
const context={document:{getElementById:get,createElement:tag=>new Element(tag),querySelectorAll:()=>[],querySelector:()=>null,addEventListener(){}},Node:Element,Blob,URLSearchParams,window:{addEventListener(){}},setInterval(){},fetch:async()=>({ok:true,status:200,json:async()=>fixture})};
let source=fs.readFileSync('public/admin.js','utf8');
source=source.replace("api('/session').then(signedIn).catch(()=>loginScreen());","window.test={matchSnapshot,readableFields,auditAction,auditTarget,auditSummary,auditDetail,renderLogs,matchDetail};");
vm.runInNewContext(source,context);
const ui=context.window.test;

await ui.matchDetail('fixture');
let body=get('detail-body');
for(const label of ['Thiết lập trận đấu','Thời gian mỗi lượt','10 giây','Tất cả chủ đề','Ô chữ của trận đấu','CONG NGHE BLOCKCHAIN','Trận đã hủy','Chưa ghi nhận'])assert.ok(text(body).includes(label),label);
assert.ok(!tags(body).includes('pre'),'wheel detail never renders raw JSON');
for(const game of ['ARENA','SURVIVAL']){
  const snapshot=ui.matchSnapshot({topic:'NETWORKING',difficulty:'EASY',questionCount:1,botCount:0,startingHp:100,totalRounds:5,questions:[question]},game);
  for(const label of ['Lập trình mạng','Dễ','Câu 1','HTTPS','Đáp án đúng: B','HTTPS sử dụng TLS.'])assert.ok(text(snapshot).includes(label),game+': '+label);
  assert.ok(tags(snapshot).includes('details'),'questions can be expanded individually');
  assert.ok(!tags(snapshot).includes('pre'));
}
assert.ok(text(ui.matchSnapshot({questions:[null,{}]},'ARENA')).includes('nội dung lưu chưa hợp lệ'));
assert.ok(text(ui.matchSnapshot(null,'WHEEL')).includes('Chưa có cấu hình hợp lệ'));
assert.ok(text(ui.readableFields({enabled:false,count:0,reason:'<img src=x onerror=alert(1)>'})).includes('<img src=x onerror=alert(1)>'),'free text is preserved as text');

const login={action:'ADMIN_LOGIN',actor:'admin',target:'login',created_at:1793485056000,details:JSON.stringify({remote:'0:0:0:0:0:0:0:1'})};
assert.equal(ui.auditAction(login),'Đăng nhập quản trị');
assert.equal(ui.auditTarget(login),'Trang đăng nhập quản trị');
assert.ok(ui.auditSummary(login).includes('Kết nối nội bộ máy chủ'));
assert.equal(ui.auditTarget({action:'CONTENT_SAVE',target:'WHEEL:12'}),'Ô chữ số 12');
assert.equal(ui.auditTarget({action:'HOST_KICK',target:'WHEEL:W7877'}),'Phòng W7877 · Chiếc Nón Kỳ Diệu');
assert.equal(ui.auditTarget({action:'TOPIC_SAVE',target:'QUESTION:NETWORKING'}),'Chủ đề Lập trình mạng · Câu hỏi');
assert.equal(ui.auditAction({action:'CONTENT_SAVE',target:'WHEEL:12'}),'Lưu ô chữ');
assert.equal(ui.auditAction({action:'RACE_DEQUEUE'}),'Xóa người chơi khỏi hàng chờ ghép trận');
assert.equal(ui.auditTarget({action:'RACE_DEQUEUE',target:'',details:'{"targetUsername":"player"}'}),'Người chơi player');
assert.ok(ui.auditAction({action:'ROOM_CANCEL_REQUEST'}).includes('Đã yêu cầu'));
assert.ok(ui.auditAction({action:'ROOM_CANCEL_DONE'}).includes('Đã hoàn tất'));
ui.auditDetail(login);body=get('detail-body');
for(const label of ['Đăng nhập quản trị','Người thực hiện','Trang đăng nhập quản trị','Địa chỉ kết nối đến máy chủ','Kết nối nội bộ máy chủ'])assert.ok(text(body).includes(label),label);
assert.ok(!text(body).includes('ADMIN_LOGIN'));
assert.ok(!text(body).includes('"remote"'));
assert.ok(!tags(body).includes('pre'));
ui.auditDetail({...login,action:'RACE_SETRANK',target:'player',details:JSON.stringify({reason:'Điều chỉnh kết quả',before:{rank:[{current_step:3,current_points:50}]},request:{step:4,rp:20}})});
for(const label of ['Điều chỉnh rank và điểm RP','Dữ liệu trước khi thay đổi','Nội dung điều chỉnh','Bạc 3','Bạc 2','Điểm RP trong cấp'])assert.ok(text(get('detail-body')).includes(label),label);
ui.auditDetail({...login,details:'invalid JSON'});
assert.ok(text(get('detail-body')).includes('Thông tin đã lưu không đọc được'));
ui.auditDetail({...login,details:'{}'});
assert.ok(text(get('detail-body')).includes('Không có thông tin bổ sung'));
ui.renderLogs([login]);
assert.ok(text(get('logs-table')).includes('Đăng nhập quản trị'));
assert.ok(text(get('logs-table')).includes('Trang đăng nhập quản trị'));
assert.ok(!text(get('logs-table')).includes('ADMIN_LOGIN'));
console.log('PASS: readable snapshots for all 3 games, answers, audit actions/targets, nested rank changes, empty/malformed historical data, and safe free text.');
