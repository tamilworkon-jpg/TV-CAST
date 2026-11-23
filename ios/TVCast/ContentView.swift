import SwiftUI
import Photos

private let accent = Color(red: 0.31, green: 0.55, blue: 1.0)
private let surface = Color(red: 0.094, green: 0.11, blue: 0.14)

struct ContentView: View {
    @EnvironmentObject var m: AppModel
    @State private var tab = 0
    @State private var query = ""
    @State private var showLink = false
    @State private var showRadio = false
    @State private var resumeFor: (Item, [Item], Int64)?

    private var items: [Item] {
        let src = tab == 0 ? m.videos : m.photos
        let words = query.lowercased().split(separator: " ")
        return words.isEmpty ? src : src.filter { i in words.allSatisfy { i.name.lowercased().contains($0) } }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                Picker("", selection: $tab) {
                    Text("Videos (\(m.videos.count))").tag(0)
                    Text("Photos (\(m.photos.count))").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                TextField(tab == 0 ? "Search videos" : "Search photos", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal).padding(.top, 8)
                if !m.authorized {
                    Spacer()
                    Text("Allow access to Photos in Settings → TV Cast to see your videos and photos.")
                        .multilineTextAlignment(.center).foregroundColor(.secondary).padding()
                    Spacer()
                } else {
                    grid
                }
            }
            .background(Color(red: 0.055, green: 0.067, blue: 0.086).ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
        .task { await m.load() }
        .sheet(isPresented: $m.showPicker, onDismiss: { m.pickerDone(nil) }) { TvPicker() }
        .sheet(isPresented: $m.showRemote) { RemoteView() }
        .sheet(isPresented: $showLink) { LinkSheet() }
        .sheet(isPresented: $showRadio) { RadioView() }
        .alert("Allow on the TV", isPresented: $m.waitingForAllow) {
            Button("Cancel", role: .cancel) { m.cancelPairing() }
        } message: {
            Text("On \(m.tv?.name ?? "the TV"), choose Allow to let this iPhone use it.\n\nIf nothing appears, open TV Cast on the TV — the question shows there.")
        }
        .alert(m.message ?? "", isPresented: Binding(get: { m.message != nil }, set: { if !$0 { m.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .confirmationDialog("Resume?", isPresented: Binding(get: { resumeFor != nil }, set: { if !$0 { resumeFor = nil } })) {
            if let r = resumeFor {
                Button("Resume from \(fmt(r.2))") { Task { await m.play(r.0, in: r.1, startMs: r.2) } }
                Button("Start from beginning") { Task { await m.play(r.0, in: r.1, startMs: 0) } }
            }
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("TV Cast").font(.title).bold()
                HStack(spacing: 6) {
                    Circle().fill(m.connected ? .green : .gray).frame(width: 8, height: 8)
                    Text(m.tv.map { "\($0.name)\(m.sharing ? " · sharing" : m.connected ? " · ready" : "")" } ?? "No TV selected")
                        .font(.footnote).foregroundColor(.secondary).lineLimit(1)
                }
            }
            Spacer()
            Button { Task { _ = await m.connectTv(forcePicker: true) } } label: { Image(systemName: "tv") }
            Button { m.disconnect() } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.red) }
                .accessibilityLabel("Disconnect from TV")
            Button { m.showRemote = true } label: { Image(systemName: "appletvremote.gen4") }
                .disabled(m.tv?.paired != true)
            Menu {
                Button { showLink = true } label: { Label("Play a link (video / stream / Google Photos)", systemImage: "link") }
                Button { showRadio = true } label: { Label("Internet radio (FM stations)", systemImage: "radio") }
                Button(role: .destructive) { m.disconnect() } label: { Label("Disconnect from TV", systemImage: "xmark.circle") }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .font(.title3)
        .padding()
    }

    private var grid: some View {
        let cols = tab == 0 ? 2 : 3
        return ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: cols), spacing: 8) {
                ForEach(items) { item in
                    Button { open(item) } label: { Cell(item: item, progress: m.history[item.key]) }
                        .buttonStyle(.plain)
                }
            }
            .padding(8)
        }
        .refreshable { await m.load() }
    }

    private func open(_ item: Item) {
        let list = items
        if item.isVideo, let h = m.history[item.key], h.pos > 10_000, h.dur > 0, h.pos < h.dur - 30_000 {
            resumeFor = (item, list, h.pos)
        } else {
            Task { await m.play(item, in: list, startMs: 0) }
        }
    }
}

struct Cell: View {
    let item: Item
    let progress: (pos: Int64, dur: Int64)?
    @State private var image: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomTrailing) {
                Rectangle().fill(surface)
                    .aspectRatio(item.isVideo ? 16 / 9 : 1, contentMode: .fit)
                    .overlay { if let image { Image(uiImage: image).resizable().scaledToFill() } }
                    .clipped()
                if item.isVideo {
                    Text(fmt(item.durationMs)).font(.caption2).padding(.horizontal, 5).padding(.vertical, 2)
                        .background(.black.opacity(0.7)).cornerRadius(4).padding(5)
                }
                if let p = progress, p.dur > 0 {
                    GeometryReader { g in
                        Rectangle().fill(Color(red: 1, green: 0.31, blue: 0.43))
                            .frame(width: g.size.width * CGFloat(min(1, Double(p.pos) / Double(p.dur))), height: 3)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            }
            .cornerRadius(10)
            if item.isVideo {
                Text(item.name).font(.caption).lineLimit(2)
            }
        }
        .onAppear {
            let o = PHImageRequestOptions()
            o.isNetworkAccessAllowed = true
            o.deliveryMode = .opportunistic
            Library.shared.thumbs.requestImage(for: item.asset, targetSize: CGSize(width: 400, height: 400), contentMode: .aspectFill, options: o) { img, _ in
                if let img { image = img }
            }
        }
    }
}

