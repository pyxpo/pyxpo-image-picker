import Foundation
import UIKit
import ImageIO
import PhotosUI
import UniformTypeIdentifiers

/// Photo library picker on PHPickerViewController (no permission prompt). Picked files are copied into the app
/// cache with their original bytes (HEIC stays HEIC), so `uri` is a local path.
final class ImagePickerModule: NSObject, PxModule, PHPickerViewControllerDelegate {
    static let moduleName = "ImagePicker"

    private var reply: PxReply?
    private var token = 0
    private weak var picker: PHPickerViewController?
    private static let queue = DispatchQueue(label: "pyxpo.imagepicker")

    required override init() { super.init() }

    func call(_ ctx: PxModuleContext, _ method: String, _ args: [Any], _ reply: @escaping PxReply) {
        guard method == "pick" else { reply(nil, "unknown native call ImagePicker.\(method)"); return }
        if self.reply != nil { reply(nil, "a picker is already open"); return }
        guard let top = ctx.presenter() else { reply(nil, "no view controller to present the picker"); return }
        let multiple = (args.first as? Bool) ?? false
        let limit = (args.count > 1 ? args[1] as? Int : nil) ?? 0
        var cfg = PHPickerConfiguration(photoLibrary: .shared())
        cfg.filter = .images
        cfg.selectionLimit = multiple ? max(0, limit) : 1
        cfg.preferredAssetRepresentationMode = .current
        let p = PHPickerViewController(configuration: cfg)
        p.delegate = self
        self.reply = reply
        token = ctx.retain(self)
        picker = p
        let release = { ctx.release(self.token) }
        self.done = release
        top.present(p, animated: true)
    }

    private var done: (() -> Void)?

    private func finish(_ value: Any?, _ error: String?) {
        let r = reply
        reply = nil
        let d = done
        done = nil
        r?(value, error)
        d?()
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        if results.isEmpty { finish(["assets": []], nil); return }
        var assets = [[String: Any]?](repeating: nil, count: results.count)
        var firstError: String?
        let group = DispatchGroup()
        let lock = NSLock()
        let dir: URL
        do { dir = try Self.outputDir() } catch { finish(nil, "\(error)"); return }
        let tid = UTType.image.identifier
        for (i, r) in results.enumerated() {
            group.enter()
            r.itemProvider.loadFileRepresentation(forTypeIdentifier: tid) { url, err in
                defer { group.leave() }
                var asset: [String: Any]?
                var message: String?
                if let url = url {
                    do { asset = try Self.copy(url, into: dir, suggested: r.itemProvider.suggestedName) }
                    catch { message = "\(error)" }
                } else {
                    message = err.map { "\($0)" } ?? "cannot load picked image"
                }
                lock.lock()
                assets[i] = asset
                if firstError == nil { firstError = message }
                lock.unlock()
            }
        }
        group.notify(queue: .main) {
            let ok = assets.compactMap { $0 }
            if ok.isEmpty, let e = firstError { self.finish(nil, e) } else { self.finish(["assets": ok], nil) }
        }
    }

    private static func copy(_ src: URL, into dir: URL, suggested: String?) throws -> [String: Any] {
        let ext = src.pathExtension.isEmpty ? "jpg" : src.pathExtension.lowercased()
        let dest = dir.appendingPathComponent(UUID().uuidString + "." + ext)
        try FileManager.default.copyItem(at: src, to: dest)
        var w = 0, h = 0
        if let s = CGImageSourceCreateWithURL(dest as CFURL, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(s, 0, nil) as? [CFString: Any] {
            let pw = props[kCGImagePropertyPixelWidth] as? Int ?? 0
            let ph = props[kCGImagePropertyPixelHeight] as? Int ?? 0
            let o = props[kCGImagePropertyOrientation] as? Int ?? 1
            (w, h) = o >= 5 && o <= 8 ? (ph, pw) : (pw, ph)
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int) ?? 0
        let name = (suggested.map { $0 + "." + ext }) ?? src.lastPathComponent
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "image/" + ext
        return ["uri": dest.path, "width": w, "height": h, "fileSize": size, "mimeType": mime, "fileName": name]
    }

    private static func outputDir() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pyxpo-picked")
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
}
