import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Window {
    id: win
    width: 600
    height: 760
    minimumWidth: 520
    minimumHeight: 600
    title: "Nixi — ustawienia"
    color: "#0e0a24"
    flags: Qt.Dialog | Qt.WindowCloseButtonHint

    property var s: ({})
    property string saveMsg: ""
    property string keyMsg: ""
    property string voskMsg: ""

    Connections {
        target: bridge
        function onKeyStatus(m) { win.keyMsg = m; }
        function onVoskStatus(m) { win.voskMsg = m; }
        function onSettingsChanged() { win.reload(); }
    }

    function reload() {
        try { s = JSON.parse(bridge.settingsJson()); } catch (e) { return; }
        apply();
    }
    function apply() {
        keyF.text = s.api_key || "";
        modelF.text = s.model || "";
        langCb.currentIndex = (s.language === "en") ? 1 : 0;
        userF.text = s.user_name || "";
        wakeCk.checked = !!(s.wake && s.wake.enabled);
        phrasesF.text = ((s.wake && s.wake.phrases) || []).join("\n");
        bareCk.checked = !!(s.wake && s.wake.allow_bare_nixi);
        cooldownSb.value = (s.wake && s.wake.cooldown_s) || 2.5;
        silenceSb.value = (s.session && s.session.silence_timeout_s) || 45;
        maxDurSb.value = (s.session && s.session.max_duration_s) || 1200;
        riskyCk.checked = !!(s.safety && s.safety.confirm_risky);
        allCk.checked = !!(s.safety && s.safety.confirm_all);
        typingCk.checked = !!(s.safety && s.safety.confirm_typing);
        memCk.checked = !!(s.memory && s.memory.enabled);
        topKSb.value = (s.memory && s.memory.top_k) || 6;
        visCk.checked = !!(s.vision && s.vision.enabled);
        visIntSb.value = (s.vision && s.vision.interval_s) || 6;
        shaderCk.checked = !!(s.ui && s.ui.shader);
        autostartCk.checked = !!(s.system && s.system.autostart);
    }
    function collect() {
        s.api_key = keyF.text.trim();
        s.model = modelF.text.trim() || "gemini-3.1-flash-live-preview";
        s.language = (langCb.currentIndex === 1) ? "en" : "pl";
        s.user_name = userF.text.trim();
        s.wake = s.wake || {};
        s.wake.enabled = wakeCk.checked;
        s.wake.phrases = phrasesF.text.split(/\r?\n/).map(function (x) { return x.trim(); }).filter(function (x) { return x.length > 0; });
        s.wake.allow_bare_nixi = bareCk.checked;
        s.wake.cooldown_s = cooldownSb.value;
        s.session = s.session || {};
        s.session.silence_timeout_s = silenceSb.value;
        s.session.max_duration_s = maxDurSb.value;
        s.safety = s.safety || {};
        s.safety.confirm_risky = riskyCk.checked;
        s.safety.confirm_all = allCk.checked;
        s.safety.confirm_typing = typingCk.checked;
        s.memory = s.memory || {};
        s.memory.enabled = memCk.checked;
        s.memory.top_k = topKSb.value;
        s.vision = s.vision || {};
        s.vision.enabled = visCk.checked;
        s.vision.interval_s = visIntSb.value;
        s.ui = s.ui || {};
        s.ui.shader = shaderCk.checked;
        s.system = s.system || {};
        s.system.autostart = autostartCk.checked;
        return JSON.stringify(s);
    }

    Component.onCompleted: reload()

    ScrollView {
        anchors.fill: parent
        anchors.bottomMargin: 64
        clip: true
        ColumnLayout {
            width: win.width - 28
            x: 14
            spacing: 8

            Text { text: "POŁĄCZENIE Z GEMINI"; color: "#8b5cf6"; font.pixelSize: 12; font.bold: true }
            TextField {
                id: keyF
                Layout.fillWidth: true
                echoMode: TextInput.Password
                placeholderText: "Klucz API (AIza… z aistudio.google.com/apikey)"
            }
            RowLayout {
                Layout.fillWidth: true
                TextField {
                    id: modelF
                    Layout.fillWidth: true
                    placeholderText: "Model Live API"
                }
                ComboBox {
                    id: langCb
                    model: ["Polski", "English"]
                }
            }
            Button {
                text: "Testuj klucz"
                onClicked: bridge.testApiKey(keyF.text)
            }
            Text { text: win.keyMsg; color: "#f5b942"; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.Wrap }

            Rectangle { Layout.fillWidth: true; height: 1; color: "#2a2350" }

            Text { text: "WYBUDZANIE GŁOSOWE („Hej Nixi”)"; color: "#8b5cf6"; font.pixelSize: 12; font.bold: true }
            CheckBox { id: wakeCk; text: "Nasłuch słowa wybudzającego w tle" }
            Text { text: "Frazy wybudzające (wyrażenia regularne, jedna na linię):"; color: "#8f88b8"; font.pixelSize: 12 }
            TextArea {
                id: phrasesF
                Layout.fillWidth: true
                Layout.preferredHeight: 92
                font.family: "monospace"
            }
            RowLayout {
                CheckBox { id: bareCk; text: "Reaguj też na samo „Nixi”" }
                Text { text: "Odstęp (s):"; color: "#8f88b8"; font.pixelSize: 12 }
                SpinBox { id: cooldownSb; from: 0; to: 10; stepSize: 0.5; editable: true }
            }
            Button {
                text: "Pobierz / odśwież model mowy (ok. 40 MB)"
                onClicked: bridge.refreshVosk()
            }
            Text { text: win.voskMsg; color: "#f5b942"; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.Wrap }

            Rectangle { Layout.fillWidth: true; height: 1; color: "#2a2350" }

            Text { text: "BEZPIECZEŃSTWO"; color: "#8b5cf6"; font.pixelSize: 12; font.bold: true }
            CheckBox { id: riskyCk; text: "Potwierdzaj ryzykowne akcje (kliknięcia, pisanie, zamykanie aplikacji, system)" }
            CheckBox { id: typingCk; text: "Potwierdzaj wpisywanie tekstu" }
            CheckBox { id: allCk; text: "Potwierdzaj KAŻDĄ akcję" }

            Rectangle { Layout.fillWidth: true; height: 1; color: "#2a2350" }

            Text { text: "SESJA I PAMIĘĆ"; color: "#8b5cf6"; font.pixelSize: 12; font.bold: true }
            RowLayout {
                Text { text: "Cisza kończąca sesję (s):"; color: "#8f88b8"; font.pixelSize: 12 }
                SpinBox { id: silenceSb; from: 10; to: 600; stepSize: 5; editable: true }
                Text { text: "Maks. czas sesji (s):"; color: "#8f88b8"; font.pixelSize: 12 }
                SpinBox { id: maxDurSb; from: 120; to: 3600; stepSize: 60; editable: true }
            }
            CheckBox { id: memCk; text: "Pamięć długotrwała" }
            RowLayout {
                CheckBox { id: visCk; text: "Wizja (zrzuty ekranu)" }
                Text { text: "Co (s):"; color: "#8f88b8"; font.pixelSize: 12 }
                SpinBox { id: visIntSb; from: 2; to: 60; stepSize: 1; editable: true }
                Text { text: "Wyniki pamięci:"; color: "#8f88b8"; font.pixelSize: 12 }
                SpinBox { id: topKSb; from: 1; to: 20; stepSize: 1; editable: true }
            }
            TextField {
                id: userF
                Layout.fillWidth: true
                placeholderText: "Twoje imię (opcjonalnie)"
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: "#2a2350" }

            Text { text: "SYSTEM"; color: "#8b5cf6"; font.pixelSize: 12; font.bold: true }
            CheckBox { id: shaderCk; text: "Efekty shader (wyłącz przy problemach z grafiką)" }
            CheckBox { id: autostartCk; text: "Uruchamiaj Nixi razem z systemem" }
        }
    }

    RowLayout {
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        anchors.left: parent.left
        anchors.margins: 12
        Text {
            Layout.fillWidth: true
            text: win.saveMsg
            color: "#8f88b8"
            font.pixelSize: 12
            wrapMode: Text.Wrap
        }
        Button { text: "Zamknij"; onClicked: win.close() }
        Button {
            text: "Zapisz"
            highlighted: true
            onClicked: { win.saveMsg = bridge.saveSettings(win.collect()); win.reload(); }
        }
    }
}
