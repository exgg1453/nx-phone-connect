import Foundation

enum MessageKind: UInt8 {
    case hello = 1
    case accepted = 2
    case rejected = 3
    case frame = 4
    case touch = 5
    case key = 6
    case frameAcknowledged = 7
}

enum TouchPhase: String, Codable {
    case began
    case moved
    case ended
}

struct TouchEvent: Codable {
    let phase: TouchPhase
    let normalizedHorizontalPosition: Double
    let normalizedVerticalPosition: Double
}

struct KeyEvent: Codable {
    let name: String
}
