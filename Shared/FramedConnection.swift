import Foundation
import Network

final class FramedConnection {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.nxteam.nxconnect.connection")
    private let headerLength = 5
    private let maximumPayloadLength = 8_000_000

    static func parameters() -> NWParameters {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        return NWParameters(tls: nil, tcp: tcpOptions)
    }

    var onMessage: ((MessageKind, Data) -> Void)?
    var onStateChange: ((NWConnection.State) -> Void)?

    init(connection: NWConnection) {
        self.connection = connection
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                self?.onStateChange?(state)
            }
            if case .ready = state {
                self?.receiveHeader()
            }
        }
        connection.start(queue: queue)
    }

    func cancel() {
        connection.stateUpdateHandler = nil
        connection.cancel()
    }

    func send(_ kind: MessageKind, payload: Data = Data(), completion: (() -> Void)? = nil) {
        var packet = Data([kind.rawValue])
        var length = UInt32(payload.count).bigEndian
        packet.append(Data(bytes: &length, count: 4))
        packet.append(payload)
        connection.send(content: packet, completion: .contentProcessed { _ in
            completion?()
        })
    }

    private func receiveHeader() {
        connection.receive(minimumIncompleteLength: headerLength, maximumLength: headerLength) { [weak self] data, _, _, error in
            guard let self = self, let data = data, data.count == self.headerLength, error == nil else {
                self?.finish()
                return
            }
            let bytes = [UInt8](data)
            let length = bytes[1...4].reduce(0) { ($0 << 8) | Int($1) }
            self.receivePayload(kindValue: bytes[0], length: length)
        }
    }

    private func receivePayload(kindValue: UInt8, length: Int) {
        guard length <= maximumPayloadLength else {
            finish()
            return
        }
        if length == 0 {
            deliver(kindValue: kindValue, payload: Data())
            receiveHeader()
            return
        }
        connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, _, error in
            guard let self = self, let data = data, data.count == length, error == nil else {
                self?.finish()
                return
            }
            self.deliver(kindValue: kindValue, payload: data)
            self.receiveHeader()
        }
    }

    private func deliver(kindValue: UInt8, payload: Data) {
        guard let kind = MessageKind(rawValue: kindValue) else {
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.onMessage?(kind, payload)
        }
    }

    private func finish() {
        connection.cancel()
    }
}
