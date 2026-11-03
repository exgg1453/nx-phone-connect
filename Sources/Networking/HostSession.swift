import CoreImage
import Foundation
import Network
import ReplayKit
import UIKit

final class HostSession: ObservableObject {
    @Published var pairingCode = ""
    @Published var isSharing = false
    @Published var statusText = "Not sharing"
    @Published var lastTouchPosition: CGPoint?

    private var listener: NWListener?
    private var activeConnection: FramedConnection?
    private var authenticatedConnection: FramedConnection?
    private let imageContext = CIContext()
    private let maximumFrameWidth: CGFloat = 720
    private let minimumFrameInterval: TimeInterval = 0.2
    private var lastFrameDate = Date.distantPast

    func start() {
        guard !isSharing else {
            return
        }
        pairingCode = String(format: "%06d", Int.random(in: 0...999_999))
        do {
            let newListener = try NWListener(using: .tcp)
            newListener.service = NWListener.Service(name: UIDevice.current.name, type: "_nxconnect._tcp")
            newListener.newConnectionHandler = { [weak self] connection in
                DispatchQueue.main.async {
                    self?.accept(connection)
                }
            }
            newListener.start(queue: .main)
            listener = newListener
            isSharing = true
            statusText = "Waiting for a controller"
        } catch {
            statusText = "Could not start sharing"
        }
    }

    func stop() {
        RPScreenRecorder.shared().stopCapture { _ in }
        authenticatedConnection = nil
        activeConnection?.cancel()
        activeConnection = nil
        listener?.cancel()
        listener = nil
        isSharing = false
        lastTouchPosition = nil
        statusText = "Not sharing"
    }

    private func accept(_ connection: NWConnection) {
        activeConnection?.cancel()
        authenticatedConnection = nil
        let framed = FramedConnection(connection: connection)
        framed.onMessage = { [weak self, weak framed] kind, payload in
            guard let self = self, let framed = framed else {
                return
            }
            self.handle(kind: kind, payload: payload, from: framed)
        }
        framed.onStateChange = { [weak self, weak framed] state in
            guard let self = self, let framed = framed else {
                return
            }
            switch state {
            case .failed, .cancelled:
                if self.activeConnection === framed {
                    self.controllerDisconnected()
                }
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
            let receivedCode = String(data: payload, encoding: .utf8)
            if receivedCode == pairingCode {
                authenticatedConnection = connection
                connection.send(.accepted)
                statusText = "Controller connected"
                startCapture()
            } else {
                connection.send(.rejected)
                connection.cancel()
            }
        case .touch:
            guard authenticatedConnection === connection,
                  let event = try? JSONDecoder().decode(TouchEvent.self, from: payload) else {
                return
            }
            lastTouchPosition = event.phase == .ended
                ? nil
                : CGPoint(x: event.normalizedHorizontalPosition, y: event.normalizedVerticalPosition)
        default:
            break
        }
    }

    private func controllerDisconnected() {
        RPScreenRecorder.shared().stopCapture { _ in }
        authenticatedConnection = nil
        activeConnection = nil
        lastTouchPosition = nil
        statusText = isSharing ? "Waiting for a controller" : "Not sharing"
    }

    private func startCapture() {
        let recorder = RPScreenRecorder.shared()
        guard recorder.isAvailable, !recorder.isRecording else {
            return
        }
        recorder.startCapture(handler: { [weak self] sampleBuffer, bufferType, _ in
            guard bufferType == .video else {
                return
            }
            self?.process(sampleBuffer)
        }, completionHandler: { _ in })
    }

    private func process(_ sampleBuffer: CMSampleBuffer) {
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
            self?.authenticatedConnection?.send(.frame, payload: frameData)
        }
    }
}
