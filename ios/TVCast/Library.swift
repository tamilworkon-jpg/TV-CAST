import Foundation
import Photos
import UIKit
import AVFoundation

/// One video or photo from the Photos library. [num] is a short numeric id used in URLs sent to the TV.
struct Item: Identifiable, Hashable {
    let num: Int
    let asset: PHAsset
    let name: String
    let size: Int64
    var id: Int { num }
    var isVideo: Bool { asset.mediaType == .video }
    var durationMs: Int64 { Int64(asset.duration * 1000) }
    /// Same key format as the Android app ("name|size") so resume history works the same way.
    var key: String { "\(name)|\(size)" }
}

/// Reads the Photos library and resolves assets to files/data for the media server.
final class Library {
    static let shared = Library()

    private let lock = NSLock()
    private var byNum: [Int: PHAsset] = [:]
    private var fileURLs: [Int: URL] = [:]
    let thumbs = PHCachingImageManager()

    func authorize() async -> Bool {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return s == .authorized || s == .limited
    }

    func load() -> (videos: [Item], photos: [Item]) {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        var videos: [Item] = []
        var photos: [Item] = []
        var map: [Int: PHAsset] = [:]
        var n = 1
        func add(_ a: PHAsset, video: Bool) {
            let res = PHAssetResource.assetResources(for: a).first
            let name = res?.originalFilename ?? (video ? "Video \(n).mov" : "Photo \(n).jpg")
            let size = (res?.value(forKey: "fileSize") as? NSNumber)?.int64Value ?? 0
            let item = Item(num: n, asset: a, name: name, size: size)
            map[n] = a
            n += 1
            if video { videos.append(item) } else { photos.append(item) }
        }
        PHAsset.fetchAssets(with: .video, options: opts).enumerateObjects { a, _, _ in add(a, video: true) }
        PHAsset.fetchAssets(with: .image, options: opts).enumerateObjects { a, _, _ in add(a, video: false) }
        lock.lock(); byNum = map; fileURLs = [:]; lock.unlock()
        return (videos, photos)
    }

    func asset(_ num: Int) -> PHAsset? { lock.lock(); defer { lock.unlock() }; return byNum[num] }

    /// Local file for a video (downloads from iCloud if needed). Cached per session.
    func videoFile(_ num: Int) -> URL? {
        lock.lock()
        if let u = fileURLs[num] { lock.unlock(); return u }
        let a = byNum[num]
        lock.unlock()
        guard let a else { return nil }
        let opts = PHVideoRequestOptions()
        opts.isNetworkAccessAllowed = true
        opts.version = .current
        opts.deliveryMode = .highQualityFormat
        let sem = DispatchSemaphore(value: 0)
        var url: URL?
        PHImageManager.default().requestAVAsset(forVideo: a, options: opts) { av, _, _ in
            url = (av as? AVURLAsset)?.url
            sem.signal()
        }
        _ = sem.wait(timeout: .now() + 60)
        if let url { lock.lock(); fileURLs[num] = url; lock.unlock() }
        return url
    }

    /// JPEG for the TV (HEIC photos are converted; large photos scaled to 4K).
    func photoJPEG(_ num: Int, maxSide: CGFloat = 3840) -> Data? {
        guard let a = asset(num) else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = true
        opts.deliveryMode = .highQualityFormat
        var data: Data?
        PHImageManager.default().requestImageDataAndOrientation(for: a, options: opts) { d, _, _, _ in data = d }
        guard let d = data, let img = UIImage(data: d) else { return nil }
        let scale = min(1, maxSide / max(img.size.width, img.size.height))
        let target = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let out = UIGraphicsImageRenderer(size: target).image { _ in img.draw(in: CGRect(origin: .zero, size: target)) }
        return out.jpegData(compressionQuality: 0.9)
    }

    /// Small JPEG preview for the TV's browser.
    func thumbJPEG(_ num: Int) -> Data? {
        guard let a = asset(num) else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = true
        opts.deliveryMode = .opportunistic
        opts.resizeMode = .fast
        var img: UIImage?
        PHImageManager.default().requestImage(for: a, targetSize: CGSize(width: 400, height: 400), contentMode: .aspectFill, options: opts) { i, _ in
            if i != nil { img = i }
        }
        return img?.jpegData(compressionQuality: 0.8)
    }
}
