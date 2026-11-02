// macOS-only build helper: creates an original silent test pattern, never downloads media.
import AVFoundation
import CoreVideo
import Foundation

let output = URL(fileURLWithPath: CommandLine.arguments[1])
guard !FileManager.default.fileExists(atPath: output.path) else {
    fatalError("Refusing to overwrite existing fixture: \(output.path)")
}
let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 640, AVVideoHeightKey: 360,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 350_000]
])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
    sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
        kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360])
writer.add(input)
guard writer.startWriting() else { fatalError("\(String(describing: writer.error))") }
writer.startSession(atSourceTime: .zero)
for frame in 0..<120 {
    while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
    var pixelBuffer: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, 640, 360, kCVPixelFormatType_32ARGB, nil, &pixelBuffer)
    let buffer = pixelBuffer!
    CVPixelBufferLockBaseAddress(buffer, [])
    let bytes = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(buffer)
    for y in 0..<360 {
        for x in 0..<640 {
            let i = y * stride + x * 4
            let marker = abs(x - frame * 640 / 120) < 8
            bytes[i] = 255
            bytes[i + 1] = marker ? 240 : UInt8(20 + x / 8)
            bytes[i + 2] = marker ? 240 : UInt8(35 + y / 4)
            bytes[i + 3] = marker ? 240 : 130
        }
    }
    CVPixelBufferUnlockBaseAddress(buffer, [])
    guard adaptor.append(buffer, withPresentationTime: CMTime(value: Int64(frame), timescale: 24)) else {
        fatalError("\(String(describing: writer.error))")
    }
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { fatalError("\(String(describing: writer.error))") }
print("Generated original 5-second silent fixture: \(output.path)")
