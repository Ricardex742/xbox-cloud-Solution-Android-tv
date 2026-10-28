const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/index.js', 'utf8');

function setup() {
    const calls = [], timers = new Map();
    let nextTimer = 0;
    const c = {console: {log() {}, warn() {}, error() {}}, URL,
        location: {pathname: '/pt-BR/play', href: 'https://www.xbox.com/pt-BR/play', origin: 'https://www.xbox.com'},
        navigator: {}, localStorage: {getItem() {return null;}, setItem() {}},
        Event: class {constructor(type) {this.type = type;}},
        MessageEvent: class {constructor(type, options) {this.type = type; Object.assign(this, options);}},
        setTimeout(fn, ms) {const id = ++nextTimer; timers.set(id, {fn, ms}); return id;},
        clearTimeout(id) {timers.delete(id);},
        btoa(s) {return Buffer.from(s, 'binary').toString('base64');},
        atob(s) {return Buffer.from(s, 'base64').toString('binary');},
        getTargetResolution: () => '720', getTargetBitrate: () => 8,
        RTCPeerConnection: function OriginalPeer() {},
        AndroidBridge: new Proxy({}, {get: (_, name) => (...args) => calls.push([name, ...args])})};
    c.window = c;
    vm.createContext(c);
    vm.runInContext(source.slice(0, source.indexOf('const BypassServerIps')), c);
    const patchStart = source.indexOf('function patchRtcPeerConnection()');
    const patchEnd = source.indexOf('\n}', patchStart) + 2;
    vm.runInContext(source.slice(patchStart, patchEnd), c);
    c.patchRtcPeerConnection();
    const pc = new c.RTCPeerConnection({iceServers: []});
    return {c, pc, calls, timers};
}


test('native motion stays exclusive while reliable synchronization and session messages pass', () => {
    const {pc, calls} = setup();
    pc.createDataChannel('input').send('blocked');
    pc.createDataChannel('unreliableinput').send('blocked');
    pc.createDataChannel('reliableinput').send(new Uint8Array([7, 8, 9]).subarray(1));
    pc.createDataChannel('message').send('{"type":"ClientReady"}');
    const sent = calls.filter(x => x[0] === 'onDataChannelSend');
    assert.equal(sent.length, 2);
    assert.equal(sent[0][1], 'reliableinput');
    assert.deepEqual(Array.from(sent[0][2]), [8, 9]);
    assert.equal(Buffer.from(sent[1][3], 'base64').toString(), '{"type":"ClientReady"}');
});

test('browser cannot remove native controller and other control messages remain intact', () => {
    const {pc, calls} = setup();
    const dc = pc.createDataChannel('control');
    dc.send('{"message":"gamepadChanged","gamepadIndex":0,"wasAdded":false}');
    dc.send('{"message":"gamepadChanged","gamepadIndex":0,"wasAdded":true}');
    dc.send('{"message":"other","wasAdded":false}');
    const sent = calls.filter(x => x[0] === 'onDataChannelSend');
    assert.equal(sent.length, 1);
    assert.equal(Buffer.from(sent[0][3], 'base64').toString(), '{"message":"other","wasAdded":false}');
});

test('channel open and message listeners are delivered once, including property handlers', () => {
    const {c, pc} = setup();
    const dc = pc.createDataChannel('message');
    let opened = 0, property = 0, listener = 0;
    const callback = () => listener++;
    dc.addEventListener('message', callback); dc.addEventListener('message', callback);
    dc.onmessage = () => property++;
    dc.addEventListener('open', () => opened++);
    c.handleDataChannelState('message', 'OPEN');
    c.handleDataChannelMessage('message', null, 'ready', false);
    assert.deepEqual([opened, property, listener], [1, 1, 1]);
});

test('idle session remains alive and repeated handshakes do not duplicate keepalive polling', () => {
    const {c, pc, timers, calls} = setup();
    pc.createDataChannel('message');
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    assert.equal(timers.size, 1);
    c.BX_EXPOSED = {streamSession: {gsToken: 'test', sessionPath: 'sessions/test', domainName: 'https://example.test'}};
    const [id, timer] = timers.entries().next().value;
    timers.delete(id); timer.fn();
    assert.equal(calls.filter(x => x[0] === 'keepalive').length, 1);
    assert.equal(pc.signalingState, 'stable');
    pc.close(); pc.close();
    assert.equal(calls.filter(x => x[0] === 'onPeerConnectionClose').length, 1);
});

test('missing metadata polling is bounded and stops when session closes', () => {
    const {c, pc, timers} = setup();
    pc.createDataChannel('message');
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    for (let n = 0; n < 120 && timers.size; n++) {
        const [id, timer] = timers.entries().next().value;
        timers.delete(id); timer.fn();
    }
    assert.equal(timers.size, 0);
    const second = new c.RTCPeerConnection({iceServers: []});
    second.createDataChannel('message');
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    assert.equal(timers.size, 1);
    second.close();
    assert.equal(timers.size, 0);
});
