import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

class Element {
    constructor() {
        this.style = {}; this.children = []; this.innerText = ''; this.disabled = false;
        this.handlers = new Map();
        this.classes = new Set();
        this.classList = {add: c => this.classes.add(c), remove: c => this.classes.delete(c), contains: c => this.classes.has(c)};
    }
    set innerHTML(value) { this.html = value; this.children = []; }
    get innerHTML() { return this.html || ''; }
    appendChild(child) { this.children.push(child); }
    addEventListener(event, handler) { this.handlers.set(event, handler); }
}
const elements = new Map([...fs.readFileSync('public/arena.html', 'utf8').matchAll(/id="([^"]+)"/g)].map(m => [m[1], new Element()]));
const intervals = new Map(), timeouts = new Map(); let id = 0;
const context = vm.createContext({
    document: {
        getElementById: key => elements.get(key) || null,
        createElement: () => new Element(),
        querySelectorAll: selector => selector === '.answer-btn' ? elements.get('options-container').children : []
    },
    WebSocket: class { sent = []; send(value) { this.sent.push(JSON.parse(value)); } close() {} },
    location: {protocol: 'http:', host: 'localhost', search: ''},
    window: {location: {search: ''}, addEventListener() {}},
    fetch: () => new Promise(() => {}),
    setInterval: fn => { intervals.set(++id, fn); return id; },
    clearInterval: key => intervals.delete(key),
    setTimeout: fn => { timeouts.set(++id, fn); return id; },
    clearTimeout: key => timeouts.delete(key),
    URLSearchParams, console, alert() {}, confirm: () => true
});
vm.runInContext(fs.readFileSync('public/arena.js', 'utf8'), context);
const run = source => vm.runInContext(source, context);
run(`currentUsername = 'host'; handleGameStarted({mainPlayer:'host', totalOpponents:100});
handleNewQuestion({questionId:1, text:'Test', options:['A','B','C','D'], timeLimit:5, phase:'OPPONENTS', isMainPlayer:true});`);
assert(elements.get('options-container').children.every(b => b.disabled), 'Main must wait for opponent phase');
assert(elements.get('btn-life-trust').disabled, 'No early lifeline');
run(`handleMainPlayerTurn({timeLimit:60, message:'Main turn'});`);
assert(elements.get('options-container').children.every(b => !b.disabled), 'Main phase enables answers');
assert(!elements.get('btn-life-trust').disabled, 'Main phase enables lifelines');
run(`lifeTrustUsed = true; updateLifelineButtons();`);
assert(elements.get('btn-life-trust').innerText.includes('Đã dùng'));
run(`handleGameStarted({mainPlayer:'host', totalOpponents:100}); handleMainPlayerTurn({timeLimit:60});`);
assert(!elements.get('btn-life-trust').disabled);
assert(!elements.get('btn-life-trust').innerText.includes('Đã dùng'));
assert.equal(elements.get('btn-life-trust').style.opacity, '1');
run(`handleGamePaused({message:'Paused'});`);
assert(elements.get('options-container').children.every(b => b.disabled));
run(`handleGameResumed({phase:'MAIN', remainingTime:42});`);
assert(elements.get('options-container').children.every(b => !b.disabled));
run(`handleRoundResult({correctAnswerIndex:0, mainPlayerCorrect:true, currentScore:1250, aliveOpponents:87, totalEliminated:13, eliminatedBotCount:13, eliminatedRealOpponents:[], milestones:[{eliminated:5,points:500,isImportantMilestone:true},{eliminated:10,points:1250,isImportantMilestone:true}]});`);
assert.equal(run('myCurrentScore'), 0, 'Score must wait for visible results');
for (const fn of [...timeouts.values()]) fn();
timeouts.clear();
assert(elements.get('result-screen').classes.has('active'));
let tick = [...intervals.values()].at(-1); tick();
assert.equal(run('myCurrentScore'), 500);
assert(elements.get('milestone-notice').innerText.includes('5 người'));
tick(); assert.equal(run('myCurrentScore'), 1250);
assert(elements.get('milestone-notice').innerText.includes('10 người'));
console.log('PASS: UI phase controls, lifeline label/opacity reset, pause/resume and visible sequential milestone animation.');

// Reconnect message order: room info and question arrive before the player list/auth fetch.
run(`currentUsername = ''; isMyHost = false; gamePaused = false;
socket.onmessage({data:JSON.stringify({type:'room_info',roomCode:'TEST',hostUsername:'host',isHost:true,isSuperAdmin:false,gameState:'QUESTION',paused:true})});
handleGameStarted({mainPlayer:'host',totalOpponents:100});
handleNewQuestion({questionId:1,text:'Reconnect',options:['A','B','C','D'],timeLimit:42,phase:'MAIN',isMainPlayer:true,paused:true});`);
assert.equal(elements.get('pause-controls').style.display, 'flex', 'Returning host must see controls before player list/auth finishes');
assert.equal(elements.get('btn-resume-game').style.display, 'inline-block');
assert.equal(elements.get('btn-pause-game').style.display, 'none');
elements.get('btn-resume-game').handlers.get('click')();
assert.equal(run('socket.sent.at(-1).type'), 'arena_resume_game');
run(`handleGameResumed({phase:'MAIN',remainingTime:42});`);
assert.equal(elements.get('btn-resume-game').style.display, 'none');
assert.equal(elements.get('btn-pause-game').style.display, 'inline-block');
run(`isMyHost = false; handleGamePaused({});`);
assert.equal(elements.get('pause-controls').style.display, 'none', 'Ordinary opponent cannot control game');
run(`isCurrentUserAdmin = true; refreshPauseControls();`);
assert.equal(elements.get('pause-controls').style.display, 'flex', 'Admin can resume');
console.log('PASS: returning host sees and can click Resume immediately; host/admin controls stay synchronized.');
