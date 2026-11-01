import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const elements = new Map();
const sourceHtml = fs.readFileSync(new URL('../public/arena.html', import.meta.url), 'utf8');
function node(tag = 'div') {
    const classes = new Set();
    return {
        tagName: tag.toUpperCase(), children: [], listeners: {}, style: {}, value: '', disabled: false,
        className: '', innerText: '', textContent: '',
        classList: {
            add(...names) { names.forEach(name => classes.add(name)); },
            remove(...names) { names.forEach(name => classes.delete(name)); },
            contains(name) { return classes.has(name); },
            toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }
        },
        set innerHTML(value) { this.html = value; this.children = []; },
        get innerHTML() { return this.html || ''; },
        appendChild(child) { this.children.push(child); if (child.id) elements.set(child.id, child); },
        addEventListener(name, handler) { this.listeners[name] = handler; },
        click() { this.listeners.click?.({}); },
        querySelectorAll() { return []; }
    };
}
for (const match of sourceHtml.matchAll(/id="([^"]+)"/g)) elements.set(match[1], node());
const packets = [];
let connection;
class FakeSocket {
    constructor() { connection = this; this.readyState = 1; }
    send(text) { packets.push(JSON.parse(text)); }
    close() {}
}
const alerts = [];
const timers = new Map();
let nextTimer = 1;
const windowObject = { addEventListener() {}, location: {}, arenaSound: null };
const context = vm.createContext({
    document: {
        getElementById(id) { assert.ok(elements.has(id), `Missing HTML element ${id}`); return elements.get(id); },
        createElement: node,
        querySelectorAll(selector) { return selector === '.answer-btn' ? elements.get('options-container').children : []; }
    },
    WebSocket: FakeSocket, location: { protocol: 'http:', host: 'localhost:3000' },
    fetch: async () => ({ json: async () => ({ loggedIn: true, user: { username: 'owner', isAdmin: false } }) }),
    window: windowObject, console, performance,
    alert: value => alerts.push(value), confirm: () => true,
    navigator: { clipboard: { writeText: async () => {} } },
    setInterval(callback) { const id = nextTimer++; timers.set(id, callback); return id; },
    clearInterval(id) { timers.delete(id); }, setTimeout() {}, clearTimeout() {}
});
vm.runInContext(fs.readFileSync(new URL('../public/room_notices.js', import.meta.url), 'utf8'), context);
vm.runInContext(fs.readFileSync(new URL('../public/arena.js', import.meta.url), 'utf8'), context);
await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
function receive(packet) { connection.onmessage({ data: JSON.stringify(packet) }); }
function element(id) { return elements.get(id); }
const info = { type: 'room_info', roomCode: 'CUSTOM', roomName: 'Custom room', topic: 'SCIENCE', difficulty: 'MIX',
    questionCount: 10, timeLimit: 3, mainPlayerTimeLimit: 8, botCount: 1, isHost: true, gameState: 'WAITING' };
receive(info);
receive({ type: 'player_disconnected', roomCode: 'CUSTOM', username: 'lost', message: 'Người chơi [lost] đã mất kết nối.' });
assert.match(element('room-notices').children.at(-1).textContent, /lost/);
const noticeCount = element('room-notices').children.length;
receive({ type: 'player_disconnected', roomCode: 'OTHER', username: 'outside' });
assert.equal(element('room-notices').children.length, noticeCount);
assert.match(element('room-settings-summary').innerText, /Phụ 3s → Chính 8s/);
assert.equal(element('btn-delete-room').style.display, 'inline-block', 'host can delete waiting room');
receive({ type: 'update_arena_players', players: [
    { username: 'owner', isHost: true, isReady: true }, { username: 'guest', isReady: true }
], hostUsername: 'owner', allReady: true, mainSelectionMode: 'MANUAL', selectedMainPlayer: 'guest' });
assert.equal(element('waiting-main-player').value, 'guest');
element('waiting-main-player').listeners.change();
assert.equal(packets.at(-1).type, 'select_arena_main_player');
assert.equal(packets.at(-1).username, 'guest');

