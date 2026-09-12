import QtQuick 2.15
import QtQuick.Controls 2.15

/* Główny interfejs Nixi — pełnoekranowa „tapeta” z kulą i panelami. */
Item {
    id: root

    // paleta
    readonly property color cViolet: "#8b5cf6"
    readonly property color cCyan: "#22d3ee"
    readonly property color cPink: "#e055c4"
    readonly property color cAmber: "#f5b942"
    readonly property color cRed: "#f04770"
    readonly property color cText: "#eae6ff"
    readonly property color cDim: "#8f88b8"
    readonly property color cGlass: "#1a1533"

    property string stateName: "idle"
    property real micLevel: 0.0
    property real voiceLevel: 0.0
    property string statusText: "Uruchamianie…"
    property string consentText: ""
    property bool showConsent: false
    property bool demo: false
    property bool showWelcome: false
    property string voskStatus: ""
    property string actionText: ""
    property real timeNow: 0.0
    property bool voskReady: false

    property real uIdle: stateName === "idle" ? 1 : 0
    property real uListen: (stateName === "listening" || stateName === "waking") ? 1 : 0
    property real uSpeak: stateName === "speaking" ? 1 : 0
    property real uConfirm: stateName === "confirming" ? 1 : 0
    property real uThink: stateName === "thinking" ? 1 : 0
    property real uErr: stateName === "error" ? 1 : 0
    property real uDemo: demo ? 1 : 0

    Behavior on uIdle { NumberAnimation { duration: 260 } }
    Behavior on uListen { NumberAnimation { duration: 260 } }
    Behavior on uSpeak { NumberAnimation { duration: 260 } }
    Behavior on uConfirm { NumberAnimation { duration: 260 } }
    Behavior on uThink { NumberAnimation { duration: 260 } }
    Behavior on uErr { NumberAnimation { duration: 260 } }
    Behavior on uDemo { NumberAnimation { duration: 260 } }

    Timer {
        interval: 16
        running: true
        repeat: true
        onTriggered: { root.timeNow += 0.016; }
    }

    // ------------------------------------------------------------ połączenia
    Connections {
        target: bridge
        function onStateChanged(s) { root.stateName = s; }
        function onStatusChanged(s) { root.statusText = s; }
        function onUserText(t) { transcript.add("Ty", t); }
        function onNixiText(t) { transcript.appendNixi(t); }
        function onNixiFinal(t) { transcript.commitNixi(); }
        function onConsentRequired(d) { root.consentText = d; root.showConsent = true; }
        function onConsentResolved(r) { root.showConsent = false; transcript.add("system", r); }
        function onActionLog(a) { root.actionText = a; actionFade.restart(); }
        function onMemoryAdded(m) { memoryToast.add(m); }
        function onAudioLevel(l) { root.micLevel = l; }
        function onModelLevel(l) { root.voiceLevel = l; }
        function onErrorNotice(e) { transcript.add("system", e); }
        function onVoskStatus(s) {
            root.voskStatus = s;
            root.voskReady = (s.indexOf("gotowy") >= 0 || s.indexOf("Gotowy") >= 0);
        }
        function onWakeTriggered() { wakeFlash.start(); }
        function onDemoMode(d) { root.demo = d; }
        function onFirstRun(b) { root.showWelcome = b; }
    }

    // ciemne tło bazowe (zawsze — nawet gdy shader niedostępny)
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#07050f" }
            GradientStop { position: 1.0; color: "#0e0a24" }
        }
    }

    // ------------------------------------------------------------------- kula
    Orb {
        id: orb
        anchors.fill: parent
        uIdle: root.uIdle
        uListen: root.uListen
        uSpeak: root.uSpeak
        uConfirm: root.uConfirm
        uThink: root.uThink
        uErr: root.uErr
        uDemo: root.uDemo
        micLevel: root.micLevel
        voiceLevel: root.voiceLevel
        timeNow: root.timeNow
        shaderEnabled: bridge.uiShaderEnabled
    }

    // słupki głosu (mówienie)
    Row {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: parent.height * 0.20
        spacing: 8
        visible: root.stateName === "speaking" || root.stateName === "demo"
        opacity: visible ? 0.9 : 0
        Behavior on opacity { NumberAnimation { duration: 250 } }
        Repeater {
            model: 7
            Rectangle {
                width: 7
                height: 6 + root.voiceLevel * 44 * (0.35 + 0.65 * Math.abs(Math.sin(root.timeNow * 4.0 + index * 1.1)))
                y: 50 - height
                radius: 4
                color: root.cPink
            }
        }
    }

    // ------------------------------------------------------------- pigułka
    Rectangle {
        id: pill
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.topMargin: 28
        height: 40
        width: pillRow.width + 36
        radius: 20
        color: "#d91a1533"
        Row {
            id: pillRow
            anchors.centerIn: parent
            spacing: 10
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: 10; height: 10; radius: 5
                color: {
                    switch (root.stateName) {
                    case "listening": case "waking": return root.cCyan;
                    case "speaking": return root.cPink;
                    case "thinking": return root.cViolet;
                    case "confirming": return root.cAmber;
                    case "error": return root.cRed;
                    default: return root.demo ? root.cAmber : root.cViolet;
                    }
                }
                SequentialAnimation on opacity {
                    id: pillPulse
                    loops: Animation.Infinite
                    running: root.stateName === "confirming"
                    NumberAnimation { to: 0.3; duration: 450 }
                    NumberAnimation { to: 1.0; duration: 450 }
                }
            }
            Text {
                text: root.statusText + (root.demo && root.stateName !== "demo" ? "  •  DEMO" : "")
                color: root.cText
                font.pixelSize: 15
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    // banner zgody
    Rectangle {
        id: consent
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: pill.bottom
        anchors.topMargin: 14
        width: Math.min(parent.width * 0.8, 640)
        height: consentTextEl.height + 30
        radius: 12
        color: "#e6402a1a"
        border.color: root.cAmber
        border.width: 1.5
        visible: root.showConsent && consentText.length > 0
        opacity: visible ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 200 } }
        SequentialAnimation on border.width {
            loops: Animation.Infinite
            running: consent.visible
            NumberAnimation { from: 1; to: 2.6; duration: 600 }
            NumberAnimation { from: 2.6; to: 1; duration: 600 }
        }
        Text {
            id: consentTextEl
            anchors.centerIn: parent
            width: parent.width - 40
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: root.cText
            font.pixelSize: 15
            text: "Czy mogę wykonać: " + root.consentText + "?\nPowiedz „tak” lub „nie”."
        }
    }

    // ------------------------------------------------------------ transkrypt
    Rectangle {
        id: transcriptBox
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: parent.height * 0.055
        width: Math.min(parent.width * 0.86, 760)
        height: transcriptColumn.height + 28
        radius: 14
        color: "#b3100d22"
        visible: transcriptModel.count > 0
        Column {
            id: transcriptColumn
            anchors.centerIn: parent
            width: parent.width - 32
            spacing: 6
            Repeater {
                model: ListModel { id: transcriptModel }
                delegate: Text {
                    width: transcriptColumn.width
                    wrapMode: Text.Wrap
                    color: model.role === "system" ? root.cDim
                         : (model.role === "Ty" ? root.cCyan : root.cPink)
                    opacity: index === transcriptModel.count - 1 ? 1.0 : 0.55
                    font.pixelSize: model.role === "Ty" ? 14 : 14
                    text: (model.role === "Ty" ? "Ty: " : model.role === "system" ? "" : "Nixi: ") + model.text
                    maximumLineCount: 3
                    elide: Text.ElideRight
                }
            }
        }
    }

    // log akcji (drobny)
    Text {
        id: actionLabel
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: transcriptBox.visible ? transcriptBox.top : parent.bottom
        anchors.bottomMargin: 10
        color: root.cDim
        font.pixelSize: 12
        text: root.actionText
        opacity: 0
        Timer { id: actionFade; interval: 4500; onTriggered: actionLabel.opacity = 0 }
        onTextChanged: { if (root.actionText.length) actionLabel.opacity = 0.9; }
    }

    // toasty pamięci
    Column {
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 28
        anchors.rightMargin: 28
        spacing: 8
        Repeater {
            model: ListModel { id: memoryModel }
            delegate: Rectangle {
                width: Math.min(360, root.width * 0.4)
                height: memText.height + 20
                radius: 10
                color: "#e01a1533"
                border.color: root.cViolet
                Text {
                    id: memText
                    anchors.centerIn: parent
                    width: parent.width - 24
                    wrapMode: Text.Wrap
                    color: root.cText
                    font.pixelSize: 13
                    text: model.text
                }
                Timer {
                    interval: 7000
                    running: true
                    onTriggered: { if (memoryModel.count) memoryModel.remove(index); }
                }
            }
        }
    }

    // ----------------------------------------------------------- powitanie
    Rectangle {
        id: welcome
        anchors.centerIn: parent
        width: Math.min(root.width * 0.72, 620)
        height: welcomeCol.height + 56
        radius: 18
        color: "#f01a1533"
        border.color: root.cViolet
        visible: root.showWelcome
        Column {
            id: welcomeCol
            anchors.centerIn: parent
            width: parent.width - 64
            spacing: 12
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: "Witaj! Jestem Nixi ✦"
                color: root.cText
                font.pixelSize: 24
                font.bold: true
            }
            Text {
                width: parent.width
                wrapMode: Text.Wrap
                color: root.cDim
                font.pixelSize: 14
                lineHeight: 1.35
                text: "Twoja asystentka głosowa. Powiedz „Hej Nixi”, a zacznę słuchać — widzę Twój ekran i mogę na nim działać.\n\n" +
                      "• W ustawieniach (⚙ w prawym dolnym rogu) wklej klucz Gemini API — bez niego działa tryb demo.\n" +
                      "• Ryzykowne akcje (klikanie, pisanie, zamykanie aplikacji) zawsze potwierdzę, zanim je wykonam.\n" +
                      "• Gdy się pożegnasz lub poprosisz, żebym przestała słuchać — natychmiast zamykam nasłuch.\n\n" +
                      "Niczego nie wysyłam do sieci, dopóki mnie nie wywołasz."
            }
            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Zaczynamy"
                onClicked: { root.showWelcome = false; bridge.dismissWelcome(); }
            }
        }
    }

    // -------------------------------------------------------------- stopka
    Column {
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.leftMargin: 24
        anchors.bottomMargin: 18
        spacing: 2
        Text { text: "„Hej Nixi” — wybudź  •  Esc — zakończ  •  2×klik na kulę — włącz/wyłącz"; color: root.cDim; font.pixelSize: 12 }
        Text {
            text: root.voskReady ? "" : (root.voskStatus || "Przygotowuję nasłuch głosowy…")
            color: root.cAmber
            font.pixelSize: 12
        }
    }

    // ustawienia
    Rectangle {
        id: gear
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 24
        anchors.bottomMargin: 16
        width: 44; height: 44; radius: 22
        color: gearMa.pressed ? "#883d2f8f" : "#c01a1533"
        border.color: root.cViolet
        Text {
            anchors.centerIn: parent
            text: "⚙"
            font.pixelSize: 20
            color: root.cText
        }
        MouseArea {
            id: gearMa
            anchors.fill: parent
            hoverEnabled: true
            onClicked: settingsLoader.active = true
        }
    }

    Loader {
        id: settingsLoader
        objectName: "settingsLoader"
        active: false
        source: "Settings.qml"
        onLoaded: { item.show(); }
    }

    // ---------------------------------------------- interakcja z kulą
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton
        onClicked: { /* pojedynczy klik: nic — unikamy przypadkowych akcji */ }
        onDoubleClicked: bridge.toggleListen()
    }

    Keys.onEscapePressed: { if (bridge.isActive()) bridge.endSessionNow(); }

    // błysk przy wybudzeniu
    SequentialAnimation {
        id: wakeFlash
        running: false
        PropertyAnimation { target: pill; property: "scale"; from: 1.12; to: 1.0; duration: 240 }
    }

    // funkcje pomocnicze
    Item {
        id: transcript
        property var pending: ""
        function add(role, text) {
            transcriptModel.append({ "role": role, "text": text });
            while (transcriptModel.count > 4) transcriptModel.remove(0);
        }
        function appendNixi(t) {
            pending += t;
            if (transcriptModel.count === 0 || transcriptModel.get(transcriptModel.count - 1).role !== "Nixi-pending") {
                transcriptModel.append({ "role": "Nixi-pending", "text": "" });
            }
            while (transcriptModel.count > 4) transcriptModel.remove(0);
            transcriptModel.set(transcriptModel.count - 1, { "role": "Nixi-pending", "text": pending });
        }
        function commitNixi() {
            if (transcriptModel.count > 0) {
                transcriptModel.set(transcriptModel.count - 1, { "role": "Nixi", "text": pending });
            }
            pending = "";
        }
    }
    Item {
        id: memoryToast
        function add(text) {
            memoryModel.append({ "text": text });
            while (memoryModel.count > 4) memoryModel.remove(0);
        }
    }
}
