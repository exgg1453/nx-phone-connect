import SwiftUI

struct HostView: View {
    @StateObject private var session = HostSession()

    var body: some View {
        VStack(spacing: 24) {
            Text(session.statusText)
                .font(.headline)

            if session.isSharing {
                VStack(spacing: 8) {
                    Text("Pairing code")
                        .foregroundColor(.secondary)
                    Text(session.pairingCode)
                        .font(.system(size: 44, weight: .bold, design: .monospaced))
                }
                if let position = session.lastTouchPosition {
                    Text(String(format: "Remote touch at %.2f, %.2f", position.x, position.y))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }

            Button(session.isSharing ? "Stop sharing" : "Start sharing") {
                if session.isSharing {
                    session.stop()
                } else {
                    session.start()
                }
            }
            .buttonStyle(.borderedProminent)

            Text("Only this app's own screen is shared. iOS does not allow one device to control another iPhone.")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .padding()
        .navigationTitle("Share this device")
        .onDisappear {
            session.stop()
        }
    }
}
