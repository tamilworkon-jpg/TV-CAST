import Foundation
import SwiftUI
import UIKit

@MainActor
final class AppModel: ObservableObject {
    @Published var videos: [Item] = []
    @Published var photos: [Item] = []
    @Published var authorized = true
    @Published var tv: Tv? = AppModel.loadTv()
    @Published var connected = false
    @Published var sharing = false
    @Published var message: String?
    @Published var waitingForAllow = false
    @Published var showPicker = false
    @Published var showRemote = false
    @Published var history: [String: (pos: Int64, dur: Int64)] = [:]
    @Published var found: [Tv] = []
    @Published var scanning = false

    private var server: MediaServer?
    private var idleTask: Task<Void, Never>?
    private var pickerContinuation: CheckedContinuation<Tv?, Never>?
    private var pairCancelled = false

    /// Random per-install secret that prefixes every media URL.
    let token: String = {
        if let t = UserDefaults.standard.string(forKey: "token") { return t }
        let t = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        UserDefaults.standard.set(t, forKey: "token")
        return t
    }()

    // MARK: - Library

    func load() async {
        authorized = await Library.shared.authorize()
        guard authorized else { return }
        let lib = await Task.detached { Library.shared.load() }.value
        videos = lib.videos
        photos = lib.photos
        await refresh()
    }

    func refresh() async {
        guard let saved = tv, saved.paired else { return }
        if let t = await TvClient.ping(saved.ip, timeout: 1.5, tvId: saved.id), t.id == saved.id || saved.id.isEmpty {
            setTv(t)
            connected = true
            history = await TvClient.history(t)
        } else {
            connected = false
        }
    }

    // MARK: - TV selection & pairing

    private static func loadTv() -> Tv? {
        guard let d = UserDefaults.standard.data(forKey: "tv"), var t = try? JSONDecoder().decode(Tv.self, from: d) else { return nil }
        t.paired = PhoneAuth.key(tvId: t.id) != nil
        return t
    }

    private func setTv(_ t: Tv) {
        var t = t
        t.paired = PhoneAuth.key(tvId: t.id) != nil
        tv = t
        if let d = try? JSONEncoder().encode(t) { UserDefaults.standard.set(d, forKey: "tv") }
    }

    /// Saved TV straight away if reachable and paired (no picker); otherwise picker, then pairing.
    func connectTv(forcePicker: Bool = false) async -> Tv? {
        if !forcePicker, let saved = tv {
            var t = await TvClient.ping(saved.ip, timeout: 1.5, tvId: saved.id)
            if t == nil || (!saved.id.isEmpty && t?.id != saved.id) {
                let hit = Locked<Tv?>(nil)
                await TvClient.scan(tvId: saved.id) { f in if hit.value == nil && f.id == saved.id { hit.value = f } }
                t = hit.value
            }
            if let t {
                setTv(t)
                connected = true
                if t.paired { return tv }
                return await pair(t)
            }
            message = "\(saved.name) not found. Choose a TV."
        }
        guard let picked = await pickTv() else { return nil }
        if picked.paired { return picked }
        return await pair(picked)
    }

    func pickTv() async -> Tv? {
        found = tv.map { [$0] } ?? []
        showPicker = true
        scanning = true
        Task {
            await TvClient.scan { f in
                if let i = self.found.firstIndex(where: { $0.ip == f.ip }) { self.found[i] = f } else { self.found.append(f) }
            }
            self.scanning = false
        }
        return await withCheckedContinuation { self.pickerContinuation = $0 }
    }

    func pickerDone(_ t: Tv?) {
        showPicker = false
        if let t { setTv(t) }
        pickerContinuation?.resume(returning: t)
        pickerContinuation = nil
    }

    func manualIP(_ ip: String) async {
        if let t = await TvClient.ping(ip.trimmingCharacters(in: .whitespaces), timeout: 2.5) { pickerDone(t) }
        else { message = "No TV Cast app answering at \(ip)" }
    }

    private func pair(_ t: Tv) async -> Tv? {
        if let err = await TvClient.pairStart(t) { message = err; return nil }
        waitingForAllow = true
        pairCancelled = false
        let deadline = Date().addingTimeInterval(120)
        var result: String? = "waiting"
        while !pairCancelled && result == "waiting" && Date() < deadline {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            result = await TvClient.pairPoll(t) ?? "waiting"
        }
        waitingForAllow = false
        if pairCancelled { return nil }
        guard result == "allowed" else {
            message = result == "denied" ? "\(t.name) denied the request" : "No answer from \(t.name). Try again."
            return nil
        }
        let fresh = await TvClient.ping(t.ip, timeout: 2, tvId: t.id) ?? t
        setTv(fresh)
        connected = true
        message = "Paired with \(t.name)"
        return tv
    }

