const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/index.js', 'utf8');

function setup(options = {}) {
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
    c.document = {body: null, readyState: 'loading'};
    c.XMLHttpRequest = class {
        constructor(){this.listeners={};this.responseType='';}
        open(method,url){this.url=url;}
        send(body){this.body=body;}
        addEventListener(type,callback){this.listeners[type]=callback;}
    };
    c.Request = Request; c.Response = Response;
    c.fetch = options.fetch || (async () => {throw new Error('unexpected fetch');});
    c.WebSocket = function OriginalSocket() {
        this.sent=[]; this.listeners={};
        this.send=data=>this.sent.push(data);
        this.addEventListener=(type,handler)=>this.listeners[type]=handler;
    };
    c.window = c; c.top = c;
    vm.createContext(c);
    vm.runInContext(source, c);
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
    vm.runInContext(source, c);
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

test('all late negotiation methods from an old peer are rejected before touching the new session', async () => {
    const {c,pc:old,calls}=setup();
    const current = new c.RTCPeerConnection({iceServers: []});
    const count=calls.length;
    assert.throws(()=>old.createDataChannel('control'), /closed/);
    assert.throws(()=>old.addTransceiver('video'), /closed/);
    await assert.rejects(old.createOffer(), /closed/);
    await assert.rejects(old.createAnswer(), /closed/);
    await assert.rejects(old.setRemoteDescription({type:'answer',sdp:'old'}), /closed/);
    await assert.rejects(old.setLocalDescription({type:'offer',sdp:'old'}), /closed/);
    await assert.rejects(old.addIceCandidate({candidate:'old'}), /closed/);
    assert.equal(calls.length,count);
    assert.equal(current.signalingState,'stable');
});

test('SDK profile and stereo munging cannot diverge from the SDP committed natively', async () => {
    const {c,pc}=setup();
    const native='m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fmtp:111 stereo=1\r\nm=video 9 UDP/TLS/RTP/SAVPF 98 96\r\na=fmtp:98 profile-level-id=64001f\r\na=fmtp:96 profile-level-id=42e01f\r\n';
    const promise=pc.createOffer();c.handleOffer(native);const offer=await promise;
    offer.sdp=offer.sdp.replace('98 96','96 98').replace('stereo=1','stereo=0');
    await pc.setLocalDescription(offer);
    assert.equal(pc.localDescription.sdp,native);
    const ws=JSON.parse(c.bxCanonicalOfferMessage(JSON.stringify({messageType:'offer',requestId:7,sdp:offer.sdp,configuration:{input:{maxVersion:10}}})));
    assert.equal(ws.sdp,native);assert.equal(ws.requestId,7);assert.equal(ws.configuration.input.maxVersion,10);
    const http=JSON.parse(c.bxCanonicalOfferMessage(JSON.stringify({exchangeRequest:JSON.stringify(ws)})));
    assert.equal(JSON.parse(http.exchangeRequest).sdp,native);
    assert.equal(c.bxCanonicalOfferMessage('{"messageType":"iceCandidate","candidate":"unchanged"}'),'{"messageType":"iceCandidate","candidate":"unchanged"}');
});

test('server-selected input versions arrive before native registration and old answers are ignored', () => {
    const {c,pc,calls}=setup();
    c.bxCaptureInputVersions({sdp:'answer',input:7,unreliableinput:9,reliableinput:9},pc);
    assert.deepEqual(calls.find(x=>x[0]==='onInputVersions'),['onInputVersions',7,9,9]);
    const count=calls.length;const current=new c.RTCPeerConnection({iceServers:[]});const after=calls.length;
    c.bxCaptureInputVersions({sdp:'old',input:10,unreliableinput:10,reliableinput:10},pc);
    assert.equal(calls.length,after);
    c.bxCaptureInputVersions({exchangeResponse:JSON.stringify({sdp:'new',input:8})},current);
    assert.deepEqual(calls.at(-1),['onInputVersions',8,0,0]);
});

test('auxiliary audio is disabled and suspended while native playback remains independent', () => {
    const {c,pc}=setup();let suspended=0;const track={enabled:true};
    c.AudioContext=class {createMediaStreamDestination(){return {stream:{getAudioTracks:()=>[track]}};} suspend(){suspended++;return Promise.resolve();}};
    c.simulateOnTrack('audio','aux');
    assert.equal(track.enabled,false);assert.equal(suspended,1);assert.equal(pc._auxStreams.length,1);
});

test('WebSocket sends the canonical SDP rather than its original unmodified arguments', async () => {
    const {c,pc,calls}=setup();const ws=new c.WebSocket('wss://example.invalid');
    const promise=pc.createOffer();c.handleOffer('native-high-and-stereo');await promise;
    ws.send(JSON.stringify({messageType:'offer',sdp:'sdk-baseline',configuration:{input:{maxVersion:10}}}));
    assert.equal(JSON.parse(ws.sent[0]).sdp,'native-high-and-stereo');
    ws.listeners.message({data:JSON.stringify({messageType:'answer',sdp:'server',input:10,unreliableinput:9,reliableinput:9})});
    assert.deepEqual(calls.at(-1),['onInputVersions',10,9,9]);
    new c.RTCPeerConnection({});const count=calls.length;
    ws.listeners.message({data:JSON.stringify({messageType:'answer',sdp:'late',input:7})});
    assert.equal(calls.length,count);
});

test('HTTP signaling canonicalizes wrapped offers and captures selected input version', async () => {
    let sent;
    const {c,pc,calls}=setup({fetch:async (request)=>{
        sent=await request.clone().json();
        return new Response(JSON.stringify({exchangeResponse:JSON.stringify({sdp:'answer',input:7,unreliableinput:9,reliableinput:9})}),{status:200});
    }});
    const pending=pc.createOffer();c.handleOffer('native-http');await pending;
    await c.fetch('https://example.xboxlive.com/v5/sessions/cloud/test/sdp',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({exchangeRequest:JSON.stringify({messageType:'offer',sdp:'mutated',configuration:{input:{maxVersion:10}}})})
    });
    assert.equal(JSON.parse(sent.exchangeRequest).sdp,'native-http');
    assert.deepEqual(calls.at(-1),['onInputVersions',7,9,9]);
});

