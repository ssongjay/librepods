import CryptoKit
import Foundation

struct HelloMessage: Codable {
    let type: String
    let v: Int
    let publicKey: String
    let nonce: String
    let name: String
}

private struct SecureEnvelope: Codable {
    let type: String
    let seq: UInt64
    let data: String
}

private struct WirePayload: Codable {
    let type: String
    var owner: String?
    var revision: UInt64?
    var success: Bool?
    var message: String?

    init(type: String, owner: String? = nil, revision: UInt64? = nil, success: Bool? = nil, message: String? = nil) {
        self.type = type
        self.owner = owner
        self.revision = revision
        self.success = success
        self.message = message
    }
}

final class CryptoSession {
    static let spkiPrefix = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d,
        0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01,
        0x07, 0x03, 0x42, 0x00
    ])

    let phonePublicDER: Data
    let phoneName: String
    let sas: String
    let macHello: HelloMessage

    private let outgoingKey: SymmetricKey
    private let incomingKey: SymmetricKey
    private(set) var outgoingSequence: UInt64 = 0
    private(set) var incomingSequence: UInt64 = 0

    init(identity: P256.KeyAgreement.PrivateKey, phoneHello: HelloMessage) throws {
        guard phoneHello.type == "hello", phoneHello.v == 1,
              let phoneDER = Data(base64Encoded: phoneHello.publicKey),
              let phoneNonce = Data(base64Encoded: phoneHello.nonce), phoneNonce.count == 32,
              phoneDER.starts(with: Self.spkiPrefix), phoneDER.count == 91 else {
            throw HandoffError.invalidMessage
        }
        let x963 = phoneDER.dropFirst(Self.spkiPrefix.count)
        let phonePublic = try P256.KeyAgreement.PublicKey(x963Representation: x963)
        let macDER = Self.spkiPrefix + identity.publicKey.x963Representation
        var macNonce = Data(count: 32)
        let randomStatus = macNonce.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw HandoffError.authenticationFailed }

        let shared = try identity.sharedSecretFromKeyAgreement(with: phonePublic)
        let transcript = macDER + phoneDER + macNonce + phoneNonce
        let salt = Data(SHA256.hash(data: transcript))
        outgoingKey = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("LPH1 mac-to-phone".utf8),
            outputByteCount: 32
        )
        incomingKey = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("LPH1 phone-to-mac".utf8),
            outputByteCount: 32
        )
        let pairingKey = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("LPH1 pairing".utf8),
            outputByteCount: 32
        )
        let sasBytes = pairingKey.withUnsafeBytes { Array($0.prefix(10)) }
        sas = Self.visualConfirmationCode(sasBytes)
        phonePublicDER = phoneDER
        phoneName = String(phoneHello.name.prefix(60))
        macHello = HelloMessage(
            type: "hello",
            v: 1,
            publicKey: macDER.base64EncodedString(),
            nonce: macNonce.base64EncodedString(),
            name: Host.current().localizedName ?? "Mac"
        )
    }

    func encrypt(_ payload: SecurePayload) throws -> Data {
        guard outgoingSequence < UInt64(Int64.max - 1) else { throw HandoffError.authenticationFailed }
        outgoingSequence &+= 1
        guard outgoingSequence > 0 else { throw HandoffError.authenticationFailed }
        let clear = try JSONEncoder().encode(wirePayload(payload))
        let aad = Data("LPH1:\(outgoingSequence)".utf8)
        let sealed = try AES.GCM.seal(clear, using: outgoingKey, authenticating: aad)
        guard let combined = sealed.combined else { throw HandoffError.authenticationFailed }
        return try JSONEncoder().encode(SecureEnvelope(
            type: "secure",
            seq: outgoingSequence,
            data: combined.base64EncodedString()
        ))
    }

    func decrypt(_ data: Data) throws -> SecurePayload {
        let envelope = try JSONDecoder().decode(SecureEnvelope.self, from: data)
        guard incomingSequence < UInt64(Int64.max - 1), envelope.seq <= UInt64(Int64.max - 1),
              envelope.type == "secure", envelope.seq == incomingSequence + 1,
              let combined = Data(base64Encoded: envelope.data) else {
            throw HandoffError.authenticationFailed
        }
        let box = try AES.GCM.SealedBox(combined: combined)
        let clear = try AES.GCM.open(
            box,
            using: incomingKey,
            authenticating: Data("LPH1:\(envelope.seq)".utf8)
        )
        incomingSequence = envelope.seq
        return try parseWirePayload(JSONDecoder().decode(WirePayload.self, from: clear))
    }

    private func wirePayload(_ payload: SecurePayload) -> WirePayload {
        switch payload {
        case .ready(let owner, let revision):
            return WirePayload(type: "ready", owner: owner?.rawValue, revision: revision)
        case .playbackStarted:
            return WirePayload(type: "playback_started")
        case .claim(let owner, let revision):
            return WirePayload(type: "claim", owner: owner.rawValue, revision: revision)
        case .yielded(let revision, let success, let message):
            return WirePayload(type: "yielded", revision: revision, success: success, message: message)
        case .result(let revision, let success, let message):
            return WirePayload(type: "result", revision: revision, success: success, message: message)
        case .status(let owner, let revision, let success, let message):
            return WirePayload(type: "status", owner: owner.rawValue, revision: revision, success: success, message: message)
        }
    }

    private func parseWirePayload(_ payload: WirePayload) throws -> SecurePayload {
        switch payload.type {
        case "ready":
            return .ready(owner: payload.owner.flatMap(HandoffOwner.init), revision: payload.revision)
        case "playback_started":
            return .playbackStarted
        case "claim":
            guard let owner = payload.owner.flatMap(HandoffOwner.init), let revision = payload.revision else { break }
            return .claim(owner: owner, revision: revision)
        case "yielded":
            guard let revision = payload.revision, let success = payload.success else { break }
            return .yielded(revision: revision, success: success, message: Self.shortReason(payload.message))
        case "result":
            guard let revision = payload.revision, let success = payload.success else { break }
            return .result(revision: revision, success: success, message: Self.shortReason(payload.message))
        case "status":
            guard let owner = payload.owner.flatMap(HandoffOwner.init), let revision = payload.revision,
                  let success = payload.success else { break }
            return .status(owner: owner, revision: revision, success: success, message: Self.shortReason(payload.message))
        default:
            break
        }
        throw HandoffError.invalidMessage
    }

    private static func shortReason(_ value: String?) -> String {
        let compact = (value ?? "")
            .split(whereSeparator: \Character.isWhitespace)
            .joined(separator: " ")
        let addressPattern = #"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"#
        if compact.contains("/") || compact.contains("\\") || compact.range(of: addressPattern, options: .regularExpression) != nil {
            return "휴대전화에서 전환을 완료하지 못했어요"
        }
        return String(compact.prefix(100))
    }

    private static func visualConfirmationCode(_ bytes: [UInt8]) -> String {
        precondition(bytes.count == 10)
        let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        var encoded = ""
        encoded.reserveCapacity(16)
        var accumulator: UInt32 = 0
        var bitCount = 0
        for byte in bytes {
            accumulator = (accumulator << 8) | UInt32(byte)
            bitCount += 8
            while bitCount >= 5 {
                bitCount -= 5
                let index = Int((accumulator >> UInt32(bitCount)) & 0x1f)
                encoded.append(alphabet[index])
                if bitCount == 0 {
                    accumulator = 0
                } else {
                    accumulator &= (1 << UInt32(bitCount)) - 1
                }
            }
        }
        return stride(from: 0, to: encoded.count, by: 4)
            .map { start in
                let lower = encoded.index(encoded.startIndex, offsetBy: start)
                let upper = encoded.index(lower, offsetBy: min(4, encoded.count - start))
                return String(encoded[lower..<upper])
            }
            .joined(separator: "-")
    }
}
