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
    const patchEnd = source.indexOf('\npatchRtcPeerConnection();', patchStart);
    vm.runInContext(source.slice(patchStart, patchEnd), c);
    c.patchRtcPeerConnection();
    const pc = new c.RTCPeerConnection({iceServers: []});
    return {c, pc, calls, timers};
}

test('message traffic after Handshake is forwarded and input remains native', () => {
    const {pc, calls} = setup();
    const dc = pc.createDataChannel('message');
    dc.send('{"type":"ClientReady"}');
    const sent = calls.find(x => x[0] === 'onDataChannelSend');
    assert.equal(sent[1], 'message');
    assert.equal(Buffer.from(sent[3], 'base64').toString(), '{"type":"ClientReady"}');
    pc.createDataChannel('input').send('must-stay-native');
    pc.createDataChannel('unreliableinput').send('must-stay-native');
    assert.equal(calls.filter(x => x[0] === 'onDataChannelSend').length, 1);
});

test('incoming messages and channel-open listeners each fire once', () => {
    const {c, pc} = setup();
    const dc = pc.createDataChannel('message');
    let handler = 0, listener = 0, opened = 0;
    dc.onmessage = () => handler++;
    const onMessage = () => listener++;
    dc.addEventListener('message', onMessage);
    dc.addEventListener('message', onMessage);
    dc.addEventListener('open', () => opened++);
    c.handleDataChannelState('message', 'OPEN');
    c.handleDataChannelMessage('message', null, 'ClientReadyAck', false);
    assert.deepEqual([handler, listener, opened], [1, 1, 1]);
    dc.removeEventListener('message', onMessage);
    c.handleDataChannelMessage('message', null, 'AnotherAck', false);
    assert.deepEqual([handler, listener], [2, 1]);
});

test('peer listeners are not duplicated as on-property handlers', () => {
    const {c, pc} = setup();
    let ice = 0, connection = 0;
    pc.addEventListener('iceconnectionstatechange', () => ice++);
    pc.addEventListener('connectionstatechange', () => connection++);
    c.handleIceConnectionState('CONNECTED');
    assert.deepEqual([ice, connection], [1, 1]);
    assert.equal(pc.connectionState, 'connected');
});

test('remote description resolves only after native confirmation', async () => {
    const {c, pc} = setup();
    let resolved = false;
    const pending = pc.setRemoteDescription({type: 'answer', sdp: 'native-answer'}).then(() => resolved = true);
    await Promise.resolve();
    assert.equal(resolved, false);
    assert.equal(pc.remoteDescription, null);
    c.handleRemoteDescriptionSet();
    await pending;
    assert.equal(pc.remoteDescription.sdp, 'native-answer');
    assert.equal(pc.signalingState, 'stable');
});

test('an answer arriving before createAnswer is not lost', async () => {
    const {c, pc} = setup();
    const remote = pc.setRemoteDescription({type: 'offer', sdp: 'server-offer'});
    c.handleRemoteDescriptionSet();
    c.handleAnswer('early-native-answer');
    await remote;
    assert.equal((await pc.createAnswer()).sdp, 'early-native-answer');
});

test('native SDP failure rejects pending negotiation and cancels its timeout', async () => {
    const {c, pc, timers} = setup();
    const pending = pc.createOffer();
    const rejected = assert.rejects(pending, /create-offer/);
    c.handleSignalingError('create-offer');
    await rejected;
    assert.equal(timers.size, 0);
    assert.equal(Object.keys(c.bxNativePending).length, 0);
});

test('a missing native reply times out instead of loading indefinitely', async () => {
    const {pc, timers} = setup();
    const rejected = assert.rejects(pc.createOffer(), /timeout-offer/);
    const timer = [...timers.values()].find(t => t.ms === 30000);
    assert.ok(timer);
    timer.fn();
    await rejected;
});

test('successful offer settles once and clears its timer', async () => {
    const {c, pc, timers} = setup();
    const pending = pc.createOffer();
    c.handleOffer('real-native-sdp');
    c.handleOffer('late-duplicate');
    assert.equal((await pending).sdp, 'real-native-sdp');
    assert.equal(timers.size, 0);
});

test('close rejects negotiations, stops media and is idempotent', async () => {
    const {pc, calls, timers} = setup();
    let stopped = 0, audioClosed = 0;
    pc._auxStreams.push({getTracks: () => [{stop() {stopped++;}}]});
    pc._auxAudioContexts.push({close() {audioClosed++; return Promise.resolve();}});
    const rejected = assert.rejects(pc.createOffer(), /session-closed/);
    pc.close(); pc.close();
    await rejected;
    assert.deepEqual([stopped, audioClosed], [1, 1]);
    assert.equal(timers.size, 0);
    assert.equal(calls.filter(x => x[0] === 'onPeerConnectionClose').length, 1);
});

test('missing keepalive metadata is guarded, bounded and canceled on close', () => {
    const {c, pc, timers} = setup();
    pc.createDataChannel('message');
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    assert.equal(timers.size, 1);
    for (let i = 0; i < 120; i++) {
        const next = timers.entries().next().value;
        if (!next) break;
        timers.delete(next[0]); next[1].fn();
    }
    assert.equal(timers.size, 0);
    pc.close();
});

