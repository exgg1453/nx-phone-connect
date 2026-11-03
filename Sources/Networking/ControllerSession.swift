import Foundation
import Network
import UIKit

struct DiscoveredHost: Identifiable, Hashable {
    let name: String
    let endpoint: NWEndpoint

    var id: String { name }
}

enum ControllerState: Equatable {
    case idle
    case connecting
    case connected
    case failed(String)
}

final class ControllerSession: ObservableObject {
    @Published var discoveredHosts: [DiscoveredHost] = []
    @Published var state: ControllerState = .idle
    @Published var currentFrame: UIImage?

    private var browser: NWBrowser?
    private var activeConnection: FramedConnection?

    func startBrowsing() {
        guard browser == nil else {
            return
        }
        let newBrowser = NWBrowser(for: .bonjour(type: "_nxconnect._tcp", domain: nil), using: .tcp)
        newBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            let hosts = results.compactMap { result -> DiscoveredHost? in
                guard case let .service(name, _, _, _) = result.endpoint else {
                    return nil
                }
                return DiscoveredHost(name: name, endpoint: result.endpoint)
            }
            DispatchQueue.main.async {
                self?.discoveredHosts = hosts.sorted { $0.name < $1.name }
            }
        }
        newBrowser.start(queue: .main)
        browser = newBrowser
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
    }

    func connect(to host: DiscoveredHost, pairingCode: String) {
        disconnect()
        state = .connecting
        let framed = FramedConnection(connection: NWConnection(to: host.endpoint, using: .tcp))
        framed.onMessage = { [weak self] kind, payload in
            self?.handle(kind: kind, payload: payload)
        }
        framed.onStateChange = { [weak self, weak framed] connectionState in
            guard let self = self, let framed = framed else {
                return
            }
            switch connectionState {
            case .ready:
                framed.send(.hello, payload: Data(pairingCode.utf8))
            case .failed, .cancelled:
                if self.state != .idle, self.state != .failed("Wrong pairing code") {
                    self.state = .failed("Connection lost")
                }
            default:
                break
            }
        }
        activeConnection = framed
        framed.start()
    }

    func disconnect() {
        activeConnection?.onStateChange = nil
        activeConnection?.cancel()
        activeConnection = nil
        currentFrame = nil
        state = .idle
    }

    func sendTouch(phase: TouchPhase, horizontalPosition: Double, verticalPosition: Double) {
        let event = TouchEvent(
            phase: phase,
            normalizedHorizontalPosition: min(max(horizontalPosition, 0), 1),
            normalizedVerticalPosition: min(max(verticalPosition, 0), 1)
        )
        guard let payload = try? JSONEncoder().encode(event) else {
            return
        }
        activeConnection?.send(.touch, payload: payload)
    }

    func sendKey(named name: String) {
        guard let payload = try? JSONEncoder().encode(KeyEvent(name: name)) else {
            return
        }
        activeConnection?.send(.key, payload: payload)
    }

    private func handle(kind: MessageKind, payload: Data) {
        switch kind {
        case .accepted:
            state = .connected
        case .rejected:
            state = .failed("Wrong pairing code")
        case .frame:
            if let image = UIImage(data: payload) {
                currentFrame = image
            }
        default:
            break
        }
    }
}
