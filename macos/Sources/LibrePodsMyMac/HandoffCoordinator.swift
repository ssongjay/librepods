import AppKit
import Combine
import Dispatch
import Foundation

@MainActor
final class HandoffCoordinator: ObservableObject {
    static let shared = HandoffCoordinator()

    @Published private(set) var enabled: Bool
    @Published private(set) var connection: ConnectionState = .idle
    @Published private(set) var owner: HandoffOwner
    @Published private(set) var revision: UInt64
    @Published private(set) var airPods: [AudioDeviceChoice] = []
    @Published var selectedAirPodsAddress: String
    @Published private(set) var statusMessage = "자동 전환이 꺼져 있어요"

    private let preferences = Preferences()
    private let router = BluetoothAudioRouter()
    private let monitor = PlaybackMonitor()
    private var peripheral: BLEPeripheral?
    private var transition: Task<Void, Never>?
    private var yieldedResults: [UInt64: (Bool, String)] = [:]
    private var phoneResults: [UInt64: (Bool, String)] = [:]
    private var acceptedResponses = Set<String>()
    private var activeTransitionRevision: UInt64?
    private var lastAcceptedObservation: UInt64 = 0

    private init() {
        enabled = preferences.enabled
        owner = preferences.owner
        revision = preferences.revision
        selectedAirPodsAddress = preferences.selectedAirPodsAddress ?? ""

        do {
            let transport = BLEPeripheral(
                identity: try KeychainIdentity.loadOrCreate(),
                expectedPhoneKey: preferences.pinnedPhoneKey
            )
            peripheral = transport
            configure(transport)
        } catch {
            connection = .error("Mac 보안 키를 준비하지 못했어요")
            enabled = false
            preferences.enabled = false
        }

        monitor.onNewPlayback = { [weak self] observedAt in
            self?.macPlaybackStarted(observedAt: observedAt)
        }
        refreshAirPods()
        if enabled {
            monitor.start()
            peripheral?.startAdvertising()
            statusMessage = "새 재생을 기다리는 중"
        }
    }

    var isPaired: Bool { preferences.pinnedPhoneKey != nil }
    var canRetry: Bool { enabled && owner != .none }
    var discoveryName: String { peripheral?.discoveryName ?? "사용할 수 없음" }

    func setEnabled(_ newValue: Bool) {
        guard newValue != enabled else { return }
        if newValue, peripheral == nil {
            enabled = false
            preferences.enabled = false
            connection = .error("Mac 보안 키를 준비하지 못했어요")
            statusMessage = "Bluetooth 연결을 시작할 수 없어요"
            return
        }
        enabled = newValue
        preferences.enabled = newValue
        transition?.cancel()
        if newValue {
            refreshAirPods()
            monitor.start()
            peripheral?.startAdvertising()
            statusMessage = "새 재생을 기다리는 중"
        } else {
            router.cancelPendingConnection()
            monitor.stop()
            peripheral?.stop()
            statusMessage = "자동 전환이 꺼져 있어요"
        }
    }

    func selectAirPods(_ address: String) {
        selectedAirPodsAddress = address
        preferences.selectedAirPodsAddress = address.isEmpty ? nil : address
    }

    func refreshAirPods() {
        airPods = router.pairedAirPods()
        if !selectedAirPodsAddress.isEmpty, !airPods.contains(where: { $0.id == selectedAirPodsAddress }) {
            selectedAirPodsAddress = ""
            preferences.selectedAirPodsAddress = nil
        }
    }

    func beginPairing() {
        guard let peripheral else {
            connection = .error("Mac 보안 키를 준비하지 못했어요")
            statusMessage = "Bluetooth 검색을 시작할 수 없어요"
            return
        }
        peripheral.startAdvertising()
        statusMessage = "휴대전화 LibrePods My에서 Mac을 찾아주세요"
    }

    func confirmPairing() {
        peripheral?.confirmPairing()
    }

