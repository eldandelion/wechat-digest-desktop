import Foundation
import Vision
import ImageIO
import CoreGraphics

struct VisionOcrResult: Encodable {
    let text: String
    let qrPayloads: [String]
}

private func printUsageAndExit() -> Never {
    FileHandle.standardError.write(Data("Usage: vision-ocr [--json] <image-path>\n".utf8))
    exit(2)
}

private func loadDownscaledCGImage(from url: URL, maxPixelSize: Int = 2048) -> CGImage? {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
        return nil
    }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true
    ]
    if let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) {
        return thumbnail
    }
    return CGImageSourceCreateImageAtIndex(source, 0, nil)
}

private func performVisionRequests(on cgImage: CGImage, cpuOnly: Bool) throws -> VisionOcrResult {
    let textRequest = VNRecognizeTextRequest()
    textRequest.recognitionLevel = .accurate
    textRequest.recognitionLanguages = ["zh-Hans", "en-US"]
    textRequest.usesLanguageCorrection = true
    textRequest.usesCPUOnly = cpuOnly

    let barcodeRequest = VNDetectBarcodesRequest()
    barcodeRequest.symbologies = [.qr]
    barcodeRequest.usesCPUOnly = cpuOnly

    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    try handler.perform([textRequest, barcodeRequest])

    let recognizedLines: [String] = (textRequest.results ?? []).compactMap { observation in
        observation.topCandidates(1).first?.string.trimmingCharacters(in: .whitespacesAndNewlines)
    }.filter { !$0.isEmpty }

    var seenQr = Set<String>()
    var qrPayloads: [String] = []
    for observation in (barcodeRequest.results ?? []) {
        guard let rawPayload = observation.payloadStringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rawPayload.isEmpty else {
            continue
        }
        if seenQr.insert(rawPayload).inserted {
            qrPayloads.append(rawPayload)
        }
    }

    return VisionOcrResult(
        text: recognizedLines.joined(separator: "\n"),
        qrPayloads: qrPayloads
    )
}

private func recognizeImage(at path: String) throws -> VisionOcrResult {
    return try autoreleasepool {
        let url = URL(fileURLWithPath: path)
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else {
            throw NSError(domain: "VisionOcr", code: 1, userInfo: [NSLocalizedDescriptionKey: "Image file not found"])
        }
        guard let cgImage = loadDownscaledCGImage(from: url) else {
            throw NSError(domain: "VisionOcr", code: 2, userInfo: [NSLocalizedDescriptionKey: "Unable to decode image"])
        }

        do {
            return try performVisionRequests(on: cgImage, cpuOnly: false)
        } catch {
            // Headless macOS VMs without Metal/ANE support may reject GPU execution; retry on CPU.
            return try performVisionRequests(on: cgImage, cpuOnly: true)
        }
    }
}

let args = Array(CommandLine.arguments.dropFirst())
var imagePath: String?
for arg in args {
    if arg == "--json" {
        continue
    } else if arg.hasPrefix("-") {
        printUsageAndExit()
    } else if imagePath == nil {
        imagePath = arg
    } else {
        printUsageAndExit()
    }
}

guard let targetPath = imagePath, !targetPath.isEmpty else {
    printUsageAndExit()
}

do {
    let result = try recognizeImage(at: targetPath)
    let encoder = JSONEncoder()
    let data = try encoder.encode(result)
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data("\n".utf8))
} catch {
    FileHandle.standardError.write(Data("Vision OCR error: \(error.localizedDescription)\n".utf8))
    exit(1)
}
