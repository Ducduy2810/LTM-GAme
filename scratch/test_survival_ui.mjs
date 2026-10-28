import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const elements = new Map();
function element(tag = 'div') {
    const classes = new Set();
    return {
        style: {}, value: '', disabled: false, children: [], listeners: {}, innerText: '', textContent: '',
        classList: {
            add(...values) { values.forEach(v => classes.add(v)); },
            remove(...values) { values.forEach(v => classes.delete(v)); },
            contains(value) { return classes.has(value); },
            toggle(value, flag) { if (flag) classes.add(value); else classes.delete(value); }
        },
        set innerHTML(value) { this.html = value; this.children = []; },
        get innerHTML() { return this.html || ''; },
        appendChild(child) { this.children.push(child); },
        addEventListener(name, handler) { this.listeners[name] = handler; }
    };
}
for (const match of fs.readFileSync(new URL('../public/survival.html', import.meta.url), 'utf8').matchAll(/id="([^"]+)"/g)) elements.set(match[1], element());
let connection;
const requests = [], timers = new Map(), errors = [];
let nextTimer = 0;
class FakeSocket {
    static OPEN = 1;
    constructor() { connection = this; this.readyState = 1; }
    send(text) { requests.push(JSON.parse(text)); }
    close() {}
}
const fakeWindow = { location: { search: '' }, addEventListener() {}, scrollTo() {} };
const context = vm.createContext({
    document: {
        getElementById(id) { assert.ok(elements.has(id), `Missing HTML element: ${id}`); return elements.get(id); },
        createElement: element,
        querySelectorAll(selector) { return selector === '.answer-btn' ? elements.get('options-container').children : []; }
    },
    WebSocket: FakeSocket, location: { protocol: 'http:', host: 'localhost:3000' }, window: fakeWindow,
    fetch: async () => ({ json: async () => ({ loggedIn: true, user: { username: 'owner', isAdmin: false } }) }),
    URLSearchParams, performance, console: { log() {}, warn() {}, error(...args) { errors.push(args); } },
    alert() {}, confirm: () => true, navigator: { clipboard: { writeText: async () => {} } },
    setInterval(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
    clearInterval(id) { timers.delete(id); }, setTimeout() {}
});
// Nạp đúng thứ tự script của survival.html để bắt lỗi phụ thuộc âm thanh thực tế.
vm.runInContext(fs.readFileSync(new URL('../public/arena_sound.js', import.meta.url), 'utf8'), context);
vm.runInContext(fs.readFileSync(new URL('../public/room_notices.js', import.meta.url), 'utf8'), context);
vm.runInContext(fs.readFileSync(new URL('../public/survival.js', import.meta.url), 'utf8'), context);
await new Promise(resolve => setImmediate(resolve));
assert.ok(connection, 'Authentication should initialize the Survival WebSocket');
const el = id => elements.get(id);
const receive = data => connection.onmessage({ data: JSON.stringify(data) });
const playerStates = [{ username: 'owner', hp: 100, score: 0, isAlive: true }, { username: 'guest', hp: 100, score: 0, isAlive: true }];
receive({ type: 'survival_room_info', roomCode: 'CUSTOM', roomName: 'Room', startingHp: 100, totalRounds: 5, isHost: true, gameState: 'WAITING' });
receive({ type: 'player_disconnected', roomCode: 'CUSTOM', username: 'lost', message: 'Người chơi [lost] đã mất kết nối.' });
assert.match(el('room-notices').children.at(-1).textContent, /lost/);
const noticeCount = el('room-notices').children.length;
receive({ type: 'player_disconnected', roomCode: 'OTHER', username: 'outside' });
assert.equal(el('room-notices').children.length, noticeCount);
assert.equal(el('btn-survival-delete').style.display, 'inline-block');
receive({ type: 'survival_game_started', startingHp: 100, totalRounds: 5 });
assert.equal(el('btn-survival-pause').style.display, 'inline-block');
const question = { type: 'survival_new_question', round: 1, text: 'Question', options: ['Zero', 'One', 'Two', 'Three'], timeLimit: 20,
    phaseName: 'EASY', penalty: 5, playerStates };
receive(question);
assert.ok(el('options-container').children.every(button => !button.disabled));
el('btn-survival-pause').listeners.click();
assert.equal(requests.at(-1).type, 'pause_survival_game');
receive({ type: 'survival_game_paused', gameState: 'QUESTION', remainingTime: 7.5 });
assert.ok(el('options-container').children.every(button => button.disabled));
assert.equal(timers.size, 0);
assert.equal(el('time-left').innerText, 8);
assert.equal(el('btn-survival-resume').style.display, 'inline-block');
const before = requests.length;
el('options-container').children[0].onclick();
assert.equal(requests.length, before, 'paused question cannot submit through click handler');
el('btn-survival-resume').listeners.click();
assert.equal(requests.at(-1).type, 'resume_survival_game');
receive({ type: 'survival_game_resumed', gameState: 'QUESTION', remainingTime: 7.5 });
assert.ok(el('options-container').children.every(button => !button.disabled));
el('options-container').children[0].onclick();
assert.equal(requests.at(-1).type, 'submit_survival_answer');
assert.equal(requests.at(-1).answer, 0);
assert.ok(el('options-container').children.every(button => button.disabled), 'locked choice cannot be edited');
receive({ type: 'survival_answer_rejected', hasAnswered: false, canAnswer: true, message: 'Retry' });
assert.ok(el('options-container').children.every(button => !button.classList.contains('selected')), 'rejected optimistic choice must be cleared');
assert.ok(el('options-container').children.every(button => !button.disabled));
el('options-container').children[0].onclick();
const result = { type: 'survival_round_result', correctAnswerIndex: 0, correctAnswerText: 'Zero', options: question.options, penalty: 5,
    playerStates, survivorsCount: 2, eliminatedThisRound: [] };
receive({ ...result, myAnswerResult: { participated: true, answer: 0, answerText: 'Zero', isCorrect: true, status: 'CORRECT', hpLost: 0 } });
assert.match(el('result-info').innerHTML, /Đáp án đúng:.*\[A\] Zero/);
assert.match(el('result-info').innerHTML, /Bạn đã chọn.*\[A\] Zero.*ĐÚNG/);
assert.doesNotMatch(el('result-info').innerHTML, /SAI|undefined/);
assert.equal(timers.size, 0, 'early result cancels question countdown');
const secondQuestion = { ...question, round: 2, text: 'Second question', options: ['Second A', 'Second B', 'Second C', 'Second D'] };
receive(secondQuestion);
assert.equal(el('result-info').innerHTML, '', 'new question must clear previous correct result');
el('options-container').children[0].onclick();
const secondResult = { ...result, round: 2, correctAnswerIndex: 2, correctAnswerText: 'Second C', options: secondQuestion.options,
    playerStates: [{ username: 'owner', hp: 95, score: 10, isAlive: true }, playerStates[1]],
    myAnswerResult: { participated: true, answer: 0, answerText: 'Second A', isCorrect: false, status: 'WRONG', hpLost: 5 } };
receive(secondResult);
assert.match(el('result-info').innerHTML, /Đáp án đúng:.*\[C\] Second C/);
assert.match(el('result-info').innerHTML, /Bạn đã chọn.*\[A\] Second A.*SAI/);
assert.doesNotMatch(el('result-info').innerHTML, /Zero|ĐÚNG!/);
assert.match(el('result-info').innerHTML, /trừ 5 HP/);
assert.equal(el('hud-hp-val').innerText, '95 / 100', 'wrong feedback agrees with HP deduction');

// Thiết bị bị lỗi audio vẫn phải cập nhật kết quả và gửi đáp án bình thường.
let updatedBeforeAudio = false;
fakeWindow.arenaSound.playDamage = () => {
    updatedBeforeAudio = el('result-info').innerHTML.includes('Second C') && el('result-info').innerHTML.includes('SAI!');
    throw new Error('Audio device unavailable');
};
receive(secondResult);
assert.ok(updatedBeforeAudio, 'wrong result must render before optional audio effect');
delete fakeWindow.arenaSound.playDamage;
receive({ ...question, round: 3, text: 'Third question', options: ['Third A', 'Third B', 'Third C', 'Third D'] });
const originalLockSound = fakeWindow.arenaSound.playLockAnswer;
fakeWindow.arenaSound.playLockAnswer = () => { throw new Error('Audio device unavailable'); };
const beforeThird = requests.length;
el('options-container').children[1].onclick();
assert.equal(requests.length, beforeThird + 1, 'broken lock sound cannot prevent submission');
assert.equal(requests.at(-1).answer, 1);
fakeWindow.arenaSound.playLockAnswer = originalLockSound;
const originalCorrectSound = fakeWindow.arenaSound.playCorrect;
fakeWindow.arenaSound.playCorrect = () => { throw new Error('Audio device unavailable'); };
receive({ ...result, round: 3, correctAnswerIndex: 1, correctAnswerText: 'Third B', options: ['Third A', 'Third B', 'Third C', 'Third D'],
    playerStates: [{ username: 'owner', hp: 95, score: 20, isAlive: true }, playerStates[1]],
    myAnswerResult: { participated: true, answer: 1, answerText: 'Third B', isCorrect: true, status: 'CORRECT', hpLost: 0 } });
assert.match(el('result-info').innerHTML, /Bạn đã chọn.*\[B\] Third B.*ĐÚNG/);
assert.match(el('result-info').innerHTML, /Đáp án đúng:.*\[B\] Third B/);
assert.doesNotMatch(el('result-info').innerHTML, /Second|SAI!/);
assert.equal(el('hud-hp-val').innerText, '95 / 100');
fakeWindow.arenaSound.playCorrect = originalCorrectSound;
receive({ ...result, myAnswerResult: { participated: true, answer: -1, isCorrect: false, status: 'UNANSWERED', hpLost: 5 } });
assert.match(el('result-info').innerHTML, /Bạn không trả lời/);
assert.doesNotMatch(el('result-info').innerHTML, /undefined|\[A\].*SAI/);
receive({ ...result, myAnswerResult: { participated: false, status: 'SPECTATING' } });
assert.match(el('result-info').innerHTML, /đang xem trận đấu/);
assert.doesNotMatch(el('result-info').innerHTML, /SAI|Bạn không trả lời|undefined/);
receive({ ...result, playerAnswerResults: {} });
assert.doesNotMatch(el('result-info').innerHTML, /SAI|undefined/);

receive({ type: 'survival_game_paused', gameState: 'RESULT', remainingTime: 3.2 });
assert.equal(el('btn-survival-resume').style.display, 'inline-block');
el('btn-survival-delete').listeners.click();
assert.equal(requests.at(-1).type, 'close_survival_room');
assert.equal(requests.at(-1).roomCode, 'CUSTOM');
receive({ type: 'survival_room_closed', message: 'Closed' });
assert.ok(el('lobby-screen').classList.contains('active'));
assert.equal(el('survival-room-controls').style.display, 'none');
receive({ type: 'survival_room_info', roomCode: 'DTST', startingHp: 100, gameState: 'QUESTION', isHost: false });
assert.equal(el('btn-survival-delete').style.display, 'none');
assert.equal(el('btn-survival-pause').style.display, 'none');
assert.deepEqual(errors, [], 'message handlers should not swallow UI errors');
console.log('PASS: Survival pause/resume, answer locking, full correct/selected answer, correct/wrong/unanswered/spectator feedback, timer cleanup and owner deletion UI.');
console.log('PASS: Production sound script, consecutive correct/wrong/correct rounds, stale result clearing and audio failures without lost submissions or stale feedback.');
