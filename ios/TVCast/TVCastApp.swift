import SwiftUI

@main
struct TVCastApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
        .onChange(of: phase) { p in
            // iOS pauses apps in the background; keep the screen awake while sharing so the TV keeps playing.
            UIApplication.shared.isIdleTimerDisabled = (p == .active && model.sharing)
        }
    }
}
