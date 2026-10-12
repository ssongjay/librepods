import Foundation

enum HandoffOwner: String, Codable, CaseIterable {
    case none
    case mac
    case phone
}

enum ConnectionState: Equatable {
    case idle
    case advertising
    case negotiating(code: String, phoneName: String)
    case pairing(code: String, phoneName: String)
    case ready(phoneName: String, code: String)
    case disconnected(String)
    case error(String)

    var summary: String {
        switch self {
        case .idle: return "대기 중"
        case .advertising: return "휴대전화에서 검색 가능"
        case .negotiating: return "보안 연결 확인 중"
        case .pairing: return "페어링 확인 필요"
        case .ready(let name, _): return "\(name) 연결됨"
        case .disconnected(let reason), .error(let reason): return reason
        }
    }
}

struct AudioDeviceChoice: Identifiable, Hashable {
    let id: String
    let name: String
}

enum SecurePayload: Equatable {
    case ready(owner: HandoffOwner?, revision: UInt64?)
    case playbackStarted
    case claim(owner: HandoffOwner, revision: UInt64)
    case yielded(revision: UInt64, success: Bool, message: String)
    case result(revision: UInt64, success: Bool, message: String)
    case status(owner: HandoffOwner, revision: UInt64, success: Bool, message: String)
}

enum HandoffError: LocalizedError {
    case invalidMessage
    case peerChanged
    case authenticationFailed
    case timeout
    case noAirPods
    case bluetoothFailed
    case routeFailed
    case cancelled

    var errorDescription: String? {
        switch self {
        case .invalidMessage: return "잘못된 연결 메시지예요"
        case .peerChanged: return "등록되지 않은 휴대전화예요"
        case .authenticationFailed: return "보안 연결을 확인할 수 없어요"
        case .timeout: return "전환 시간이 초과됐어요"
        case .noAirPods: return "선택한 AirPods를 찾을 수 없어요"
        case .bluetoothFailed: return "AirPods Bluetooth 연결에 실패했어요"
        case .routeFailed: return "Mac 오디오 출력을 바꾸지 못했어요"
        case .cancelled: return "새 재생 요청으로 전환이 바뀌었어요"
        }
    }
}
