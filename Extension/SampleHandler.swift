import CoreImage
import Network
import ReplayKit
import UIKit

final class SampleHandler: RPBroadcastSampleHandler {
    private var listener: NWListener?
    private var activeConnection: FramedConnection?
    private var authenticatedConnection: FramedConnection?
    private var pairingCode = ""
    private let controllerLock = NSLock()
    private var hasController = false
    private let imageContext = CIContext(options: [.cacheIntermediates: false, .useSoftwareRenderer: false])
    private let maximumFrameWidth: CGFloat = 720
    private let minimumFrameInterval: TimeInterval = 0.2
    private var lastFrameDate = Date.distantPast

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
        guard sampleBufferType == .video, controllerIsConnected() else {
            return
        }
        let now = Date()
        guard now.timeIntervalSince(lastFrameDate) >= minimumFrameInterval,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }
        lastFrameDate = now
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        let scale = min(1, maximumFrameWidth / image.extent.width)
        let scaledImage = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let options = [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.45]
        guard let frameData = imageContext.jpegRepresentation(of: scaledImage, colorSpace: colorSpace, options: options) else {
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.authenticatedConnection?.sendDroppingIfBusy(.frame, payload: frameData)
        }
    }

    private func controllerIsConnected() -> Bool {
        controllerLock.lock()
        defer { controllerLock.unlock() }
        return hasController
    }

    private func updateControllerConnected(_ value: Bool) {
        controllerLock.lock()
        hasController = value
        controllerLock.unlock()
    }

    private func accept(_ connection: NWConnection) {
        activeConnection?.cancel()
        authenticatedConnection = nil
        updateControllerConnected(false)
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
                self.updateControllerConnected(false)
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
                updateControllerConnected(true)
                connection.send(.accepted)
                SharedState.statusText = "Controller connected"
            } else {
                connection.send(.rejected)
                connection.cancel()
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
