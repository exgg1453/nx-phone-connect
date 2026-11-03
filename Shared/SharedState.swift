import Foundation

enum SharedState {
    static let groupIdentifier = "group.com.nxteam.nxconnect"

    private static let defaults = UserDefaults(suiteName: groupIdentifier)
    private static let pairingCodeKey = "pairingCode"
    private static let statusTextKey = "statusText"
    private static let touchHorizontalKey = "touchHorizontal"
    private static let touchVerticalKey = "touchVertical"

    static var pairingCode: String {
        get { defaults?.string(forKey: pairingCodeKey) ?? "" }
        set { defaults?.set(newValue, forKey: pairingCodeKey) }
    }

    static var statusText: String {
        get { defaults?.string(forKey: statusTextKey) ?? "Not sharing" }
        set { defaults?.set(newValue, forKey: statusTextKey) }
    }

    static var touchPosition: (horizontal: Double, vertical: Double)? {
        get {
            guard let defaults = defaults, defaults.object(forKey: touchHorizontalKey) != nil else {
                return nil
            }
            return (defaults.double(forKey: touchHorizontalKey), defaults.double(forKey: touchVerticalKey))
        }
        set {
            if let position = newValue {
                defaults?.set(position.horizontal, forKey: touchHorizontalKey)
                defaults?.set(position.vertical, forKey: touchVerticalKey)
            } else {
                defaults?.removeObject(forKey: touchHorizontalKey)
                defaults?.removeObject(forKey: touchVerticalKey)
            }
        }
    }

    static func reset() {
        pairingCode = ""
        statusText = "Not sharing"
        touchPosition = nil
    }
}
