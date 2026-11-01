import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const elements = new Map();
function node() {
    return { style: {}, innerText: '', innerHTML: '', textContent: '', children: [], value: '', listeners: {},
        classList: { add() {}, remove() {} }, addEventListener(name, action) { this.listeners[name] = action; },
        appendChild(child) { this.children.push(child); } };
}
for (const match of fs.readFileSync(new URL('../public/wheel.html', import.meta.url), 'utf8').matchAll(/id="([^"]+)"/g)) elements.set(match[1], node());
let connection;
const alerts = [], expirations = [];
class Socket {
    constructor() { connection = this; this.readyState = 1; }
    send() {} close() {}
}
const context = vm.createContext({
    document: {
        getElementById(id) { assert.ok(elements.has(id), `Unknown element ${id}`); return elements.get(id); },
        addEventListener() {}, createElement: node, querySelectorAll() { return []; }
    },
    WebSocket: Socket, location: { host: 'localhost:3000', protocol: 'http:' },
    window: { addEventListener() {}, location: { search: '' } }, console, URLSearchParams,
    fetch: async () => ({ json: async () => ({ loggedIn: true, user: { username: 'owner', isAdmin: false } }) }),
    alert: message => alerts.push(message), confirm: () => true,
    setTimeout(callback) { expirations.push(callback); }, setInterval() {}, clearInterval() {}
});
vm.runInContext(fs.readFileSync(new URL('../public/room_notices.js', import.meta.url), 'utf8'), context);
vm.runInContext(fs.readFileSync(new URL('../public/wheel.js', import.meta.url), 'utf8'), context);
await new Promise(resolve => setImmediate(resolve));
const receive = value => connection.onmessage({ data: JSON.stringify(value) });
receive({ type: 'wheel_room_info', roomCode: 'W', roomName: 'Wheel', hostUsername: 'owner', topic: 'ALL', timeLimit: 10 });
receive({ type: 'player_disconnected', roomCode: 'W', username: '<img src=x>', message: 'Người chơi [<img src=x>] đã mất kết nối.' });
const region = elements.get('room-notices');
assert.equal(region.children.length, 1);
assert.equal(region.children[0].textContent, 'Người chơi [<img src=x>] đã mất kết nối.');
assert.equal(region.children[0].innerHTML, '', 'username must be plain text, not executable markup');
receive({ type: 'player_disconnected', roomCode: 'OTHER', username: 'outside' });
assert.equal(region.children.length, 1);
assert.equal(alerts.length, 0, 'notice must not block gameplay with modal alerts');
assert.equal(expirations.length, 1);
console.log('PASS: Wheel room disconnect notice, room isolation, safe player names and nonblocking display.');