struct TvPicker: View {
    @EnvironmentObject var m: AppModel
    @State private var ip = ""

    var body: some View {
        NavigationStack {
            List {
                Section(m.scanning ? "Searching your Wi-Fi…" : (m.found.isEmpty ? "No TV found. Open TV Cast on the TV." : "TVs")) {
                    ForEach(m.found, id: \.ip) { t in
                        Button { m.pickerDone(t) } label: {
                            VStack(alignment: .leading) {
                                Text(t.name)
                                Text(t.ip + (t.paired ? " · paired" : "")).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
                Section("Or enter the TV's IP address") {
                    HStack {
                        TextField("192.168.0.104", text: $ip).keyboardType(.decimalPad)
                        Button("Connect") { Task { await m.manualIP(ip) } }
                    }
                }
            }
            .navigationTitle("Select TV")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { m.pickerDone(nil) } } }
        }
    }
}

struct LinkSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var link = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: $link)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Paste") { link = UIPasteboard.general.string ?? "" }
                } footer: {
                    Text("Video or stream link (mp4, m3u8 …), a web page with a video, a YouTube link, or a Google Photos shared-album link (starts a slideshow).\n\nPrivate: the link isn't saved on the iPhone or the TV. Ads/trackers are blocked; if a VPN app is running on the TV, it's used.")
                }
            }
            .navigationTitle("Play a link")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Play") { let l = link; dismiss(); Task { await m.openLink(l) } }
                }
            }
        }
    }
}

struct RadioView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var query = "tamil"
    @State private var stations: [[String: Any]] = []
    @State private var status = "Searching…"

    var body: some View {
        NavigationStack {
            List {
                Section(status) {
                    ForEach(stations.indices, id: \.self) { i in
                        let s = stations[i]
                        Button(s["name"] as? String ?? "Station") {
                            let url = (s["url_resolved"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (s["url"] as? String ?? "")
                            dismiss()
                            Task { await m.playRadio(url: url, name: s["name"] as? String ?? "Radio", favicon: s["favicon"] as? String) }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Tamil, Mirchi, Hello FM, AIR…")
            .onSubmit(of: .search) { Task { await search() } }
            .navigationTitle("Internet radio")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task { await search() }
        }
    }

    private func search() async {
        status = "Searching…"
        let q = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        for field in ["name", "tag"] {
            let url = URL(string: "https://de1.api.radio-browser.info/json/stations/search?countrycode=IN&hidebroken=true&order=clickcount&reverse=true&limit=80&\(field)=\(q)")!
            var r = URLRequest(url: url)
            r.setValue("TVCast-iOS/1", forHTTPHeaderField: "User-Agent")
            if let (d, _) = try? await URLSession.shared.data(for: r),
               let a = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]], !a.isEmpty {
                stations = a.filter { (($0["url_resolved"] as? String) ?? ($0["url"] as? String) ?? "").hasPrefix("http") }
                status = "\(stations.count) stations · tap to play on TV"
                return
            }
        }
        stations = []
        status = "No stations found (or no internet)"
    }
}

func fmt(_ ms: Int64) -> String {
    let s = ms / 1000
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}
