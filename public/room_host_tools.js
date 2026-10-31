(() => {
  'use strict';
  let panel,trackedRoom='',roomState='WAITING',players=[],hostUsername='';
  function hide(reset=false){if(panel)panel.hidden=true;if(reset){trackedRoom='';players=[];hostUsername='';roomState='WAITING';}}
  window.RoomHostTools={observe(data,context){
    if(data.explanation){
      const screen=document.getElementById(data.type==='wheel_end_game'?'end-screen':'result-screen');
      if(screen){let note=screen.querySelector('.learning-explanation');if(!note){note=document.createElement('p');note.className='learning-explanation';screen.append(note);}note.textContent='Lời giải: '+data.explanation;}
    }else if(data.type.endsWith('_round_result')){
      document.querySelectorAll('.learning-explanation').forEach(n=>n.remove());
    }
    if(data.cancelled&&['arena_room_closed','survival_room_closed','left_wheel_room'].includes(data.type))window.SystemClient?.showCancellation(data);
    if(data.type==='room_kicked')window.SystemClient?.showKick(data);
    if(data.type==='server_notice'||(data.type==='left_wheel_room'&&data.message))window.RoomNotices?.show({username:'Thông báo',message:data.message});
    if(['left_room_success','left_wheel_room','left_survival_room','arena_room_closed','survival_room_closed','auth_error','room_kicked'].includes(data.type)){hide(true);return;}
    if(data.roomCode&&context.roomCode&&data.roomCode!==context.roomCode&&!data.type.endsWith('_room_info')&&data.type!=='room_info')return;
    const code=data.roomCode||context.roomCode;
    if(!code||!context.currentUsername)return;
    if(code!==trackedRoom){hide(true);trackedRoom=code;}
    if(data.gameState)roomState=data.gameState;
    if(data.type.endsWith('_game_started')||data.type.endsWith('_new_question'))roomState='PLAYING';
    if(data.type.endsWith('_round_result'))roomState='RESULT';
    if(data.type.endsWith('_end_game'))roomState='END';
    if(Array.isArray(data.players))players=data.players;
    if(data.hostUsername)hostUsername=data.hostUsername;
    const me=players.find(p=>p.username===context.currentUsername);
    const host=hostUsername===context.currentUsername||me?.isHost;
    if(!host){hide();return;}
    if(!panel){panel=document.createElement('details');panel.className='host-tools';document.body.append(panel);}
    panel.hidden=false;const open=panel.open;panel.replaceChildren();
    const summary=document.createElement('summary');summary.textContent='♙ Quản lý thành viên · Chủ phòng';panel.append(summary);
    const list=document.createElement('div');list.className='host-tools-members';let count=0;
    players.filter(p=>!p.isBot&&p.username!==context.currentUsername&&!p.isSuperAdmin).forEach(p=>{
      count++;const row=document.createElement('div'),name=document.createElement('span'),kick=document.createElement('button');row.className='host-tools-member';name.textContent=p.username;kick.textContent='Kick';kick.type='button';kick.setAttribute('aria-label','Kick '+p.username);
      kick.addEventListener('click',()=>{if(confirm(`Mời ${p.username} rời phòng? Người này sẽ không thể vào lại phòng hiện tại.`))context.send({type:'kick_'+context.game+'_player',targetUsername:p.username});});row.append(name,kick);list.append(row);
    });
    if(!count){const empty=document.createElement('p');empty.textContent='Chưa có thành viên khác để kick.';list.append(empty);}
    panel.append(list);
    const defaultRoom={arena:'DT100',wheel:'CNKD',survival:'DTST'}[context.game];
    if(['WAITING','END'].includes(roomState)&&trackedRoom!==defaultRoom){
      const remove=document.createElement('button');remove.type='button';remove.className='host-tools-delete';remove.textContent='Xóa phòng chờ';
      const roomCode=trackedRoom,type={arena:'close_arena_room',wheel:'close_wheel_room',survival:'close_survival_room'}[context.game];
      remove.addEventListener('click',()=>{if(confirm('Xóa phòng chờ '+roomCode+' và đưa các thành viên về sảnh?'))context.send({type,roomCode});});panel.append(remove);
    }
    panel.open=open;
  }};
})();
