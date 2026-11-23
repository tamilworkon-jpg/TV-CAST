import Foundation
import CryptoKit
import Security
import UIKit

struct Tv: Codable, Equatable {
    var ip: String
    var name: String
    var id: String
    var paired: Bool
}

// MARK: - Keys (Keychain) and request signing — same scheme as the Android app.

enum PhoneAuth {
    private static let service = "com.cleantv.ios.pairing"

    static var phoneId: String {
        if let v = KeychainStore.get("phone_id", service: service) { return v }
        var b = [UInt8](repeating: 0, count: 12)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        let v = b.map { String(format: "%02x", $0) }.joined()
        KeychainStore.set(v, for: "phone_id", service: service)
        return v
    }

    static func key(tvId: String) -> String? { tvId.isEmpty ? nil : KeychainStore.get("key_\(tvId)", service: service) }
    static func saveKey(_ key: String, tvId: String) { KeychainStore.set(key, for: "key_\(tvId)", service: service) }

    /// X-Phone / X-Ts / X-Sig headers: HMAC-SHA256(key, method \n path \n query \n ts \n sha256(body)).
    static func headers(tvId: String, method: String, url: URL, body: Data) -> [String: String] {
        guard let keyHex = key(tvId: tvId), let keyData = Data(hex: keyHex) else { return [:] }
        let ts = String(Int64(Date().timeIntervalSince1970 * 1000))
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let msg = "\(method)\n\(url.path)\n\(comps?.percentEncodedQuery ?? "")\n\(ts)\n\(bodyHash)"
        let mac = HMAC<SHA256>.authenticationCode(for: Data(msg.utf8), using: SymmetricKey(data: keyData))
        return ["X-Phone": phoneId, "X-Ts": ts, "X-Sig": mac.map { String(format: "%02x", $0) }.joined()]
    }
}

enum KeychainStore {
    static func get(_ account: String, service: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ value: String, for account: String, service: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data(capacity: hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            d.append(b)
            i = j
        }
        self = d
    }
}

// MARK: - Local network

enum LAN {
    /// IPv4 addresses on Wi-Fi (en0) and Personal Hotspot (bridge*), Wi-Fi first.
    static func addresses() -> [String] {
        var out: [(String, String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            let ifa = cur.pointee
            if let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: ifa.ifa_name)
                if name.hasPrefix("en") || name.hasPrefix("bridge") {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: host)
                        if !ip.hasPrefix("169.254") { out.append((name, ip)) }
                    }
                }
            }
            p = ifa.ifa_next
        }
        return (out.filter { $0.0 == "en0" } + out.filter { $0.0 != "en0" }).map { $0.1 }
    }

    /// This phone's address on the same /24 as the TV (Wi-Fi or hotspot).
    static func address(near tvIp: String) -> String? {
        let prefix = tvIp.split(separator: ".").dropLast().joined(separator: ".")
        let all = addresses()
        return all.first { $0.split(separator: ".").dropLast().joined(separator: ".") == prefix } ?? all.first
    }
}

// MARK: - TV client

