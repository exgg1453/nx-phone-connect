import ReplayKit
import SwiftUI

struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 72, height: 72))
        picker.preferredExtension = "com.nxteam.nxconnect.broadcast"
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}

struct HostView: View {
    @State private var pairingCode = ""
    @State private var statusText = "Not sharing"
    @State private var touchPosition: (horizontal: Double, vertical: Double)?

    private let refreshTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 24) {
            Text(statusText)
                .font(.headline)

            if pairingCode.isEmpty {
                Text("Tap the button below, choose NX Phone Connect Broadcast and start the broadcast to get a pairing code.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                VStack(spacing: 8) {
                    Text("Pairing code")
                        .foregroundColor(.secondary)
                    Text(pairingCode)
                        .font(.system(size: 44, weight: .bold, design: .monospaced))
                }
            }

            if let position = touchPosition {
                Text(String(format: "Remote touch at %.2f, %.2f", position.horizontal, position.vertical))
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }

            BroadcastPicker()
                .frame(width: 72, height: 72)

            Text("Warning: a controller on Android can view your iPhone screen but cannot control it. iOS does not allow remote input.")
                .font(.footnote)
                .foregroundColor(.orange)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .padding()
        .navigationTitle("Share this device")
        .onAppear(perform: refresh)
        .onReceive(refreshTimer) { _ in refresh() }
    }

    private func refresh() {
        pairingCode = SharedState.pairingCode
        statusText = SharedState.statusText
        touchPosition = SharedState.touchPosition
    }
}
