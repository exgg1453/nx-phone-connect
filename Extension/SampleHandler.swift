import CoreImage
import Network
import ReplayKit
import UIKit

final class SampleHandler: RPBroadcastSampleHandler {
    private var listener: NWListener?
    private var activeConnection: FramedConnection?
    private var authenticatedConnection: FramedConnection?
    private var pairingCode = ""

    private let frameQueue = DispatchQueue(label: "com.nxteam.nxconnect.frames", qos: .userInteractive)
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private let maximumFrameLongSide: CGFloat = 1280
    private var pendingOrientation: CGImagePropertyOrientation = .up
    private let minimumFrameInterval: TimeInterval = 0.05
    private let acknowledgementTimeout: TimeInterval = 1
    private var frameConnection: FramedConnection?
    private var pendingPixelBuffer: CVPixelBuffer?
    private var lastFrameDate = Date.distantPast
    private var isSendingFrame = false
    private var isAwaitingAcknowledgement = false
    private var isFlushScheduled = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        pairingCode = String(format: "%06d", Int.random(in: 0...999_999))
        SharedState.pairingCode = pairingCode
        SharedState.statusText = "Waiting for a controller"
        do {
            let newListener = try NWListener(using: FramedConnection.parameters())
            newListener.service = NWListener.Service(name: UIDevice.current.name, type: "_nxconnect._tcp")
            newListener.newConnectionHandler = { [weak self] connection in
                DispatchQueue.main.async {
                    self?.accept(connection)
                }
            }
            newListener.start(queue: .main)
            listener = newListener
        } catch {
            finishBroadcastWithError(error)
        }
    }

    override func broadcastFinished() {
        activeConnection?.cancel()
        listener?.cancel()
        SharedState.reset()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }
        var orientation = CGImagePropertyOrientation.up
        if let value = CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
           let attachedOrientation = CGImagePropertyOrientation(rawValue: value.uint32Value) {
            orientation = attachedOrientation
        }
        frameQueue.async { [weak self] in
            guard let self = self, self.frameConnection != nil else {
                return
            }
            self.pendingPixelBuffer = pixelBuffer
            self.pendingOrientation = orientation
            self.flushPendingFrame()
        }
    }

    private func flushPendingFrame() {
        guard let connection = frameConnection, let pixelBuffer = pendingPixelBuffer, !isSendingFrame else {
            return
        }
        let elapsed = Date().timeIntervalSince(lastFrameDate)
        var wait = minimumFrameInterval - elapsed
        if isAwaitingAcknowledgement {
            wait = max(wait, acknowledgementTimeout - elapsed)
        }
        if wait > 0 {
            if !isFlushScheduled {
                isFlushScheduled = true
                frameQueue.asyncAfter(deadline: .now() + wait) { [weak self] in
                    self?.isFlushScheduled = false
                    self?.flushPendingFrame()
                }
            }
            return
        }
        pendingPixelBuffer = nil
        lastFrameDate = Date()
        guard let frameData = encode(pixelBuffer, orientation: pendingOrientation) else {
            return
        }
        isSendingFrame = true
        isAwaitingAcknowledgement = true
        connection.send(.frame, payload: frameData) { [weak self] in
            self?.frameQueue.async {
                self?.isSendingFrame = false
                self?.flushPendingFrame()
            }
        }
    }

    private func encode(_ pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> Data? {
        let image = CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation)
        let longSide = max(image.extent.width, image.extent.height)
        let scale = min(1, maximumFrameLongSide / longSide)
        let scaledImage = image
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: -image.extent.origin.x * scale, y: -image.extent.origin.y * scale))
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let options = [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.4]
        return imageContext.jpegRepresentation(of: scaledImage, colorSpace: colorSpace, options: options)
    }

    private func updateFrameConnection(_ connection: FramedConnection?) {
        frameQueue.async { [weak self] in
            self?.frameConnection = connection
            self?.pendingPixelBuffer = nil
            self?.isSendingFrame = false
            self?.isAwaitingAcknowledgement = false
        }
    }

    private func accept(_ connection: NWConnection) {
        activeConnection?.cancel()
        authenticatedConnection = nil
        updateFrameConnection(nil)
        let framed = FramedConnection(connection: connection)
        framed.onMessage = { [weak self, weak framed] kind, payload in
            guard let self = self, let framed = framed else {
                return
            }
            self.handle(kind: kind, payload: payload, from: framed)
        }
        framed.onStateChange = { [weak self, weak framed] state in
            guard let self = self, let framed = framed, self.activeConnection === framed else {
                return
            }
            switch state {
            case .failed, .cancelled:
                self.authenticatedConnection = nil
                self.activeConnection = nil
                self.updateFrameConnection(nil)
                SharedState.statusText = "Waiting for a controller"
                SharedState.touchPosition = nil
            default:
                break
            }
        }
        activeConnection = framed
        framed.start()
    }

    private func handle(kind: MessageKind, payload: Data, from connection: FramedConnection) {
        switch kind {
        case .hello:
            if String(data: payload, encoding: .utf8) == pairingCode {
                authenticatedConnection = connection
                connection.send(.accepted)
                updateFrameConnection(connection)
                SharedState.statusText = "Controller connected"
            } else {
                connection.send(.rejected)
                connection.cancel()
            }
        case .frameAcknowledged:
            frameQueue.async { [weak self] in
                self?.isAwaitingAcknowledgement = false
                self?.flushPendingFrame()
            }
        case .touch:
            guard authenticatedConnection === connection,
                  let event = try? JSONDecoder().decode(TouchEvent.self, from: payload) else {
                return
            }
            SharedState.touchPosition = event.phase == .ended
                ? nil
                : (event.normalizedHorizontalPosition, event.normalizedVerticalPosition)
        default:
            break
        }
    }
}
