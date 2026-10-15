import Foundation
import UIKit
import ImageIO
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation

/// Photo library picker on PHPickerViewController (no permission prompt) and camera on UIImagePickerController.
/// Picked files are copied into the app cache with their original bytes (HEIC stays HEIC), so `uri` is a local path.
final class ImagePickerModule: NSObject, PxModule, PHPickerViewControllerDelegate,
                               UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    static let moduleName = "ImagePicker"

    private var reply: PxReply?
    private var token = 0
    private weak var picker: PHPickerViewController?
    private static let queue = DispatchQueue(label: "pyxpo.imagepicker")

    required override init() { super.init() }

    func call(_ ctx: PxModuleContext, _ method: String, _ args: [Any], _ reply: @escaping PxReply) {
        switch method {
        case "pick": pick(ctx, args, reply)
        case "camera": camera(ctx, args, reply)
        case "camera_permission": cameraPermission((args.first as? Bool) ?? false, reply)
        default: reply(nil, "unknown native call ImagePicker.\(method)")
        }
    }

    private func pick(_ ctx: PxModuleContext, _ args: [Any], _ reply: @escaping PxReply) {
        if self.reply != nil { reply(nil, "a picker is already open"); return }
        guard let top = ctx.presenter() else { reply(nil, "no view controller to present the picker"); return }
        let multiple = (args.first as? Bool) ?? false
        let limit = (args.count > 1 ? args[1] as? Int : nil) ?? 0
        var cfg = PHPickerConfiguration(photoLibrary: .shared())
        let kinds = (args.count > 2 ? args[2] as? String : nil) ?? "images"
        cfg.filter = kinds == "videos" ? .videos : (kinds == "all" ? .any(of: [.images, .videos]) : .images)
        cfg.selectionLimit = multiple ? max(0, limit) : 1
        cfg.preferredAssetRepresentationMode = .current
        let p = PHPickerViewController(configuration: cfg)
        p.delegate = self
        hold(ctx, reply)
        picker = p
        top.present(p, animated: true)
    }

    /// Marks a picker as open (the single in-flight guard) and keeps this delegate alive until `finish`.
    private func hold(_ ctx: PxModuleContext, _ reply: @escaping PxReply) {
        self.reply = reply
        token = ctx.retain(self)
        let t = token
        done = { ctx.release(t) }
    }

    // MARK: camera

    private static func cameraState() -> [String: Any] {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return ["status": "granted", "canAskAgain": true]
        case .notDetermined: return ["status": "undetermined", "canAskAgain": true]
        default: return ["status": "denied", "canAskAgain": false]  // denied in Settings, or restricted
        }
    }

    private func cameraPermission(_ ask: Bool, _ reply: @escaping PxReply) {
        guard ask, AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined else { reply(Self.cameraState(), nil); return }
        if Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") == nil {
            reply(nil, "camera access needs NSCameraUsageDescription in Info.plist"); return
        }
        AVCaptureDevice.requestAccess(for: .video) { _ in reply(Self.cameraState(), nil) }
    }

    private func camera(_ ctx: PxModuleContext, _ args: [Any], _ reply: @escaping PxReply) {
        if self.reply != nil { reply(nil, "a picker is already open"); return }
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            reply(nil, "this device has no camera"); return
        }
        let kinds = (args.first as? String) ?? "images"
        let maxDuration = (args.count > 1 ? (args[1] as? NSNumber)?.doubleValue : nil) ?? 0
        let image = UTType.image.identifier, movie = UTType.movie.identifier
        let wanted = kinds == "videos" ? [movie] : (kinds == "all" ? [image, movie] : [image])
        let available = UIImagePickerController.availableMediaTypes(for: .camera) ?? []
        guard wanted.allSatisfy(available.contains) else { reply(nil, "this camera cannot capture \(kinds)"); return }
        // UIKit terminates the app when these are missing, so fail the call instead.
        for key in ["NSCameraUsageDescription"] + (wanted.contains(movie) ? ["NSMicrophoneUsageDescription"] : [])
        where Bundle.main.object(forInfoDictionaryKey: key) == nil {
            reply(nil, "the camera needs \(key) in Info.plist"); return
        }
        hold(ctx, reply)  // claim the single slot before any async permission prompt
        let present = { [weak self] in
            guard let self = self else { return }
            guard let top = ctx.presenter() else { self.finish(nil, "no view controller to present the camera"); return }
            let p = UIImagePickerController()
            p.sourceType = .camera
            p.mediaTypes = wanted
            p.videoQuality = .typeHigh
            if maxDuration > 0 { p.videoMaximumDuration = maxDuration }
            p.modalPresentationStyle = .fullScreen  // no swipe-to-dismiss, so every close goes through the delegate
            p.delegate = self
            top.present(p, animated: true)
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: present()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? present() : self.finish(nil, "camera permission denied") }
            }
        default: finish(nil, "camera permission denied")
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        finish(["assets": []], nil)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        let video = info[.mediaURL] as? URL
        let photo = info[.originalImage] as? UIImage
        let meta = info[.mediaMetadata] as? [String: Any]
        Self.queue.async {
            do {
                let dir = try Self.outputDir()
                let asset: [String: Any]
                if let url = video {
                    asset = try Self.copy(url, into: dir, suggested: nil, video: true)
                } else if let img = photo {
                    asset = try Self.describe(Self.writeJPEG(img, meta, into: dir), name: nil, video: false)
                } else {
                    throw NSError(domain: "pyxpo.imagepicker", code: 1, userInfo: [NSLocalizedDescriptionKey: "the camera returned no media"])
                }
                DispatchQueue.main.async { self.finish(["assets": [asset]], nil) }
            } catch {
                let message = "\(error.localizedDescription)"
                DispatchQueue.main.async { self.finish(nil, message) }
            }
        }
    }

    /// Writes the photo as a full-quality JPEG keeping the camera's metadata, with the orientation tag matching the pixels.
    private static func writeJPEG(_ img: UIImage, _ meta: [String: Any]?, into dir: URL) throws -> URL {
        let dest = dir.appendingPathComponent(UUID().uuidString + ".jpg")
        guard let cg = img.cgImage,
              let out = CGImageDestinationCreateWithURL(dest as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            guard let data = img.jpegData(compressionQuality: 1) else {
                throw NSError(domain: "pyxpo.imagepicker", code: 2, userInfo: [NSLocalizedDescriptionKey: "cannot encode the photo"])
            }
            try data.write(to: dest)
            return dest
        }
        var props = meta ?? [:]
        let orientation = exifOrientation(img.imageOrientation)
        props[kCGImagePropertyOrientation as String] = orientation
        if var tiff = props[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            tiff[kCGImagePropertyTIFFOrientation as String] = orientation
            props[kCGImagePropertyTIFFDictionary as String] = tiff
        }
        props[kCGImageDestinationLossyCompressionQuality as String] = 1.0
        CGImageDestinationAddImage(out, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(out) else {
            throw NSError(domain: "pyxpo.imagepicker", code: 2, userInfo: [NSLocalizedDescriptionKey: "cannot write the photo"])
        }
        return dest
    }

    private static func exifOrientation(_ o: UIImage.Orientation) -> Int {
        switch o {
        case .up: return 1
        case .upMirrored: return 2
        case .down: return 3
        case .downMirrored: return 4
        case .leftMirrored: return 5
        case .right: return 6
        case .rightMirrored: return 7
        case .left: return 8
        @unknown default: return 1
        }
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
        for (i, r) in results.enumerated() {
            group.enter()
            let isVideo = r.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            let tid = isVideo ? UTType.movie.identifier : UTType.image.identifier
            r.itemProvider.loadFileRepresentation(forTypeIdentifier: tid) { url, err in
                defer { group.leave() }
                var asset: [String: Any]?
                var message: String?
                if let url = url {
                    do { asset = try Self.copy(url, into: dir, suggested: r.itemProvider.suggestedName, video: isVideo) }
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

    private static func copy(_ src: URL, into dir: URL, suggested: String?, video: Bool) throws -> [String: Any] {
        let ext = src.pathExtension.isEmpty ? (video ? "mov" : "jpg") : src.pathExtension.lowercased()
        let dest = dir.appendingPathComponent(UUID().uuidString + "." + ext)
        try FileManager.default.copyItem(at: src, to: dest)
        return describe(dest, name: suggested.map { $0 + "." + ext } ?? src.lastPathComponent, video: video)
    }

    /// The expo asset dictionary for a file already in the cache folder.
    private static func describe(_ dest: URL, name: String?, video: Bool) -> [String: Any] {
        let ext = dest.pathExtension.lowercased()
        var w = 0, h = 0
        var duration: Int?
        if video {
            let av = AVURLAsset(url: dest)
            if let t = av.tracks(withMediaType: .video).first {
                let sz = t.naturalSize.applying(t.preferredTransform)
                (w, h) = (Int(abs(sz.width).rounded()), Int(abs(sz.height).rounded()))
            }
            duration = Int((CMTimeGetSeconds(av.duration) * 1000).rounded())
        } else if let s = CGImageSourceCreateWithURL(dest as CFURL, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(s, 0, nil) as? [CFString: Any] {
            let pw = props[kCGImagePropertyPixelWidth] as? Int ?? 0
            let ph = props[kCGImagePropertyPixelHeight] as? Int ?? 0
            let o = props[kCGImagePropertyOrientation] as? Int ?? 1
            (w, h) = o >= 5 && o <= 8 ? (ph, pw) : (pw, ph)
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int) ?? 0
        let fileName = name ?? dest.lastPathComponent
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? (video ? "video/" : "image/") + ext
        var out: [String: Any] = ["uri": dest.path, "type": video ? "video" : "image", "width": w, "height": h,
                                  "fileSize": size, "mimeType": mime, "fileName": fileName]
        if let d = duration { out["duration"] = d }
        return out
    }

    private static func outputDir() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pyxpo-picked")
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
}
