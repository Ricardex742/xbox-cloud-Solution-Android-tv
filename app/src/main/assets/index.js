(function cloudxDocument() {
    if (window.__cloudxDocumentInstalled || window.top !== window) return;
    if (!/\/play(?:\/|$)/i.test(window.location.pathname)) return;
    window.__cloudxDocumentInstalled = true;
(function forcePtBrLocale() {
    try {
        if (window.location && window.location.pathname && /^\/(fr-fr|en-us|es-es|fr)\b/i.test(window.location.pathname)) {
            var newUrl = window.location.href.replace(/\/(fr-fr|en-us|es-es|fr)(?=\/|$)/i, '/pt-BR');
            window.location.replace(newUrl);
        }
    } catch (e) {}
})();
"use strict";

// Bridge for Native Android WebRTC
window.handleAnswer = function(sdp) {
    console.log("[BxC] handleAnswer from Java, sdp length: " + (sdp ? sdp.length : 0));
    if (window.pendingAnswerResolver) {
        window.pendingAnswerResolver({ type: 'answer', sdp: sdp });
        window.pendingAnswerResolver = null;
    } else {
        console.warn("[BxC] No pendingAnswerResolver found");
    }
};

window.handleOffer = function(sdp) {
    console.log("[BxC] handleOffer from Java, sdp length: " + (sdp ? sdp.length : 0));
    if (window.pendingOfferResolver) {
        window.pendingOfferResolver({ type: 'offer', sdp: sdp });
        window.pendingOfferResolver = null;
    } else {
        console.warn("[BxC] No pendingOfferResolver found");
    }
};

window.bxKeepAliveTimer = null;
window.bxKeepAliveStarted = false;
window.bxStartKeepAlive = function() {
    if (window.bxKeepAliveStarted) return;
    window.bxKeepAliveStarted = true;
    let attempts = 0;
    function start() {
        window.bxKeepAliveTimer = null;
        if (!window.peer || window.peer.signalingState === 'closed') return;
        const session = window.BX_EXPOSED && window.BX_EXPOSED.streamSession;
        if (session && session.gsToken && session.sessionPath && session.domainName && window.AndroidBridge) {
            window.AndroidBridge.keepalive(session.domainName + '/' + session.sessionPath + '/keepalive', session.gsToken);
            return;
        }
        if (++attempts < 120) window.bxKeepAliveTimer = setTimeout(start, 250);
        else console.warn('[BxC] Native keepalive metadata unavailable; retaining browser session');
    }
    start();
};

window.handleIceCandidate = function(sdpMid, sdpMLineIndex, sdp) {
    console.log("[BxC] handleIceCandidate from Java: " + sdpMid + " sdp length: " + (sdp ? sdp.length : 0));
    if (window.peer && window.peer.onicecandidate) {
        window.peer.onicecandidate({
            candidate: {
                candidate: sdp,
                sdpMid: sdpMid,
                sdpMLineIndex: sdpMLineIndex
            }
        });
    } else {
        console.warn("[BxC] handleIceCandidate: peer or onicecandidate missing");
    }
};

window.handleDataChannelMessage = function(label, binaryData, textData, isBinary) {
    const dc = window.proxyDataChannels && window.proxyDataChannels[label];
    if (dc) {
        let data;
        if (isBinary) {
            data = window.bxToArrayBuffer(binaryData);
        } else {
            data = textData;
        }

        if (label === 'message' && typeof data === 'string' && data.includes('HandshakeAck')) {
            window.bxStartKeepAlive();
        }

        const event = new MessageEvent('message', {
            data: data,
            origin: window.location.origin
        });
        dc.dispatchEvent(event);
    }
};

window.handleDataChannelState = function(label, state) {
    console.log("[BxC] handleDataChannelState: " + label + " -> " + state);
    const dc = window.proxyDataChannels && window.proxyDataChannels[label];
    if (dc) {
        if (dc.readyState === state.toLowerCase()) return;
        dc.readyState = state.toLowerCase();
        if (dc.readyState === 'open') dc.dispatchEvent(new Event('open'));
        if (dc.readyState === 'closed') dc.dispatchEvent(new Event('close'));
    }
};

window.handleIceConnectionState = function(state) {
    console.log("[BxC] handleIceConnectionState: " + state);
    if (window.peer) {
        window.peer.iceConnectionState = state.toLowerCase();
        if (window.peer.oniceconnectionstatechange) window.peer.oniceconnectionstatechange();
        if (window.peer._listeners && window.peer._listeners['iceconnectionstatechange']) {
            window.peer._listeners['iceconnectionstatechange'].forEach(l => l());
        }
    }
};

window.setControllerSettings = function(deadzone, sensitivity) {
    setPref("controller.deadzone", deadzone);
    setPref("controller.sensitivity", sensitivity);
};

window.setUseUnreliableInput = function(enabled) {
    window.BX_USE_UNRELIABLE_INPUT = enabled;
    BxLogger.info("Settings", "Updated UseUnreliableInput to:", enabled);
};

window.setPref = function lsSet(key, value) {
    localStorage.setItem(key, value);
};

window.getPref = function lsget(key) {
    return localStorage.getItem(key);
};

const BypassServerIps = {
    us: "143.244.47.65",
    br: "169.150.198.66",
    kr: "121.125.60.151",
    jp: "138.199.21.239",
    pl: "45.134.212.66",
    es: "80.58.61.250",
    uk: "62.24.134.1",
    fr: "212.27.40.240"
};

const ServerRegionMap = {
    us: "westus",
    br: "brazilsouth",
    kr: "koreacentral",
    jp: "japaneast",
    pl: "swedencentral",
    es: "spaincentral",
    uk: "uksouth",
    fr: "francecentral"
};

const BxLogger = {
    info: (tag, ...args) => console.log("%c[BxC]", "color:#008746;font-weight:bold;", tag, "//", ...args),
    error: (tag, ...args) => console.error("%c[BxC]", "color:#c10404;font-weight:bold;", tag, "//", ...args)
};

const NATIVE_FETCH = window.fetch;

function deepClone(obj) {
    if (!obj) return {};
    if ("structuredClone" in window) return structuredClone(obj);
    return JSON.parse(JSON.stringify(obj));
}

function getTargetRegion() {
    // Padrao configurado para Brasil (br -> brazilsouth)
    return window.BX_TARGET_REGION || localStorage.getItem('BX_TARGET_REGION') || 'br';
}

function getTargetResolution() {
    // Padrao configurado para 1080
    return window.BX_TARGET_RES || localStorage.getItem('BX_TARGET_RES') || '1080HQ';
}

function getTargetBitrate() {
    const value = window.BX_TARGET_BITRATE ?? localStorage.getItem('BX_TARGET_BITRATE') ?? 20;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.max(0, Math.min(20, parsed)) : 20;
}

function getOsNameFromResolution(resolution) {
    switch (resolution) {
        case "1080HQ":
        case "1440":
            return "tizen";
        case "1080":
        case "1080p":
            return "windows";
        case "720":
        case "720p":
        case "720HQ":
            return "android";
        default:
            return "windows";
    }
}

function generateMsDeviceInfo(osName, resolution) {
    const isQhd = (resolution === "1440");
    return {
        appInfo: {
            env: {
                clientAppId: window.location.host,
                clientAppType: "browser",
                clientAppVersion: "26.1.97",
                clientSdkVersion: "10.3.7",
                httpEnvironment: "prod",
                sdkInstallId: ""
            }
        },
        dev: {
            os: { name: osName, ver: "22631.2715", platform: "desktop" },
            hw: { make: "Microsoft", model: "unknown", sdktype: "web" },
            browser: { browserName: "chrome", browserVersion: "140.0.3485.54" },
            displayInfo: {
                dimensions: {
                    widthInPixels: isQhd ? 2560 : 1920,
                    heightInPixels: isQhd ? 1440 : 1080
                },
                pixelDensity: { dpiX: 1, dpiY: 1 }
            }
        }
    };
}

const BxExposed = {
    modifyPreloadedState: (state) => {
        try {
            state = deepClone(state);
            if (state.xcloud && state.xcloud.authentication) {
                const xCloud = state.xcloud.authentication.authStatusByStrategy.XCloud;
                if (xCloud && xCloud.type === 3 && xCloud.error.type === "UnsupportedMarketError") {
                    BxLogger.info("PreloadState", "Unsupported region detected, redirecting to pt-BR");
                    window.stop();
                    window.location.href = "https://www.xbox.com/pt-BR/play";
                }
            }
        } catch (e) {
            BxLogger.error("PreloadState", e);
        }
        return state;
    },

    modifyTitleInfo: (titleInfo) => {
        titleInfo = deepClone(titleInfo);
        if (titleInfo && titleInfo.details) {
            let supportedInputTypes = titleInfo.details.supportedInputTypes || [];
            if (!supportedInputTypes.includes("Controller")) {
                supportedInputTypes.push("Controller");
            }
            titleInfo.details.supportedInputTypes = supportedInputTypes;
            BxLogger.info("TitleInfo", "Modified title info for:", titleInfo.titleId);
        }
        return titleInfo;
    }
};

window.BX_EXPOSED = BxExposed;
window.BX_EXPOSED.streamSession = {
    gsToken: '',
    sessionPath: '',
    domainName: ''
};

class XcloudInterceptor {
    static async handleLogin(request, init) {
        const region = getTargetRegion();
        const ip = BypassServerIps[region];

        // Se a regiao for 'br', usa a rota direta brasileira sem cabecalho IP falso
        if (ip && region !== 'br') {
            BxLogger.info("XcloudInterceptor", "Using bypass IP for login:", region, "->", ip);
            request.headers.set("X-Forwarded-For", ip);
        }

        let response;
        try {
            response = await NATIVE_FETCH(request, init);
        } catch (e) {
            BxLogger.error("XcloudInterceptor", "Login interception failed", e);
            throw e;
        }

        if (response.status === 200) {
            const obj = await response.clone().json();
            const targetServerRegion = ServerRegionMap[region];

            if (targetServerRegion && obj.offeringSettings && obj.offeringSettings.regions) {
                BxLogger.info("XcloudInterceptor", "Forcing streaming region to:", targetServerRegion);
                const filteredRegions = obj.offeringSettings.regions.filter(r => r.name.toLowerCase().includes(targetServerRegion));
                if (filteredRegions.length > 0) {
                    filteredRegions[0].isDefault = true;
                    obj.offeringSettings.regions = filteredRegions;
                }
            }

            response.json = () => Promise.resolve(obj);
        }

        return response;
    }

    static async handlePlay(request, init) {
        const res = getTargetResolution();
        const osName = getOsNameFromResolution(res);
        const deviceInfo = generateMsDeviceInfo(osName, res);

        BxLogger.info("XcloudInterceptor", "Patching play request for resolution:", res, "-> OS:", osName);

        request.headers.set("x-ms-device-info", JSON.stringify(deviceInfo));

        let finalRequest = request;
        try {
            const body = await request.clone().json();
            if (body.settings) {
                body.settings.osName = osName;
            }
            finalRequest = new Request(request, {
                body: JSON.stringify(body)
            });
        } catch (e) {
            BxLogger.error("XcloudInterceptor", "Play request body modification failed", e);
        }

        const response = await NATIVE_FETCH(finalRequest, init);

        try {
            if (response.status >= 200 && response.status < 300) {
                const respBody = await response.clone().json();
                if (respBody.sessionPath) {
                    window.BX_EXPOSED.streamSession.sessionPath = respBody.sessionPath;
                }
                const url = typeof request === "string" ? request : request.url;
                window.BX_EXPOSED.streamSession.domainName = new URL(url).origin;
                BxLogger.info("streamSession", "Session metadata captured");
            }
        } catch (e) {
            BxLogger.error("XcloudInterceptor", "Session info capture failed", e);
        }

        return response;
    }

    static async handleConfiguration(request, init) {
        const authHeader = request.headers.get("Authorization");
        if (authHeader && authHeader.startsWith("Bearer ")) {
            window.BX_EXPOSED.streamSession.gsToken = authHeader.substring(7);
            BxLogger.info("streamSession", "Captured gsToken from configuration request headers");
        }

        let response = await NATIVE_FETCH(request, init);
        let text = await response.clone().text();
        if (!text.length) return response;

        try {
            let obj = JSON.parse(text);
            let overrides = JSON.parse(obj.clientStreamingConfigOverrides || "{}") || {};

            overrides.inputConfiguration = overrides.inputConfiguration || {};
            const useUnreliable = window.BX_USE_UNRELIABLE_INPUT !== false;
            overrides.inputConfiguration.useUnreliableInput = useUnreliable;
            overrides.inputConfiguration.enableVibration = true;

            obj.clientStreamingConfigOverrides = JSON.stringify(overrides);

            BxLogger.info("XcloudInterceptor", "Forcing input configuration - unreliableinput:", useUnreliable);

            return new Response(JSON.stringify(obj), {
                status: response.status,
                statusText: response.statusText,
                headers: response.headers
            });
        } catch (e) {
            BxLogger.error("XcloudInterceptor", "Configuration modification failed", e);
        }

        return response;
    }

    static async handle(request, init) {
        const url = typeof request === "string" ? request : request.url;
        if (url.endsWith("/v2/login/user")) {
            return XcloudInterceptor.handleLogin(request, init);
        } else if (url.endsWith("/sessions/cloud/play")) {
            return XcloudInterceptor.handlePlay(request, init);
        } else if (url.endsWith("/configuration")) {
            return XcloudInterceptor.handleConfiguration(request, init);
        }
        return NATIVE_FETCH(request, init);
    }
}

function interceptHttpRequests() {
    window.fetch = async (request, init) => {
        let url = typeof request === "string" ? request : request.url;

        if (url.includes("xboxlive.com") || url.includes("gssv-play-prod.xboxlive.com")) {
            if (typeof request === "string") {
                request = new Request(request, init);
            }
            return XcloudInterceptor.handle(request, init);
        }

        return NATIVE_FETCH(request, init);
    };
}

window._lastFullConfig = null;

function patchWebSocket() {
    const OriginalWebSocket = window.WebSocket;
    window.WebSocket = function(url, protocols) {
        const ws = new OriginalWebSocket(url, protocols);
        const originalSend = ws.send;

        ws.send = function(data) {
            if (typeof data === 'string') {
                try {
                    const msg = JSON.parse(data);
                    if (msg.messageType === 'offer' && msg.configuration) {
                        if (msg.configuration.isMediaStreamsChatRenegotiation && window._lastFullConfig) {
                            console.log("[BxC] Sparse renegotiation detected, merging with cached config");
                            msg.configuration = Object.assign({}, window._lastFullConfig, msg.configuration);
                            data = JSON.stringify(msg);
                        } else {
                            window._lastFullConfig = msg.configuration;
                        }
                    }
                } catch (e) {}
            }
            return originalSend.apply(this, arguments);
        };

        return ws;
    };
    window.WebSocket.prototype = OriginalWebSocket.prototype;
    Object.assign(window.WebSocket, OriginalWebSocket);
}

class Patcher {
    static init() {
        const nativeBind = Function.prototype.bind;
        Function.prototype.bind = function() {
            if (this.name.length <= 2 && arguments.length === 2 && arguments[0] === null && typeof arguments[1] === "function") {
                const orgFunc = this;
                const newFunc = (a, item) => {
                    Patcher.checkChunks(item);
                    orgFunc(a, item);
                };
                Function.prototype.bind = nativeBind;
                BxLogger.info("Patcher", "Webpack hook established");
                return nativeBind.apply(newFunc, arguments);
            }
            return nativeBind.apply(this, arguments);
        };
    }

    static checkChunks(item) {
        const chunkData = item[1];
        if (!chunkData) return;

        for (const chunkId in chunkData) {
            let funcStr = chunkData[chunkId].toString();
            let modified = false;

            if (funcStr.includes("=window.__PRELOADED_STATE__;")) {
                funcStr = funcStr.replace("=window.__PRELOADED_STATE__;", "=window.BX_EXPOSED.modifyPreloadedState(window.__PRELOADED_STATE__);");
                modified = true;
            }

            if (funcStr.includes("async cloudConnect")) {
                const searchIdx = funcStr.indexOf("async cloudConnect");
                const bracketIndex = funcStr.indexOf("{", searchIdx);
                const paramsMatch = funcStr.substring(searchIdx, bracketIndex).match(/\(([^)]+)\)/);
                if (paramsMatch) {
                    const titleInfoVar = paramsMatch[1].split(",")[0].trim();
                    const injection = `\n${titleInfoVar} = window.BX_EXPOSED.modifyTitleInfo(${titleInfoVar});\n`;
                    funcStr = funcStr.substring(0, bracketIndex + 1) + injection + funcStr.substring(bracketIndex + 1);
                    modified = true;
                }
            }

            if (funcStr.includes("},this.onFocusChanged=")) {
                const searchIdx = funcStr.indexOf("},this.onFocusChanged=");
                const bracketIndex = funcStr.indexOf("=>{", searchIdx) + 3;
                let start = searchIdx - 1;
                while (funcStr[start] && (/[a-zA-Z0-9_$]/.test(funcStr[start]))) start--;
                const varName = funcStr.substring(start + 1, searchIdx);
                if (varName) {
                    funcStr = funcStr.substring(0, bracketIndex) + `try { ${varName} = "focus"; } catch (e) {}` + funcStr.substring(bracketIndex);
                    modified = true;
                }
            }

            if (modified) {
                try {
                    chunkData[chunkId] = eval("(function " + funcStr.replace(/^\d+/, "") + ")");
                    BxLogger.info("Patcher", `Successfully patched chunk: ${chunkId}`);
                } catch (e) {
                    BxLogger.error("Patcher", "Failed to eval patched chunk", e);
                }
            }
        }
    }
}

function patchRtcPeerConnection() {
    if (window.__cloudxRtcInstalled) return;
    window.__cloudxRtcInstalled = true;
    // Keep catalog navigation working. Only a live native session owns game input.
    const browserGamepads = navigator.getGamepads && navigator.getGamepads.bind(navigator);
    try {
        navigator.getGamepads = function() {
            return window.peer && window.peer.signalingState !== 'closed'
                ? [] : (browserGamepads ? browserGamepads() : []);
        };
    } catch (e) {}
    let OrgRTCPeerConnection = window.RTCPeerConnection;

    window.handleIceCandidate = function(sdpMid, sdpMLineIndex, candidateStr) {
        if (!window.peer) return;
        let candidateObj = candidateStr ? { sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex, candidate: candidateStr } : null;
        let event = new Event('icecandidate');
        event.candidate = candidateObj;

        if (window.peer.onicecandidate) window.peer.onicecandidate(event);
        if (window.peer._listeners && window.peer._listeners['icecandidate']) {
            window.peer._listeners['icecandidate'].forEach(l => l(event));
        }
    };

    window.bxToBase64 = function(buffer) {
        let binary = '';
        let bytes = new Uint8Array(buffer);
        for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
        return window.btoa(binary);
    };

    window.bxToArrayBuffer = function(base64) {
        let binary = window.atob(base64);
        let bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes.buffer;
    };

    window.simulateOnTrack = function(kind, id) {
        if (!window.peer || window.peer.signalingState === 'closed') return;
        console.log("[BxC] Simulating lightweight ontrack for " + kind);

        let stream;
        let track;

        if (kind === 'video') {
            // Minimal placeholder track; the native surface renders the real video.
            const canvas = document.createElement('canvas');
            canvas.width = 2;
            canvas.height = 2;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = 'black';
            ctx.fillRect(0, 0, 2, 2);

            stream = canvas.captureStream(1);
            track = stream.getVideoTracks()[0];
        } else {
            const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            window.peer._auxAudioContexts.push(audioCtx);
            const destination = audioCtx.createMediaStreamDestination();
            stream = destination.stream;
            track = stream.getAudioTracks()[0];
        }

        window.peer._auxStreams.push(stream);
        let event = new Event('track');
        event.track = track;
        event.streams = [stream];
        event.transceiver = { receiver: { track: track } };

        if (window.peer) {
            if (window.peer.ontrack) window.peer.ontrack(event);
            if (window.peer._listeners && window.peer._listeners['track']) {
                window.peer._listeners['track'].forEach(l => l(event));
            }
        }
    };

    window.filterSdp = function(sdp) {
        let lines = sdp.split('\r\n');
        let newLines = [];
        let skipExtmap = [
            'urn:ietf:params:rtp-hdrext:toffset',
            'urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id',
            'urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id',
            'http://www.webrtc.org/experiments/rtp-hdrext/video-layers-allocation00',
            'http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01'
        ];

        for (let line of lines) {
            let skip = false;
            if (line.startsWith('a=extmap:')) {
                for (let ext of skipExtmap) {
                    if (line.includes(ext)) {
                        skip = true;
                        break;
                    }
                }
            }
            if (!skip) newLines.push(line);
        }
        return newLines.join('\r\n');
    };

    window.RTCPeerConnection = function(config) {
        if (window.peer && window.peer.signalingState !== 'closed') window.peer.close();
        console.log("[BxC] RTCPeerConnection constructor called");

        if (window.AndroidBridge) {
            window.AndroidBridge.onPeerConnectionConfig(JSON.stringify(config || {}));
        }

        clearTimeout(window.bxKeepAliveTimer);
        window.bxKeepAliveStarted = false;
        window.proxyDataChannels = {};
        const pc = {
            config: config,
            localDescription: null,
            remoteDescription: null,
            connectionState: 'new',
            iceConnectionState: 'new',
            signalingState: 'stable',
            _listeners: {},
            _transceivers: [],
            _auxStreams: [],
            _auxAudioContexts: [],

            createDataChannel: function(label, options) {
                console.log("[BxC] Proxy createDataChannel: " + label);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onDataChannelCreate(label);
                }

                const dc = {
                    label: label,
                    id: options ? options.id : null,
                    ordered: options ? options.ordered : true,
                    protocol: options ? (options.protocol || "") : "",
                    readyState: 'connecting',
                    binaryType: 'arraybuffer',
                    onmessage: null,
                    onopen: null,
                    onclose: null,

                    send: function(data) {
                        if (pc.signalingState === 'closed' || window.peer !== pc) return;
                        if (label === 'input' || label === 'unreliableinput') return;
                        if (window.AndroidBridge) {
                            let isBinary = typeof data !== 'string';
                            if (isBinary) {
                                window.AndroidBridge.onDataChannelSend(label, (ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data)), null, true);
                                return;
                            } else {
                                let authorizationSent = false;
                                if (label === 'control') {
                                    try {
                                        // Native code owns the virtual controller. The hidden browser
                                        // sees no physical gamepads and must not remove that slot.
                                        const message = JSON.parse(data).message;
                                        if (message === 'gamepadChanged') return;
                                        authorizationSent = message === 'authorizationRequest';
                                    } catch (e) {}
                                }
                                if (label === 'control' && data.includes('resolutionAlias')) {
                                    try {
                                        let overridesData = JSON.parse(data);
                                        let targetRes = getTargetResolution();
                                        if (targetRes === "720" || targetRes === "720HQ") {
                                            overridesData.resolutionAlias = "720p";
                                        } else if (targetRes === "1080" || targetRes === "1080HQ") {
                                            overridesData.resolutionAlias = "1080p";
                                        } else if (targetRes === "1440") {
                                            overridesData.resolutionAlias = "1440p";
                                        }
                                        data = JSON.stringify(overridesData);
                                    } catch (e) {}
                                }
                                let base64 = window.btoa(unescape(encodeURIComponent(data)));
                                window.AndroidBridge.onDataChannelSend(label, null, base64, false);
                                // control is ordered: enqueue native gamepad registration after authorization.
                                if (authorizationSent && window.AndroidBridge.onControlAuthorizationSent) {
                                    window.AndroidBridge.onControlAuthorizationSent();
                                }
                            }
                        }
                    },
                    close: function() {
                        console.log("[BxC] Proxy DataChannel close: " + label);
                    },
                    addEventListener: function(type, listener) {
                        if (!this._listeners) this._listeners = {};
                        if (!this._listeners[type]) this._listeners[type] = [];
                        if (!this._listeners[type].includes(listener)) this._listeners[type].push(listener);
                    },
                    removeEventListener: function(type, listener) {
                        if (this._listeners && this._listeners[type]) {
                            this._listeners[type] = this._listeners[type].filter(l => l !== listener);
                        }
                    },
                    dispatchEvent: function(event) {
                        const handler = this['on' + event.type];
                        if (handler) handler.call(this, event);
                        if (this._listeners && this._listeners[event.type]) {
                            this._listeners[event.type].slice().forEach(l => l.call(this, event));
                        }
                        return true;
                    }
                };

                if (!window.proxyDataChannels) window.proxyDataChannels = {};
                window.proxyDataChannels[label] = dc;

                return dc;
            },

            addTransceiver: function(trackOrKind, init) {
                if (window.AndroidBridge) {
                    window.AndroidBridge.onAddTransceiver(trackOrKind, init ? (init.direction || "recvonly") : "recvonly");
                }
                const transceiver = {
                    mid: null,
                    direction: init ? (init.direction || 'sendrecv') : 'sendrecv',
                    sender: { track: (typeof trackOrKind !== 'string' ? trackOrKind : null) },
                    receiver: { track: { kind: (typeof trackOrKind === 'string' ? trackOrKind : trackOrKind.kind), stop: () => {} } },
                    stop: () => {},
                    setCodecPreferences: function(codecs) {}
                };
                this._transceivers.push(transceiver);
                return transceiver;
            },

            addTrack: function(track, ...streams) {
                return { track: track };
            },

            getTransceivers: function() { return this._transceivers || []; },
            getReceivers: function() { return (this._transceivers || []).map(t => t.receiver); },
            getSenders: function() { return (this._transceivers || []).map(t => t.sender); },

            setRemoteDescription: function(description) {
                this.remoteDescription = description;
                if (description && window.AndroidBridge) {
                    if (description.type === 'offer') {
                        window.AndroidBridge.onOfferReceived(description.sdp);
                    } else if (description.type === 'answer') {
                        window.AndroidBridge.onAnswerReceived(description.sdp);
                    }
                }
                return Promise.resolve();
            },

            createAnswer: function() {
                return new Promise((resolve) => {
                    window.pendingAnswerResolver = (answer) => {
                        this.localDescription = answer;
                        resolve(answer);
                    };
                });
            },

            createOffer: function() {
                return new Promise((resolve) => {
                    window.pendingOfferResolver = (offer) => {
                        this.localDescription = offer;
                        resolve(offer);
                    };
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onOfferRequested(getTargetBitrate());
                    }
                });
            },

            setLocalDescription: function(description) {
                this.localDescription = description;
                this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';

                let event = new Event('signalingstatechange');
                if (this.onsignalingstatechange) this.onsignalingstatechange(event);
                if (this._listeners && this._listeners['signalingstatechange']) {
                    this._listeners['signalingstatechange'].forEach(l => l(event));
                }

                return Promise.resolve();
            },

            setConfiguration: function(config) {
                this.config = config;
                if (this.signalingState === 'closed' || window.peer !== this) throw new Error('Peer connection closed');
                if (window.AndroidBridge && config) {
                    window.AndroidBridge.onPeerConnectionConfigurationUpdate(JSON.stringify(config));
                }
            },

            addIceCandidate: function(candidate) {
                if (candidate && window.AndroidBridge) {
                    window.AndroidBridge.onIceCandidateReceived(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate || candidate.sdp || "");
                }
                return Promise.resolve();
            },

            addEventListener: function(type, listener) {
                if (!this._listeners[type]) this._listeners[type] = [];
                if (!this._listeners[type].includes(listener)) this._listeners[type].push(listener);
            },

            getStats: function() {
                return Promise.resolve(new Map());
            },

            close: function() {
                if (this.signalingState === 'closed') return;
                this.signalingState = 'closed';
                for (const stream of this._auxStreams) stream.getTracks().forEach(track => track.stop());
                for (const audio of this._auxAudioContexts) { try { audio.close(); } catch (e) {} }
                this._auxStreams = []; this._auxAudioContexts = [];
                if (window.peer !== this) return;
                window.proxyDataChannels = {};
                clearTimeout(window.bxKeepAliveTimer);
                window.bxKeepAliveStarted = false;
                if (window.AndroidBridge) {
                    window.AndroidBridge.onPeerConnectionClose();
                }
            }
        };

        window.peer = pc;
        return pc;
    };

    window.RTCPeerConnection.prototype = OrgRTCPeerConnection.prototype;
    if (OrgRTCPeerConnection.generateCertificate) {
        window.RTCPeerConnection.generateCertificate = OrgRTCPeerConnection.generateCertificate.bind(OrgRTCPeerConnection);
    }
}

// Install synchronously at document start; waiting for load may miss the SDK's first peer.
patchRtcPeerConnection();
InitBc();

function InitBc() {
    window.worker = null;
    window.d = null;
    window.canvas = null;
    window.firstBoost = false;
    window.forKeyboard = false;

    window.handleIceGatheringState = function(state) {
        if (window.peer) {
            window.peer.iceGatheringState = state;
            let event = new Event('icegatheringstatechange');
            if (window.peer.onicegatheringstatechange) window.peer.onicegatheringstatechange(event);
            if (window.peer._listeners && window.peer._listeners['icegatheringstatechange']) {
                window.peer._listeners['icegatheringstatechange'].forEach(l => l(event));
            }
        }
    };
}

function main() {
    BxLogger.info("Init", "Loading lightweight xCloud bypass (PT-BR Optimized)...");
    interceptHttpRequests();
    patchWebSocket();
    Patcher.init();
}

main();

})();