enum TvClient {
    static let port = 8091
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral   // private: no cookies, no disk cache
        c.urlCache = nil
        c.timeoutIntervalForRequest = 5
        return URLSession(configuration: c)
    }()

    private static func url(_ tv: Tv, _ path: String) -> URL { URL(string: "http://\(tv.ip):\(port)\(path)")! }

    private static func request(_ url: URL, method: String = "GET", body: Data? = nil, tvId: String = "", timeout: TimeInterval = 5) async -> (Int, Data?) {
        var r = URLRequest(url: url, timeoutInterval: timeout)
        r.httpMethod = method
        if let body { r.httpBody = body; r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        for (k, v) in PhoneAuth.headers(tvId: tvId, method: method, url: url, body: body ?? Data()) { r.setValue(v, forHTTPHeaderField: k) }
        do {
            let (d, resp) = try await session.data(for: r)
            return ((resp as? HTTPURLResponse)?.statusCode ?? -1, d)
        } catch {
            return (-1, nil)
        }
    }

    private static func json(_ obj: [String: Any]) -> Data { (try? JSONSerialization.data(withJSONObject: obj)) ?? Data() }

    static func ping(_ ip: String, timeout: TimeInterval = 0.8, tvId: String = "") async -> Tv? {
        let (code, d) = await request(URL(string: "http://\(ip):\(port)/ping")!, tvId: tvId, timeout: timeout)
        guard code == 200, let d, let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              j["app"] as? String == "tvcast" else { return nil }
        return Tv(ip: ip, name: j["name"] as? String ?? ip, id: j["id"] as? String ?? "", paired: j["paired"] as? Bool ?? false)
    }

    /// Probes every address of each local /24 network; returns TVs as they're found.
    static func scan(tvId: String = "", found: @escaping @MainActor (Tv) -> Void) async {
        let mine = LAN.addresses()
        await withTaskGroup(of: Void.self) { group in
            var running = 0
            for my in mine {
                let prefix = my.split(separator: ".").dropLast().joined(separator: ".")
                for i in 1...254 {
                    let ip = "\(prefix).\(i)"
                    if mine.contains(ip) { continue }
                    if running >= 48 { await group.next(); running -= 1 }
                    running += 1
                    group.addTask {
                        if let tv = await ping(ip, tvId: tvId) { await found(tv) }
                    }
                }
            }
        }
    }

    // Pairing: TV shows Allow / Deny; we poll until answered.
    static func pairStart(_ tv: Tv) async -> String? {
        let (code, _) = await request(url(tv, "/pair/start"), method: "POST",
                                      body: json(["name": UIDevice.current.name, "phoneId": PhoneAuth.phoneId]))
        switch code {
        case 200..<300: return nil
        case 403: return "The TV refused too many requests. Try again in 10 minutes."
        case 429: return "Please wait a few seconds and try again."
        case -1: return "TV not reachable"
        default: return "The TV app needs updating."
        }
    }

    /// "waiting", "allowed" (key saved) or "denied"; nil = TV not reachable.
    static func pairPoll(_ tv: Tv) async -> String? {
        let (code, d) = await request(url(tv, "/pair/finish"), method: "POST", body: json(["phoneId": PhoneAuth.phoneId]), timeout: 3)
        if code == 403 { return "denied" }
        guard (200..<300).contains(code), let d, let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
        guard j["state"] as? String == "allowed", let key = j["key"] as? String else { return "waiting" }
        PhoneAuth.saveKey(key, tvId: (j["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? tv.id)
        return "allowed"
    }

    private static func signed(_ tv: Tv, _ path: String, body: [String: Any]? = nil, timeout: TimeInterval = 5) async -> Data? {
        let (code, d) = await request(url(tv, path), method: body == nil ? "GET" : "POST", body: body.map(json), tvId: tv.id, timeout: timeout)
        return (200..<300).contains(code) ? (d ?? Data()) : nil
    }

    static func play(_ tv: Tv, items: [[String: Any]], index: Int, startMs: Int64) async -> Bool {
        await signed(tv, "/play", body: ["items": items, "index": index, "startMs": startMs], timeout: 10) != nil
    }

    static func images(_ tv: Tv, urls: [String], names: [String], index: Int) async -> Bool {
        await signed(tv, "/images", body: ["urls": urls, "names": names, "index": index, "slideshow": false], timeout: 10) != nil
    }

    static func openURL(_ tv: Tv, _ link: String) async -> Bool { await signed(tv, "/url", body: ["url": link]) != nil }

    static func radio(_ tv: Tv, url: String, name: String, favicon: String?) async -> Bool {
        await signed(tv, "/radio", body: ["url": url, "name": name, "favicon": favicon ?? ""]) != nil
    }

    static func hello(_ tv: Tv, base: String) async -> Bool {
        await signed(tv, "/hello", body: ["base": base, "name": UIDevice.current.name]) != nil
    }

    static func bye(_ tv: Tv) async { _ = await signed(tv, "/bye", body: [:], timeout: 2) }

    @discardableResult
    static func control(_ tv: Tv, _ cmd: String, _ v: String? = nil) async -> Bool {
        var path = "/control?cmd=\(cmd)"
        if let v, let e = v.addingPercentEncoding(withAllowedCharacters: .alphanumerics) { path += "&v=\(e)" }
        return await signed(tv, path) != nil
    }

    static func status(_ tv: Tv) async -> [String: Any]? {
        guard let d = await signed(tv, "/status", timeout: 1.5) else { return nil }
        return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
    }

    /// Resume positions saved on the TV, keyed by "name|size".
    static func history(_ tv: Tv) async -> [String: (pos: Int64, dur: Int64)] {
        guard let d = await signed(tv, "/history", timeout: 2),
              let a = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return [:] }
        var out: [String: (pos: Int64, dur: Int64)] = [:]
        for o in a {
            if let k = o["key"] as? String {
                out[k] = (pos: (o["pos"] as? NSNumber)?.int64Value ?? 0, dur: (o["dur"] as? NSNumber)?.int64Value ?? 0)
            }
        }
        return out
    }
}