    func rejectPairing() {
        peripheral?.rejectPairing()
    }

    func forgetPhone() {
        transition?.cancel()
        transition = nil
        activeTransitionRevision = nil
        router.cancelPendingConnection()
        monitor.stop()
        peripheral?.stop()
        enabled = false
        preferences.enabled = false
        preferences.forgetPhone()
        peripheral?.updatePinnedKey(nil)
        connection = .idle
        statusMessage = "휴대전화 등록을 해제했어요"
    }

    func retryLastRequestedOwner() {
        guard canRetry else { return }
        beginClaim(owner)
    }

    private func configure(_ transport: BLEPeripheral) {
        transport.onState = { [weak self] state in
            self?.connection = state
        }
        transport.onPaired = { [weak self] key, name in
            guard let self else { return }
            self.preferences.pinnedPhoneKey = key
            self.preferences.pinnedPhoneName = name
            transport.updatePinnedKey(key)
            self.objectWillChange.send()
        }
        transport.onReady = { [weak self] _ in
            guard let self else { return }
            guard self.activeTransitionRevision == nil else { return }
            self.statusMessage = self.enabled ? "새 재생을 기다리는 중" : "연결됨 · 자동 전환 꺼짐"
        }
        transport.readyState = { [weak self] in
            guard let self else { return (.none, 0) }
            return (self.owner, self.revision)
        }
        transport.onPayload = { [weak self] payload in self?.receive(payload) }
    }

    private func receive(_ payload: SecurePayload) {
        switch payload {
        case .playbackStarted:
            guard enabled else { return }
            beginClaim(.phone, observedAt: DispatchTime.now().uptimeNanoseconds)
        case .yielded(let revision, let success, let message):
            let key = "yielded:\(revision)"
            guard revision == self.revision, acceptedResponses.insert(key).inserted else { return }
            yieldedResults[revision] = (success, message)
        case .result(let revision, let success, let message):
            let key = "result:\(revision)"
            guard revision == self.revision, acceptedResponses.insert(key).inserted else { return }
            phoneResults[revision] = (success, message)
        case .ready, .claim, .status:
            break
        }
    }

    private func macPlaybackStarted(observedAt: UInt64) {
        guard enabled else { return }
        beginClaim(.mac, observedAt: observedAt)
    }

    private func beginClaim(
        _ requestedOwner: HandoffOwner,
        observedAt: UInt64 = DispatchTime.now().uptimeNanoseconds
    ) {
        guard observedAt > lastAcceptedObservation else { return }
        lastAcceptedObservation = observedAt
        transition?.cancel()
        guard revision < UInt64(Int64.max) else {
            activeTransitionRevision = nil
            transition = nil
            statusMessage = "전환 번호 한도에 도달해 새 요청을 시작할 수 없어요"
            return
        }
        revision += 1
        preferences.revision = revision
        setOwner(requestedOwner)
        acceptedResponses.removeAll(keepingCapacity: true)
        yieldedResults.removeAll(keepingCapacity: true)
        phoneResults.removeAll(keepingCapacity: true)
        let current = revision
        let session = peripheral?.sessionToken
        activeTransitionRevision = current
        statusMessage = requestedOwner == .mac ? "Mac으로 전환 중" : "휴대전화로 전환 중"
        transition = Task { [weak self] in
            guard let self else { return }
            defer { self.finishTransition(revision: current) }
            if requestedOwner == .mac {
                await self.claimMac(revision: current, session: session)
            } else {
                await self.claimPhone(revision: current, session: session)
            }
        }
    }

