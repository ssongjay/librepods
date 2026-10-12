import CryptoKit
import Foundation
import Security

final class Preferences {
    private enum Key {
        static let enabled = "handoffEnabled"
        static let selectedAirPods = "selectedAirPodsAddress"
        static let pinnedPhone = "pinnedPhonePublicKey"
        static let pinnedPhoneName = "pinnedPhoneName"
        static let owner = "handoffOwner"
        static let revision = "handoffRevision"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var enabled: Bool {
        get { defaults.bool(forKey: Key.enabled) }
        set { defaults.set(newValue, forKey: Key.enabled) }
    }

    var selectedAirPodsAddress: String? {
        get { defaults.string(forKey: Key.selectedAirPods) }
        set { defaults.set(newValue, forKey: Key.selectedAirPods) }
    }

    var pinnedPhoneKey: Data? {
        get { defaults.data(forKey: Key.pinnedPhone) }
        set { defaults.set(newValue, forKey: Key.pinnedPhone) }
    }

    var pinnedPhoneName: String? {
        get { defaults.string(forKey: Key.pinnedPhoneName) }
        set { defaults.set(newValue, forKey: Key.pinnedPhoneName) }
    }

    var owner: HandoffOwner {
        get { HandoffOwner(rawValue: defaults.string(forKey: Key.owner) ?? "") ?? .none }
        set { defaults.set(newValue.rawValue, forKey: Key.owner) }
    }

    var revision: UInt64 {
        get {
            guard let number = defaults.object(forKey: Key.revision) as? NSNumber else { return 0 }
            let stored = number.int64Value
            return stored >= 0 ? UInt64(stored) : UInt64(Int64.max)
        }
        set { defaults.set(Int64(min(newValue, UInt64(Int64.max))), forKey: Key.revision) }
    }

    func forgetPhone() {
        defaults.removeObject(forKey: Key.pinnedPhone)
        defaults.removeObject(forKey: Key.pinnedPhoneName)
    }
}

enum KeychainIdentity {
    private static let service = "me.ssongjay.librepods-my.mac.identity"
    private static let account = "p256-key-agreement"

    static func loadOrCreate() throws -> P256.KeyAgreement.PrivateKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data {
            return try P256.KeyAgreement.PrivateKey(rawRepresentation: data)
        }
        guard status == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }

        let key = P256.KeyAgreement.PrivateKey()
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: key.rawRepresentation
        ]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(addStatus))
        }
        return key
    }
}
