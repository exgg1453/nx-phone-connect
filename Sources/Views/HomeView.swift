import SwiftUI

struct HomeView: View {
    var body: some View {
        NavigationView {
            List {
                Section(footer: Text("Pick this device's role. Both devices must be on the same network.")) {
                    NavigationLink(destination: ControllerView()) {
                        Label("Control another device", systemImage: "hand.tap")
                    }
                    NavigationLink(destination: HostView()) {
                        Label("Share this device", systemImage: "rectangle.on.rectangle")
                    }
                }
            }
            .navigationTitle("NX Phone Connect")
        }
        .navigationViewStyle(.stack)
    }
}
