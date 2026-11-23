import Foundation
import Network

/**
 Serves Photos-library media to the TV over HTTP (with byte ranges for seeking).
 Every URL starts with a per-install secret token, and only the paired TV's IP is answered.
 */
final class MediaServer {
    static let port: UInt16 = 8090

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "tvcast.server", attributes: .concurrent)
    private let token: String
    private let allowedIP: () -> String?
    private let onBye: () -> Void
    private(set) var lastHit = Date()
    var listJSON: () -> Data = { Data() }

    init(token: String, allowedIP: @escaping () -> String?, onBye: @escaping () -> Void) {
        self.token = token
        self.allowedIP = allowedIP
        self.onBye = onBye
    }

    var isRunning: Bool { listener != nil }

    func start() throws {
        guard listener == nil else { return }
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: Self.port)!)
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.start(queue: queue)
        listener = l
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - HTTP

    private func accept(_ c: NWConnection) {
        c.start(queue: queue)
        readHead(c, buffer: Data())
    }

    private func readHead(_ c: NWConnection, buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, done, err in
            guard let self else { return }
            var buf = buffer
            if let data { buf.append(data) }
            if let end = buf.range(of: Data("\r\n\r\n".utf8)) {
                self.handle(c, head: String(decoding: buf[..<end.lowerBound], as: UTF8.self))
            } else if err != nil || done || buf.count > 32 * 1024 {
                c.cancel()
            } else {
                self.readHead(c, buffer: buf)
            }
        }
    }

    private func remoteIP(_ c: NWConnection) -> String? {
        if case let .hostPort(host, _) = c.endpoint, case let .ipv4(a) = host { return "\(a)" }
        if case let .hostPort(host, _) = c.endpoint { return "\(host)".components(separatedBy: "%").first }
        return nil
    }

    private func handle(_ c: NWConnection, head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { return send(c, 400, "text/plain", Data("bad".utf8)) }
        var headers: [String: String] = [:]
        for l in lines.dropFirst() {
            if let i = l.firstIndex(of: ":") {
                headers[l[..<i].lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
            }
        }
        let path = String(parts[1]).components(separatedBy: "?")[0]
        let seg = path.split(separator: "/").map(String.init)
        if seg.first == "ping" { return send(c, 200, "text/plain", Data("ok".utf8)) }
        // Secret token + only the paired TV.
        guard seg.first == token, let tv = allowedIP(), remoteIP(c) == tv else {
            return send(c, 404, "text/plain", Data("not found".utf8))
        }
        lastHit = Date()
        let rest = Array(seg.dropFirst())
        let num = rest.count > 1 ? Int(rest[1].components(separatedBy: ".")[0]) : nil
        switch rest.first {
        case "alive": send(c, 200, "text/plain", Data("ok".utf8))
        case "bye":
            send(c, 200, "text/plain", Data("ok".utf8))
            onBye()
        case "list": send(c, 200, "application/json", listJSON())
        case "v":
            guard let n = num, let url = Library.shared.videoFile(n) else { return send(c, 404, "text/plain", Data()) }
            sendFile(c, url: url, range: headers["range"])
        case "i":
            guard let n = num, let d = Library.shared.photoJPEG(n) else { return send(c, 404, "text/plain", Data()) }
            send(c, 200, "image/jpeg", d)
        case "t":
            guard rest.count > 2, let n = Int(rest[2]), let d = Library.shared.thumbJPEG(n) else { return send(c, 404, "text/plain", Data()) }
            send(c, 200, "image/jpeg", d)
        default:
            send(c, 404, "text/plain", Data("not found".utf8))
        }
    }

    private func send(_ c: NWConnection, _ code: Int, _ type: String, _ body: Data, extra: [String: String] = [:]) {
        var h = "HTTP/1.1 \(code) \(code == 200 ? "OK" : code == 206 ? "Partial Content" : "Error")\r\n"
        h += "Content-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n"
        for (k, v) in extra { h += "\(k): \(v)\r\n" }
        h += "\r\n"
        var out = Data(h.utf8)
        out.append(body)
        c.send(content: out, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }

    private func sendFile(_ c: NWConnection, url: URL, range: String?) {
        guard let fh = try? FileHandle(forReadingFrom: url),
              let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
        else { return send(c, 404, "text/plain", Data()) }
        var start: Int64 = 0
        var end = size - 1
        var partial = false
        if let r = range, r.hasPrefix("bytes=") {
            partial = true
            let spec = r.dropFirst(6).split(separator: ",")[0].split(separator: "-", omittingEmptySubsequences: false)
            if spec[0].isEmpty {
                start = max(0, size - (Int64(spec.count > 1 ? spec[1] : "0") ?? 0))
            } else {
                start = Int64(spec[0]) ?? 0
                if spec.count > 1, let e = Int64(spec[1]) { end = min(e, size - 1) }
            }
            if start >= size || start > end {
                try? fh.close()
                return send(c, 416, "text/plain", Data(), extra: ["Content-Range": "bytes */\(size)"])
            }
        }
        let length = end - start + 1
        let type = url.pathExtension.lowercased() == "mov" ? "video/quicktime" : "video/mp4"
        var h = "HTTP/1.1 \(partial ? "206 Partial Content" : "200 OK")\r\nContent-Type: \(type)\r\n"
        h += "Content-Length: \(length)\r\nAccept-Ranges: bytes\r\nConnection: close\r\n"
        if partial { h += "Content-Range: bytes \(start)-\(end)/\(size)\r\n" }
        h += "\r\n"
        try? fh.seek(toOffset: UInt64(start))
        var remaining = length
        func next() {
            if remaining <= 0 {
                try? fh.close()
                c.send(content: nil, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
                return
            }
            let n = Int(min(remaining, 256 * 1024))
            guard let chunk = try? fh.read(upToCount: n), !chunk.isEmpty else {
                try? fh.close(); c.cancel(); return
            }
            remaining -= Int64(chunk.count)
            lastHit = Date()
            c.send(content: chunk, completion: .contentProcessed { err in
                if err != nil { try? fh.close(); c.cancel() } else { next() }
            })
        }
        c.send(content: Data(h.utf8), completion: .contentProcessed { err in
            if err != nil { try? fh.close(); c.cancel() } else { next() }
        })
    }
}
