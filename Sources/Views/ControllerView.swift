import SwiftUI

struct ControllerView: View {
    @StateObject private var session = ControllerSession()
    @State private var selectedHost: DiscoveredHost?
    @State private var pairingCode = ""
    @State private var isTouching = false
    @State private var isFullScreen = false

    var body: some View {
        Group {
            if session.state == .connected {
                remoteScreen
            } else {
                connectionForm
            }
        }
        .navigationTitle("Control another device")
        .onAppear { session.startBrowsing() }
        .onDisappear {
            session.disconnect()
            session.stopBrowsing()
        }
    }

    private var connectionForm: some View {
        List {
            Section(header: Text("Devices on your network")) {
                if session.discoveredHosts.isEmpty {
                    Text("Searching...")
                        .foregroundColor(.secondary)
                }
                ForEach(session.discoveredHosts) { host in
                    Button {
                        selectedHost = host
                    } label: {
                        HStack {
                            Text(host.name)
                            Spacer()
                            if selectedHost == host {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }

            Section(header: Text("Pairing code")) {
                TextField("6-digit code", text: $pairingCode)
                    .keyboardType(.numberPad)
            }

            Section(footer: statusFooter) {
                Button("Connect") {
                    if let host = selectedHost {
                        session.connect(to: host, pairingCode: pairingCode)
                    }
                }
                .disabled(selectedHost == nil || pairingCode.count != 6 || session.state == .connecting)
            }
        }
    }

    private var statusFooter: some View {
        switch session.state {
        case .connecting:
            return Text("Connecting...")
        case .failed(let message):
            return Text(message).foregroundColor(.red)
        default:
            return Text("")
        }
    }

    private var remoteScreen: some View {
        ZStack(alignment: .topTrailing) {
            Color.black
                .ignoresSafeArea()
            VStack(spacing: 0) {
                Group {
                    if let frame = session.currentFrame {
                        Image(uiImage: frame)
                            .resizable()
                            .scaledToFit()
                            .overlay(touchSurface)
                    } else {
                        ProgressView("Waiting for the first frame...")
                            .foregroundColor(.white)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if !isFullScreen {
                    controlBar
                }
            }
            Button {
                withAnimation {
                    isFullScreen.toggle()
                }
            } label: {
                Image(systemName: isFullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(10)
                    .background(Circle().fill(Color.black.opacity(0.5)))
            }
            .padding(12)
        }
        .navigationBarHidden(isFullScreen)
        .statusBar(hidden: isFullScreen)
        .ignoresSafeArea(.container, edges: isFullScreen ? .all : [])
    }

    private var controlBar: some View {
        HStack(spacing: 24) {
            Button("Back") { session.sendKey(named: "back") }
            Button("Home") { session.sendKey(named: "home") }
            Button("Recents") { session.sendKey(named: "recents") }
            Button("Disconnect", role: .destructive) {
                isFullScreen = false
                session.disconnect()
            }
        }
        .padding()
    }

    private var touchSurface: some View {
        GeometryReader { proxy in
            Color.clear
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let phase: TouchPhase = isTouching ? .moved : .began
                            isTouching = true
                            session.sendTouch(
                                phase: phase,
                                horizontalPosition: value.location.x / proxy.size.width,
                                verticalPosition: value.location.y / proxy.size.height
                            )
                        }
                        .onEnded { value in
                            isTouching = false
                            session.sendTouch(
                                phase: .ended,
                                horizontalPosition: value.location.x / proxy.size.width,
                                verticalPosition: value.location.y / proxy.size.height
                            )
                        }
                )
        }
    }
}