test('duplicate handshake acknowledgment does not start duplicate native keepalive', () => {
    const {c, pc, calls} = setup();
    c.BX_EXPOSED = {streamSession: {gsToken: 'test-only', sessionPath: 'sessions/test', domainName: 'https://example.test'}};
    pc.createDataChannel('message');
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    c.handleDataChannelMessage('message', null, 'HandshakeAck', false);
    assert.equal(calls.filter(x => x[0] === 'keepalive').length, 1);
});

test('reconfiguration uses existing native connection and patch is idempotent', () => {
    const {c, pc, calls} = setup();
    const constructor = c.RTCPeerConnection;
    c.patchRtcPeerConnection();
    assert.equal(c.RTCPeerConnection, constructor);
    pc.setConfiguration({iceServers: [{urls: 'stun:example.test'}]});
    assert.equal(calls.filter(x => x[0] === 'onPeerConnectionClose').length, 0);
    assert.equal(calls.filter(x => x[0] === 'onPeerConnectionConfig').length, 1);
    assert.equal(calls.filter(x => x[0] === 'onPeerConnectionConfigUpdated').length, 1);
});

test('binary views preserve byte offset and length', () => {
    const {c, pc, calls} = setup();
    c.testChannel = pc.createDataChannel('message');
    vm.runInContext('const bytes = new Uint8Array([1,2,3,4]); testChannel.send(bytes.subarray(1,3));', c);
    const sent = calls.find(x => x[0] === 'onDataChannelSend');
    assert.equal(JSON.stringify(sent[2]), '[2,3]');
});

test('720p device dimensions agree with the requested resolution', () => {
    const {c} = setup();
    const start = source.indexOf('function generateMsDeviceInfo(');
    const end = source.indexOf('\nconst BxExposed', start);
    vm.runInContext(source.slice(start, end), c);
    assert.equal(c.generateMsDeviceInfo('android', '720').dev.displayInfo.dimensions.widthInPixels, 1280);
    assert.equal(c.generateMsDeviceInfo('android', '720HQ').dev.displayInfo.dimensions.heightInPixels, 720);
    assert.equal(c.generateMsDeviceInfo('tizen', '1080HQ').dev.displayInfo.dimensions.heightInPixels, 1080);
});

test('native controller registration is requested only after sending control authorization', () => {
    const {pc, calls} = setup();
    const control = pc.createDataChannel('control');
    control.send(JSON.stringify({message: 'authorizationRequest', token: 'test-only'}));
    const send = calls.findIndex(x => x[0] === 'onDataChannelSend');
    const announce = calls.findIndex(x => x[0] === 'onControlAuthorizationSent');
    assert.ok(send >= 0 && announce > send);
    assert.equal(Buffer.from(calls[send][3], 'base64').toString(), '{"message":"authorizationRequest","token":"test-only"}');
});

test('hidden browser cannot remove native gamepad but normal control messages pass', () => {
    const {pc, calls} = setup();
    const control = pc.createDataChannel('control');
    control.send('{"message":"gamepadChanged","gamepadIndex":0,"wasAdded":false}');
    assert.equal(calls.filter(x => x[0] === 'onDataChannelSend').length, 0);
    control.send('{"message":"videoKeyframeRequested"}');
    assert.equal(calls.filter(x => x[0] === 'onDataChannelSend').length, 1);
    assert.equal(calls.filter(x => x[0] === 'onControlAuthorizationSent').length, 0);
});

test('duplicate open notification does not repeat SDK authorization', () => {
    const {c, pc, calls} = setup();
    const control = pc.createDataChannel('control');
    control.onopen = () => control.send('{"message":"authorizationRequest"}');
    c.handleDataChannelState('control', 'OPEN');
    c.handleDataChannelState('control', 'OPEN');
    assert.equal(calls.filter(x => x[0] === 'onControlAuthorizationSent').length, 1);
});

test('old control channel cannot register or remove a controller in the next session', () => {
    const {c, pc, calls} = setup();
    const old = pc.createDataChannel('control');
    new c.RTCPeerConnection({iceServers: []});
    const count = calls.length;
    old.send('{"message":"authorizationRequest"}');
    old.send('{"message":"gamepadChanged","wasAdded":false}');
    assert.equal(calls.length, count);
});

test('reliable input synchronization remains available alongside native motion', () => {
    const {pc, calls} = setup();
    pc.createDataChannel('reliableinput').send(new Uint8Array([0, 1, 2, 3]).subarray(1));
    pc.createDataChannel('unreliableinput').send(new Uint8Array([9]));
    const sent = calls.filter(x => x[0] === 'onDataChannelSend');
    assert.equal(sent.length, 1);
    assert.equal(sent[0][1], 'reliableinput');
    assert.deepEqual(Array.from(sent[0][2]), [1, 2, 3]);
    assert.equal(sent[0][4], true);
});
