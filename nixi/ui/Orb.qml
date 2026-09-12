import QtQuick 2.15

/* Kula Nixi — shader z aurą, pierścieniami i reakcją na głos. */
Item {
    id: orb

    property real uIdle: 1.0
    property real uListen: 0.0
    property real uSpeak: 0.0
    property real uConfirm: 0.0
    property real uThink: 0.0
    property real uErr: 0.0
    property real uDemo: 0.0
    property real micLevel: 0.0
    property real voiceLevel: 0.0
    property real timeNow: 0.0
    property bool shaderEnabled: true

    property color cAura: "#8b5cf6"
    property color cAccent: "#22d3ee"
    property color cSpeak: "#e055c4"

    ShaderEffect {
        id: shader
        anchors.fill: parent
        visible: orb.shaderEnabled

        property real uTime: orb.timeNow
        property real uIdle: orb.uIdle
        property real uListen: orb.uListen
        property real uSpeak: orb.uSpeak
        property real uConfirm: orb.uConfirm
        property real uThink: orb.uThink
        property real uErr: orb.uErr
        property real uDemo: orb.uDemo
        property real uMic: orb.micLevel
        property real uVoice: orb.voiceLevel
        property color uAura: orb.cAura
        property color uAccent: orb.cAccent
        property color uSpeakC: orb.cSpeak
        property vector2d uRes: Qt.vector2d(width, height)

        fragmentShader: "
            varying highp vec2 qt_TexCoord0;
            uniform highp float uTime;
            uniform highp float uIdle;
            uniform highp float uListen;
            uniform highp float uSpeak;
            uniform highp float uConfirm;
            uniform highp float uThink;
            uniform highp float uErr;
            uniform highp float uDemo;
            uniform highp float uMic;
            uniform highp float uVoice;
            uniform highp vec4 uAura;
            uniform highp vec4 uAccent;
            uniform highp vec4 uSpeakC;
            uniform highp vec2 uRes;

            float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
            float noise(in vec2 p){
                vec2 i = floor(p); vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), f.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
            }
            float fbm(vec2 p){
                float v = 0.0; float a = 0.5;
                for (int i = 0; i < 4; i++){ v += a * noise(p); p *= 2.03; a *= 0.5; }
                return v;
            }

            void main(){
                vec2 uv = qt_TexCoord0;
                vec2 p = (uv * 2.0 - 1.0);
                float ar = uRes.x / max(uRes.y, 1.0);
                p.x *= ar;
                float r = length(p);
                float R = 0.34;

                // tło
                vec3 bg = mix(vec3(0.027, 0.020, 0.059), vec3(0.055, 0.039, 0.141), clamp(uv.y * 0.8 + 0.2, 0.0, 1.0));
                vec2 sp = uv * vec2(ar, 1.0) * 3.0;
                float stars = step(0.9982, hash(floor(sp * 120.0)));
                bg += stars * vec3(0.9, 0.92, 1.0) * 0.22 * (0.5 + 0.5 * sin(uTime * 1.4 + hash(floor(sp * 120.0)) * 6.283));
                bg += vec3(0.35, 0.25, 0.60) * fbm(uv * 3.0 + vec2(uTime * 0.02, 0.0)) * 0.05;

                // kolor stanu
                vec3 idleC = uAura.rgb;
                vec3 listenC = mix(uAccent.rgb, idleC, 0.35);
                vec3 speakC = uSpeakC.rgb;
                vec3 confC = vec3(0.96, 0.73, 0.26);
                vec3 errC = vec3(0.94, 0.28, 0.44);
                vec3 stateC = idleC * uIdle + listenC * uListen + speakC * uSpeak
                            + confC * uConfirm + idleC * uThink + errC * uErr;

                // kula
                float breathe = 1.0 + 0.022 * uIdle * sin(uTime * 1.5) + 0.05 * uListen * sin(uTime * 2.3);
                float orbR = R * breathe;
                float core = smoothstep(orbR, orbR * 0.35, r);
                vec2 q = p / orbR;
                float n = fbm(q * 2.4 + vec2(uTime * 0.10, uTime * 0.07));
                float n2 = fbm(q * 4.6 - vec2(uTime * 0.06, uTime * 0.09));
                vec3 orbCol = stateC * (0.45 + 0.55 * n);
                orbCol += vec3(1.0) * pow(max(0.0, 1.0 - abs(q.y * 2.0)), 3.0) * 0.10 * (0.3 + 0.7 * uListen);
                orbCol *= mix(0.72, 1.12, smoothstep(orbR, 0.0, r));
                float hl = smoothstep(0.55, 0.0, length(q - vec2(-0.28, -0.30)));
                orbCol += stateC * hl * 0.25 * (0.5 + 0.5 * n2);

                // halo mikrofonu (słuchanie)
                float halo = uListen * (0.35 + 0.10 * uMic);
                float ring1 = smoothstep(orbR * 1.16 + halo, orbR * 1.16 + halo - 0.015, r);
                // kolce głosu (mówienie)
                float ang = atan(p.y, p.x);
                float spike = uSpeak * (0.5 + 0.5 * sin(ang * 16.0 + uTime * 2.2)) * (0.3 + 0.7 * uVoice);
                float ring2 = smoothstep(orbR * 1.22 + spike * 0.16, orbR * 1.22 + spike * 0.16 - 0.010, r) * uSpeak;
                // puls potwierdzenia
                float confPulse = uConfirm * (0.5 + 0.5 * sin(uTime * 5.0));
                float ring3 = smoothstep(orbR * 1.24, orbR * 1.24 - 0.012, r) * confPulse;
                // pierścień bazowy
                float ring0 = smoothstep(orbR * 1.045, orbR * 1.045 - 0.008, r) - smoothstep(orbR * 1.16, orbR * 1.16 - 0.008, r);
                // myślenie — obracające się kreski
                float da = mod(ang - uTime * 1.8, 0.6);
                float dashes = smoothstep(orbR * 1.20, orbR * 1.20 - 0.012, r) * smoothstep(0.45, 0.55, da) * uThink;

                vec3 col = bg;
                col += stateC * 0.20 * (1.0 - core) * smoothstep(orbR * 1.9, orbR * 1.05, r);
                col += stateC * ring0 * 0.55 * (0.6 + 0.4 * uIdle);
                col += listenC * ring1 * 0.9;
                col += speakC * ring2 * 1.1;
                col += confC * ring3 * 1.0;
                col += stateC * dashes * 0.8;
                col += orbCol * core;
                col += uDemo * vec3(0.96, 0.73, 0.26) * stars * 0.4;

                gl_FragColor = vec4(col, 1.0);
            }
        "
    }

    // Fallback (bez shadera — np. zdalny pulpit)
    Rectangle {
        anchors.centerIn: parent
        width: parent.width * 0.30
        height: width
        radius: width / 2
        visible: !orb.shaderEnabled
        gradient: Gradient {
            GradientStop { position: 0; color: "#b78bfa" }
            GradientStop { position: 1; color: "#5b21b6" }
        }
    }
}