test('XHR signaling preserves canonical offers and observes selected input versions', async () => {
    const {c,pc,calls}=setup();const pending=pc.createOffer();c.handleOffer('native-xhr');await pending;
    const xhr=new c.XMLHttpRequest();xhr.open('POST','https://example.invalid/sdp');
    xhr.send(JSON.stringify({messageType:'offer',sdp:'sdk-mutated',configuration:{}}));
    assert.equal(JSON.parse(xhr.body).sdp,'native-xhr');
    xhr.responseText=JSON.stringify({sdp:'server',input:10,unreliableinput:9,reliableinput:9});xhr.listeners.load();
    assert.deepEqual(calls.at(-1),['onInputVersions',10,9,9]);
    const other=new c.XMLHttpRequest();other.open('POST','https://example.invalid/settings');other.send('unmodified');
    assert.equal(other.body,'unmodified');
});

test('canonicalizing a Request preserves RequestInit authorization and content type', async () => {
    let auth,body;
    const {c,pc}=setup({fetch:async request=>{
        auth=request.headers.get('Authorization');body=await request.json();
        return new Response(JSON.stringify({sdp:'answer',input:10}));
    }});
    const pending=pc.createOffer();c.handleOffer('native-with-headers');await pending;
    const request=new Request('https://example.xboxlive.com/session/sdp',{method:'POST',body:'{}'});
    await c.fetch(request,{headers:{Authorization:'Bearer unit-test-placeholder','Content-Type':'application/json'},
        body:JSON.stringify({messageType:'offer',sdp:'changed'})});
    assert.equal(auth,'Bearer unit-test-placeholder');assert.equal(body.sdp,'native-with-headers');
});