receive({ type: 'arena_game_started', mainPlayer: 'guest', totalOpponents: 1 });
assert.equal(element('btn-delete-room').style.display, 'none', 'delete hidden once match starts');
assert.match(element('arena-main-player-label').textContent, /guest/);
assert.match(element('podium-2').className, /main-player/);
assert.ok(element('podium-2').children.some(child => child.textContent === '👑'));
assert.doesNotMatch(element('podium-1').className, /main-player/, 'own seat must not masquerade as main player');
receive({ type: 'arena_game_started', mainPlayer: 'owner', totalOpponents: 1 });
assert.match(element('podium-1').className, /main-player/);
const question = { type: 'arena_new_question', questionId: 1, totalQuestions: 10, text: 'Question', options: ['A', 'B', 'C', 'D'],
    phase: 'OPPONENTS', timeLimit: 3, isMainPlayer: true, isAlive: true, canAnswer: false };
receive(question);
assert.ok(element('options-container').children.every(button => button.disabled), 'main answers locked before own turn');
assert.ok(element('btn-life-trust').disabled && element('btn-life-ask').disabled && element('btn-life-survey').disabled, 'all lifelines locked before main turn');
const before = packets.length;
element('btn-life-trust').listeners.click();
assert.equal(packets.length, before, 'direct button handler cannot bypass main turn');
receive({ type: 'arena_main_player_turn', phase: 'MAIN', timeLimit: 8, message: 'Main turn' });
assert.ok(element('options-container').children.every(button => !button.disabled));
assert.equal(element('btn-life-trust').disabled, false);
receive({ type: 'arena_game_paused', phase: 'MAIN', remainingTime: 6.5, message: 'Paused' });
assert.ok(element('options-container').children.every(button => button.disabled));
assert.equal(element('btn-life-survey').disabled, true);
receive({ type: 'arena_game_resumed', phase: 'MAIN', remainingTime: 6.5, message: 'Resumed' });
assert.equal(element('btn-life-survey').disabled, false);
element('btn-life-trust').listeners.click();
assert.equal(packets.at(-1).type, 'arena_life_trust');
assert.equal(element('btn-life-trust').disabled, false, 'lifeline is only consumed after server confirmation');
receive({ type: 'arena_life_trust', opponentUsername: 'guest', opponentAnswer: 2 });
assert.equal(element('btn-life-trust').disabled, true);
element('options-container').children[0].onclick();
assert.equal(packets.at(-1).type, 'submit_arena_answer');
assert.ok(element('options-container').children.every(button => button.disabled));
assert.equal(element('btn-life-ask').disabled, true, 'no lifeline after locking answer');

receive({ ...question, isMainPlayer: false, canAnswer: true });
assert.ok(element('options-container').children.every(button => !button.disabled));
receive({ type: 'arena_main_player_turn', phase: 'MAIN', timeLimit: 8, message: 'Main turn' });
assert.ok(element('options-container').children.every(button => button.disabled), 'opponents locked during main turn');

for (const [id, value] of Object.entries({ 'create-room-name': 'Room', 'create-room-code': 'TEST', 'create-topic': 'ALL',
    'create-difficulty': 'MIX', 'create-question-count': '10', 'create-bot-count': '3',
    'create-opponent-time': '4', 'create-main-time': '12', 'create-main-mode': 'RANDOM' })) element(id).value = value;
element('btn-submit-create-room').listeners.click();
assert.equal(packets.at(-1).timeLimit, 4);
assert.equal(packets.at(-1).mainPlayerTimeLimit, 12);
assert.equal(packets.at(-1).mainSelectionMode, 'RANDOM');
const previousPackets = packets.length;
element('create-main-time').value = '0';
element('btn-submit-create-room').listeners.click();
assert.equal(packets.length, previousPackets, 'invalid time blocked before creating room');
const beforeDelete = packets.length;
element('btn-delete-room-game').listeners.click();
assert.equal(packets.length, beforeDelete, 'host cannot send deletion from hidden handler');
receive({ type: 'arena_room_closed', message: 'Closed' });
assert.ok(element('lobby-screen').classList.contains('active'), 'deleted room returns to lobby');
assert.equal(packets.at(-1).type, 'get_rooms');
assert.equal(timers.size, 0, 'no local countdown left after deleting room');
console.log('PASS: Arena setup, main selection, answer/lifeline controls, answer lock, host delete removal and admin cancellation feedback.');
