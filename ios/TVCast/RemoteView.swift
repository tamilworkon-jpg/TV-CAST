import SwiftUI

/// Phone remote for whatever is on the TV — same controls as the Android remote.
struct RemoteView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var s: [String: Any] = [:]
    @State private var seekPos: Double = 0
    @State private var dragging = false
    @State private var vol: Double = 0
    @State private var volDragging = false
    @State private var picker: String?

    private var mode: String { s["mode"] as? String ?? "idle" }
    private func str(_ k: String) -> String { s[k] as? String ?? "" }
    private func num(_ k: String) -> Double { (s[k] as? NSNumber)?.doubleValue ?? 0 }
    private func bool(_ k: String) -> Bool { s[k] as? Bool ?? false }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(str("title").isEmpty ? "Nothing playing on the TV" : str("title")).font(.title3).bold().lineLimit(3)
                    Text(stateText).foregroundColor(.blue).font(.subheadline)
                    if mode == "video" { videoControls }
                    if mode == "images" { imageControls }
                    if mode == "video" || mode == "images" { viewControls }
                    volumeControls
                    Button(role: .destructive) { send("stop"); dismiss() } label: {
                        Label("Stop on TV", systemImage: "stop.fill").frame(maxWidth: .infinity)
                    }.buttonStyle(.bordered)
                    Button { m.disconnect(); dismiss() } label: {
                        Text("Disconnect (stop sharing from this iPhone)").frame(maxWidth: .infinity)
                    }.foregroundColor(.secondary)
                }
                .padding()
            }
            .navigationTitle(m.tv?.name ?? "Remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task { await pollLoop() }
            .confirmationDialog(picker ?? "", isPresented: Binding(get: { picker != nil }, set: { if !$0 { picker = nil } })) {
                pickerButtons
            }
        }
    }

    private var stateText: String {
        guard mode == "video" else {
            return mode == "images" ? "Picture \(Int(num("index")) + 1) of \(Int(num("count")))" : "Connected"
        }
        var t = bool("reconnecting") ? "Reconnecting…" : !str("error").isEmpty ? "Can't play (\(str("error")))"
            : bool("ended") ? "Finished" : bool("buffering") ? "Buffering…" : bool("playing") ? "Playing" : "Paused"
        if bool("radio") { t += " · Live radio" }
        else if bool("online") { t += " · Private link · ads blocked · VPN \(bool("vpn") ? "on" : "off")" }
        else if num("qCount") > 1 { t += " · \(Int(num("qIndex")) + 1) of \(Int(num("qCount")))" }
        return t
    }

    private var videoControls: some View {
        VStack(spacing: 12) {
            let dur = max(1, num("dur") / 1000)
            Slider(value: $seekPos, in: 0...dur, onEditingChanged: { editing in
                dragging = editing
                if !editing { send("seek", String(Int64(seekPos * 1000))) }
            })
            Text("\(fmt(Int64(seekPos * 1000))) / \(fmt(Int64(num("dur"))))").font(.caption).frame(maxWidth: .infinity, alignment: .trailing)
            HStack {
                btn("-1m") { send("rew", "60000") }
                btn("-10s") { send("rew") }
                Button { send("toggle") } label: {
                    Image(systemName: bool("playing") ? "pause.fill" : "play.fill").font(.title).frame(width: 64, height: 64)
                        .background(Circle().fill(Color.blue))
                }.foregroundColor(.white)
                btn("+10s") { send("fwd") }
                btn("+1m") { send("fwd", "60000") }
            }
            if num("qCount") > 1 {
                HStack {
                    btn("⏮ Previous") { send("prev") }
                    btn("Next ⏭") { send("next") }
                }
            }
            HStack {
                btn("Audio (\((s["audioTracks"] as? [Any])?.count ?? 0))") { picker = "Audio track" }
                btn(bool("subs") ? "Subtitles: On" : "Subtitles: Off") { picker = "Subtitles" }
            }
            HStack {
                btn("Speed \(String(format: "%.2g", num("speed")))x") { picker = "Speed" }
                btn("Screen: \(str("aspect"))") { picker = "Screen size" }
            }
            HStack {
                btn("Picture: \(str("picMode"))") { picker = "Picture mode" }
                btn("Sound: \(str("soundMode"))") { picker = "Sound mode" }
            }
        }
    }

    private var imageControls: some View {
        HStack {
            btn("◀ Prev") { send("prev") }
            btn(bool("slideshow") ? "Stop slideshow" : "Slideshow") { send("slideshow") }
            btn("Next ▶") { send("next") }
        }
    }

    private var viewControls: some View {
        VStack(alignment: .leading) {
            Text("Rotate & zoom · \(Int(num("rotation")))° · " + (num("zoom") <= 1 ? "no zoom" : "zoom \(String(format: "%.2g", num("zoom")))×"))
                .font(.caption).foregroundColor(.secondary)
            HStack {
                btn("⟲ 90°") { send("rotate", "-90") }
                btn("⟳ 90°") { send("rotate", "90") }
                btn("Zoom −") { send("zoom", "out") }
                btn("Zoom +") { send("zoom", "in") }
            }
            if mode == "images" && num("zoom") > 1 {
                HStack {
                    btn("◀") { send("pan", "left") }
                    btn("▲") { send("pan", "up") }
                    btn("▼") { send("pan", "down") }
                    btn("▶") { send("pan", "right") }
                }
            }
            Button("Reset rotation & zoom") { send("resetview") }.font(.footnote)
        }
    }

    private var volumeControls: some View {
        VStack(alignment: .leading) {
            Text("TV volume").font(.caption).foregroundColor(.secondary)
            Slider(value: $vol, in: 0...max(1, num("volMax")), onEditingChanged: { editing in
                volDragging = editing
                if !editing { send("setvol", String(Int(vol))) }
            })
            HStack {
                btn("Vol −") { send("voldown") }
                btn("Mute") { send("mute") }
                btn("Vol +") { send("volup") }
            }
        }
    }

    @ViewBuilder private var pickerButtons: some View {
        switch picker {
        case "Audio track":
            ForEach(Array(tracks("audioTracks").enumerated()), id: \.offset) { _, t in Button(t.label) { send("audiotrack", t.id) } }
        case "Subtitles":
            Button("Off") { send("subtrack", "off") }
            ForEach(Array(tracks("subTracks").enumerated()), id: \.offset) { _, t in Button(t.label) { send("subtrack", t.id) } }
        case "Speed":
            ForEach(["0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"], id: \.self) { v in Button("\(v)x") { send("speed", v) } }
        case "Screen size":
            ForEach(Array(((s["aspects"] as? [String]) ?? []).enumerated()), id: \.offset) { i, a in Button(a) { send("aspect", String(i)) } }
        case "Picture mode":
            ForEach(((s["picModes"] as? [String]) ?? []).filter { $0 != "Custom" }, id: \.self) { p in Button(p) { send("picmode", p) } }
        case "Sound mode":
            ForEach(((s["soundModes"] as? [String]) ?? []).filter { $0 != "Custom" }, id: \.self) { p in Button(p) { send("soundmode", p) } }
        default:
            EmptyView()
        }
    }

    private struct Track { let id: String; let label: String }

    private func tracks(_ field: String) -> [Track] {
        ((s[field] as? [[String: Any]]) ?? []).map { Track(id: $0["id"] as? String ?? "", label: $0["label"] as? String ?? "Track") }
    }

    private func btn(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).font(.callout).frame(maxWidth: .infinity).padding(.vertical, 10) }
            .buttonStyle(.bordered)
    }

    private func send(_ cmd: String, _ v: String? = nil) {
        guard let tv = m.tv else { return }
        Task {
            await TvClient.control(tv, cmd, v)
            await poll()
        }
    }

    private func pollLoop() async {
        while !Task.isCancelled {
            await poll()
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }

    private func poll() async {
        guard let tv = m.tv, let st = await TvClient.status(tv) else { return }
        s = st
        if !dragging { seekPos = num("pos") / 1000 }
        if !volDragging { vol = num("vol") }
    }
}
