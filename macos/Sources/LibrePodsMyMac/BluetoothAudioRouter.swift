import CoreAudio
import Foundation
import IOBluetooth

@MainActor
final class BluetoothAudioRouter {
    private static let connectionPageTimeout: BluetoothHCIPageTimeout = 0x2000

    private final class PendingConnection {
        let token = UUID()
        let address: String
        let device: IOBluetoothDevice
        let target: ConnectionCompletionTarget
        var result: IOReturn?
        var closeWhenComplete = false

        init(address: String, device: IOBluetoothDevice, target: ConnectionCompletionTarget) {
            self.address = address
            self.device = device
            self.target = target
        }
    }

    private var pendingConnection: PendingConnection?

    func cancelPendingConnection() {
        guard let pending = pendingConnection else { return }
        pending.closeWhenComplete = true
        guard pending.result != nil else { return }
        if pending.device.isConnected() { pending.device.closeConnection() }
        pendingConnection = nil
    }

    func pairedAirPods() -> [AudioDeviceChoice] {
        let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        return devices.compactMap { device in
            guard device.deviceClassMajor == kBluetoothDeviceClassMajorAudio,
                  let name = device.name, !name.isEmpty,
                  let address = device.addressString else { return nil }
            return AudioDeviceChoice(id: address, name: name)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func disconnectAndWait(address: String?, isCurrent: @escaping @MainActor () -> Bool) async throws {
        guard isCurrent() else { throw HandoffError.cancelled }
        var devicesToClose: [IOBluetoothDevice] = []
        if let pending = pendingConnection {
            do {
                try await waitForPendingSettlementBeforeDisconnect(
                    pending,
                    timeout: 10,
                    isCurrent: isCurrent
                )
                guard isCurrent() else { throw HandoffError.cancelled }
                if pendingConnection?.token == pending.token { pendingConnection = nil }
                devicesToClose.append(pending.device)
            } catch is CancellationError {
                throw HandoffError.cancelled
            } catch {
                guard isCurrent() else { throw HandoffError.cancelled }
                abandonPendingConnection(token: pending.token)
                throw error
            }
        }

        if let address, let selected = IOBluetoothDevice(addressString: address),
           !devicesToClose.contains(where: { $0.addressString == selected.addressString }) {
            devicesToClose.append(selected)
        }

        for device in devicesToClose where device.isConnected() {
            guard isCurrent() else { throw HandoffError.cancelled }
            device.closeConnection()
        }

        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline {
            try Task.checkCancellation()
            guard isCurrent() else { throw HandoffError.cancelled }
            if devicesToClose.allSatisfy({ !$0.isConnected() }) { return }
            try await Task.sleep(for: .milliseconds(150))
        }
        if devicesToClose.contains(where: { $0.isConnected() }) { throw HandoffError.timeout }
    }

    func connectAndSelect(address: String, isCurrent: @escaping @MainActor () -> Bool) async throws {
        guard let device = IOBluetoothDevice(addressString: address),
              let name = device.name else { throw HandoffError.noAirPods }
        try Task.checkCancellation()
        guard isCurrent() else { throw HandoffError.cancelled }
        if !device.isConnected() {
            if let pending = pendingConnection, pending.address != address {
                do {
                    _ = try await waitForPendingConnection(
                        token: pending.token,
                        timeout: 10,
                        isCurrent: isCurrent
                    )
                    guard isCurrent() else { throw HandoffError.cancelled }
                    if pending.device.isConnected() { pending.device.closeConnection() }
                    if pendingConnection?.token == pending.token { pendingConnection = nil }
                } catch is CancellationError {
                    throw HandoffError.cancelled
                } catch {
                    guard isCurrent() else { throw HandoffError.cancelled }
                    abandonPendingConnection(token: pending.token)
                    throw error
                }
            }

            let pending: PendingConnection
            if let existing = pendingConnection, existing.address == address {
                guard isCurrent() else { throw HandoffError.cancelled }
                existing.closeWhenComplete = false
                pending = existing
            } else {
                let target = ConnectionCompletionTarget { [weak self] token, callbackDevice, status in
                    Task { @MainActor in
                        self?.connectionCompleted(token: token, device: callbackDevice, status: status)
                    }
                }
                let command = PendingConnection(address: address, device: device, target: target)
                target.token = command.token
                pendingConnection = command
                let issueStatus = device.openConnection(
                    target,
                    withPageTimeout: Self.connectionPageTimeout,
                    authenticationRequired: true
                )
                guard issueStatus == kIOReturnSuccess else {
                    if pendingConnection?.token == command.token { pendingConnection = nil }
                    if !device.isConnected() { throw HandoffError.bluetoothFailed }
                    return try await selectOutput(
                        device: device,
                        name: name,
                        address: address,
                        isCurrent: isCurrent
                    )
                }
                pending = command
            }

            let completionStatus: IOReturn
            do {
                completionStatus = try await waitForPendingConnection(
                    token: pending.token,
                    timeout: 10,
                    isCurrent: isCurrent
                )
            } catch is CancellationError {
                throw HandoffError.cancelled
            } catch {
                guard isCurrent() else { throw HandoffError.cancelled }
                abandonPendingConnection(token: pending.token)
                throw error
            }
            guard isCurrent() else { throw HandoffError.cancelled }
            if pendingConnection?.token == pending.token { pendingConnection = nil }
            guard completionStatus == kIOReturnSuccess || device.isConnected() else {
                throw HandoffError.bluetoothFailed
            }
        }

        try await selectOutput(device: device, name: name, address: address, isCurrent: isCurrent)
    }

    private func selectOutput(
        device: IOBluetoothDevice,
        name: String,
        address: String,
        isCurrent: @escaping @MainActor () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            try Task.checkCancellation()
            guard isCurrent() else { throw HandoffError.cancelled }
            if device.isConnected(), let audioID = Self.findOutputDevice(named: name, address: address) {
                try Task.checkCancellation()
                guard isCurrent() else { throw HandoffError.cancelled }
                if Self.defaultOutputDevice() != audioID {
                    try Self.setDefaultOutput(audioID)
                }
                try await Task.sleep(for: .milliseconds(250))
                try Task.checkCancellation()
                guard isCurrent() else { throw HandoffError.cancelled }
                guard device.isConnected(), Self.defaultOutputDevice() == audioID else {
                    throw HandoffError.routeFailed
                }
                return
            }
            try await Task.sleep(for: .milliseconds(350))
        }
        throw HandoffError.timeout
    }

    private func waitForPendingConnection(
        token: UUID,
        timeout: TimeInterval,
        isCurrent: @escaping @MainActor () -> Bool
    ) async throws -> IOReturn {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try Task.checkCancellation()
            guard isCurrent() else { throw HandoffError.cancelled }
            guard let pending = pendingConnection, pending.token == token else {
                throw HandoffError.cancelled
            }
            if let result = pending.result { return result }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw HandoffError.timeout
    }

    private func waitForPendingSettlementBeforeDisconnect(
        _ expected: PendingConnection,
        timeout: TimeInterval,
        isCurrent: @escaping @MainActor () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try Task.checkCancellation()
            guard isCurrent() else { throw HandoffError.cancelled }
            guard let pending = pendingConnection, pending.token == expected.token else { return }
            if pending.result != nil { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw HandoffError.timeout
    }

    private func abandonPendingConnection(token: UUID) {
        guard let pending = pendingConnection, pending.token == token else { return }
        pending.closeWhenComplete = true
        if pending.device.isConnected() {
            pending.device.closeConnection()
            pendingConnection = nil
        }
    }

    private func connectionCompleted(token: UUID, device: IOBluetoothDevice, status: IOReturn) {
        guard let pending = pendingConnection, pending.token == token else { return }
        pending.result = status
        if pending.closeWhenComplete {
            if device.isConnected() { device.closeConnection() }
            pendingConnection = nil
        }
    }

    private static func findOutputDevice(named selectedName: String, address: String) -> AudioObjectID? {
        let system = AudioObjectID(kAudioObjectSystemObject)
        let devices: [AudioObjectID] = readArray(object: system, selector: kAudioHardwarePropertyDevices)
        let normalizedTarget = normalize(selectedName)
        let candidates = devices.compactMap { id -> (AudioObjectID, String, String)? in
            let transport: UInt32 = readScalar(object: id, selector: kAudioDevicePropertyTransportType) ?? 0
            guard (transport == kAudioDeviceTransportTypeBluetooth ||
                   transport == kAudioDeviceTransportTypeBluetoothLE),
                  hasOutputChannels(id),
                  let name = readCFString(object: id, selector: kAudioObjectPropertyName),
                  let uid = readCFString(object: id, selector: kAudioDevicePropertyDeviceUID) else { return nil }
            return (id, normalize(name), normalizeIdentifier(uid))
        }

        let normalizedAddress = normalizeIdentifier(address)
        let addressMatches = candidates.filter {
            !normalizedAddress.isEmpty && !$0.2.isEmpty && $0.2.contains(normalizedAddress)
        }
        if addressMatches.count == 1 { return addressMatches[0].0 }
        if addressMatches.count > 1 { return nil }

        let exactNameMatches = candidates.filter { $0.1 == normalizedTarget }
        return exactNameMatches.count == 1 ? exactNameMatches[0].0 : nil
    }

    private static func hasOutputChannels(_ device: AudioObjectID) -> Bool {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreamConfiguration,
            mScope: kAudioDevicePropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr, size > 0 else { return false }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, raw) == noErr else { return false }
        let list = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(0) { $0 + Int($1.mNumberChannels) } > 0
    }

    private static func setDefaultOutput(_ device: AudioObjectID) throws {
        let system = AudioObjectID(kAudioObjectSystemObject)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var value = device
        let status = AudioObjectSetPropertyData(system, &address, 0, nil, UInt32(MemoryLayout.size(ofValue: value)), &value)
        guard status == noErr else { throw HandoffError.routeFailed }
    }

    private static func defaultOutputDevice() -> AudioObjectID? {
        readScalar(object: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDefaultOutputDevice)
    }

    private static func normalize(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "’", with: "")
            .replacingOccurrences(of: "'", with: "")
    }

    private static func normalizeIdentifier(_ value: String) -> String {
        String(value.lowercased().unicodeScalars.filter(CharacterSet.alphanumerics.contains))
    }
}

private final class ConnectionCompletionTarget: NSObject, IOBluetoothDeviceAsyncCallbacks {
    var token = UUID()
    private let completion: (UUID, IOBluetoothDevice, IOReturn) -> Void

    init(completion: @escaping (UUID, IOBluetoothDevice, IOReturn) -> Void) {
        self.completion = completion
    }

    func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        guard let device else { return }
        completion(token, device, status)
    }

    func remoteNameRequestComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}
    func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}
}