    private func claimMac(revision: UInt64, session: UInt64?) async {
        guard !selectedAirPodsAddress.isEmpty else {
            finishFailure(revision: revision, session: session, message: "먼저 AirPods를 선택해주세요")
            return
        }
        do {
            if isTransportReady {
                guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
                peripheral?.send(.claim(owner: .mac, revision: revision))
                let yielded = try await waitForResult(revision: revision, session: session, source: .yielded, timeout: 3)
                guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
                guard yielded.0 else {
                    finishFailure(revision: revision, session: session, message: yielded.1.isEmpty ? "휴대전화가 연결을 놓지 못했어요" : yielded.1)
                    return
                }
            }
            try Task.checkCancellation()
            guard isCurrent(revision: revision, session: session, requireReady: false) else { throw HandoffError.cancelled }
            try await router.connectAndSelect(address: selectedAirPodsAddress) { [weak self] in
                guard let self else { return false }
                return self.isCurrent(revision: revision, session: session, requireReady: false)
            }
            try Task.checkCancellation()
            guard isCurrent(revision: revision, session: session, requireReady: false) else {
                throw HandoffError.cancelled
            }
            statusMessage = "Mac 오디오 연결됨"
            if isTransportReady {
                guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
                peripheral?.send(.status(owner: .mac, revision: revision, success: true, message: "Mac 오디오 연결됨"))
            }
        } catch is CancellationError {
            return
        } catch {
            finishFailure(revision: revision, session: session, message: (error as? LocalizedError)?.errorDescription ?? "Mac 전환에 실패했어요")
        }
    }

    private func claimPhone(revision: UInt64, session: UInt64?) async {
        do {
            guard isCurrent(revision: revision, session: session, requireReady: false), isTransportReady else {
                finishFailure(revision: revision, session: session, message: "먼저 휴대전화를 연결해주세요")
                return
            }
            try await router.disconnectAndWait(address: selectedAirPodsAddress.isEmpty ? nil : selectedAirPodsAddress) { [weak self] in
                guard let self else { return false }
                return self.isCurrent(revision: revision, session: session, requireReady: true)
            }
            guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
            peripheral?.send(.claim(owner: .phone, revision: revision))
            let result = try await waitForResult(revision: revision, session: session, source: .phone, timeout: 7)
            guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
            if result.0 {
                statusMessage = "휴대전화 오디오 연결됨"
            } else {
                finishFailure(revision: revision, session: session, message: result.1.isEmpty ? "휴대전화 전환에 실패했어요" : result.1)
            }
        } catch is CancellationError {
            return
        } catch {
            finishFailure(revision: revision, session: session, message: "휴대전화 응답이 없어요")
        }
    }

    private enum ResultSource { case yielded, phone }

    private func waitForResult(revision: UInt64, session: UInt64?, source: ResultSource, timeout: TimeInterval) async throws -> (Bool, String) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try Task.checkCancellation()
            guard isCurrent(revision: revision, session: session, requireReady: true) else { throw HandoffError.cancelled }
            switch source {
            case .yielded:
                if let result = yieldedResults.removeValue(forKey: revision) { return result }
            case .phone:
                if let result = phoneResults.removeValue(forKey: revision) { return result }
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw HandoffError.timeout
    }

    private var isTransportReady: Bool {
        if case .ready = connection { return true }
        return false
    }

    private func isCurrent(revision: UInt64, session: UInt64?, requireReady: Bool) -> Bool {
        guard !Task.isCancelled, revision == self.revision else { return false }
        if let session, peripheral?.sessionToken != session { return false }
        return !requireReady || isTransportReady
    }

    private func setOwner(_ newOwner: HandoffOwner) {
        owner = newOwner
        preferences.owner = newOwner
    }

    private func finishTransition(revision: UInt64) {
        guard activeTransitionRevision == revision else { return }
        activeTransitionRevision = nil
        transition = nil
    }

    private func finishFailure(revision: UInt64, session: UInt64?, message: String) {
        guard isCurrent(revision: revision, session: session, requireReady: false) else { return }
        statusMessage = String(message.prefix(100))
        if isTransportReady {
            guard isCurrent(revision: revision, session: session, requireReady: true) else { return }
            peripheral?.send(.status(owner: owner, revision: revision, success: false, message: statusMessage))
        }
    }
}