    func cancelPairing() { pairCancelled = true; waitingForAllow = false }

    // MARK: - Sharing (only while needed)

    private func startSharing(for t: Tv) -> String? {
        guard let my = LAN.address(near: t.ip) else { message = "iPhone is not on Wi-Fi or Personal Hotspot"; return nil }
        if server == nil {
            let ipBox = allowedIP
            let listBox = listJSON
            let s = MediaServer(token: token, allowedIP: { ipBox.value }, onBye: { [weak self] in
                Task { @MainActor in self?.stopSharingSoon(immediate: true) }
            })
            s.listJSON = { listBox.value }
            do { try s.start() } catch { message = "Couldn't start sharing: \(error.localizedDescription)"; return nil }
            server = s
        }
        allowedIP.value = t.ip
        listJSON.value = buildListJSON()
        sharing = true
        UIApplication.shared.isIdleTimerDisabled = true
        watchIdle()
        return "http://\(my):\(MediaServer.port)/\(token)"
    }

    private let allowedIP = Locked<String?>(nil)
    private let listJSON = Locked<Data>(Data())

    /// Stops sharing 2 minutes after the TV last fetched anything (the TV pings every 30 s while playing).
    private func watchIdle() {
        idleTask?.cancel()
        idleTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if let s = server, Date().timeIntervalSince(s.lastHit) > 120 { stopSharingSoon(immediate: true); return }
            }
        }
    }

    func stopSharingSoon(immediate: Bool) {
        idleTask?.cancel()
        server?.stop()
        server = nil
        sharing = false
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func disconnect() {
        let t = tv
        stopSharingSoon(immediate: true)
        showRemote = false
        Task { if let t, t.paired { await TvClient.bye(t) } }
        message = "Disconnected. Sharing stopped."
    }

    private func buildListJSON() -> Data {
        let v = videos.map { ["id": $0.num, "name": $0.name, "size": $0.size, "dur": $0.durationMs, "key": $0.key,
                              "folder": "iPhone", "date": Int64(($0.asset.creationDate ?? Date()).timeIntervalSince1970)] as [String: Any] }
        let p = photos.map { ["id": $0.num, "name": $0.name, "size": $0.size, "folder": "iPhone",
                              "date": Int64(($0.asset.creationDate ?? Date()).timeIntervalSince1970)] as [String: Any] }
        return (try? JSONSerialization.data(withJSONObject: ["videos": v, "images": p])) ?? Data()
    }

    // MARK: - Playing

    /// Plays [item]; the visible list becomes the queue (Next/Previous on the TV).
    func play(_ item: Item, in list: [Item], startMs: Int64) async {
        guard let t = await connectTv(), let base = startSharing(for: t) else { return }
        _ = await TvClient.hello(t, base: base)
        let ok: Bool
        let sameKind = Array(list.filter { $0.isVideo == item.isVideo }.prefix(300))
        let index = sameKind.firstIndex(of: item) ?? 0
        if item.isVideo {
            let items: [[String: Any]] = sameKind.map {
                ["url": "\(base)/v/\($0.num)", "title": $0.name, "key": $0.key, "sub": ""]
            }
            ok = await TvClient.play(t, items: items, index: index, startMs: startMs)
        } else {
            ok = await TvClient.images(t, urls: sameKind.map { "\(base)/i/\($0.num)" }, names: sameKind.map { $0.name }, index: index)
        }
        if ok { showRemote = true } else { message = "Could not reach \(t.name). Is the TV on and on the same Wi-Fi?" }
    }

    /// Online link in private mode: sent straight to the TV, never stored on the iPhone.
    func openLink(_ link: String) async {
        guard let u = URL(string: link.trimmingCharacters(in: .whitespaces)), ["http", "https"].contains(u.scheme ?? ""), u.host != nil else {
            message = "Enter a link starting with http:// or https://"
            return
        }
        guard let t = await connectTv() else { return }
        if await TvClient.openURL(t, u.absoluteString) { showRemote = true } else { message = "Could not reach \(t.name)" }
    }

    func playRadio(url: String, name: String, favicon: String?) async {
        guard let t = await connectTv() else { return }
        if await TvClient.radio(t, url: url, name: name, favicon: favicon) { showRemote = true } else { message = "Could not reach \(t.name)" }
    }
}

/// Tiny thread-safe box for values read by the server threads.
final class Locked<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}
