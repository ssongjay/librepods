import CoreBluetooth
import CryptoKit
import Foundation

final class BLEPeripheral: NSObject, CBPeripheralManagerDelegate {
    static let serviceUUID = CBUUID(string: "739D1A20-6C32-4B18-A8E5-BDC4010A2000")
    static let rxUUID = CBUUID(string: "739D1A20-6C32-4B18-A8E5-BDC4010A2001")
    static let txUUID = CBUUID(string: "739D1A20-6C32-4B18-A8E5-BDC4010A2002")

    var onState: ((ConnectionState) -> Void)?
    var onPairing: ((String, String) -> Void)?
    var onPaired: ((Data, String) -> Void)?
    var onReady: ((String) -> Void)?
    var onPayload: ((SecurePayload) -> Void)?
    var readyState: (() -> (HandoffOwner, UInt64))?
    private(set) var sessionToken: UInt64 = 0
    let discoveryName: String

    private let identity: P256.KeyAgreement.PrivateKey
    private var expectedPhoneKey: Data?
    private var manager: CBPeripheralManager!
    private var rx: CBMutableCharacteristic?
    private var tx: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var decoder = FrameDecoder()
    private var crypto: CryptoSession?
    private var localApproved = false
    private var phoneApproved = false
    private var authenticated = false
    private var handshakeTimer: Timer?
    private var outgoingFrames: [Data] = []
    private var outgoingOffset = 0
    private var queuedBytes = 0
    private var queueTimer: Timer?
    private var wantsAdvertising = false
    private var servicePublished = false
    private var serviceAddInFlight = false
    private var pendingService: CBMutableService?

    init(identity: P256.KeyAgreement.PrivateKey, expectedPhoneKey: Data?) {
        self.identity = identity
        self.expectedPhoneKey = expectedPhoneKey
        let publicDER = CryptoSession.spkiPrefix + identity.publicKey.x963Representation
        let digestPrefix = SHA256.hash(data: publicDER).prefix(4)
        self.discoveryName = "LPMy-" + digestPrefix.map { String(format: "%02X", $0) }.joined()
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func updatePinnedKey(_ key: Data?) {
        expectedPhoneKey = key
    }

    func startAdvertising() {
        wantsAdvertising = true
        guard manager.state == .poweredOn else {
            switch manager.state {
            case .poweredOff:
                onState?(.disconnected("Bluetooth를 켜주세요"))
            case .unauthorized:
                onState?(.disconnected("Bluetooth 권한이 필요해요"))
            case .unsupported:
                onState?(.disconnected("이 Mac은 Bluetooth LE 주변기기를 지원하지 않아요"))
            case .resetting:
                onState?(.disconnected("Bluetooth를 다시 준비하고 있어요"))
            case .unknown:
                onState?(.disconnected("Bluetooth 상태를 확인하고 있어요"))
            case .poweredOn:
                break
            @unknown default:
                onState?(.disconnected("Bluetooth를 사용할 수 없어요"))
            }
            return
        }
        installServiceIfNeeded()
        advertiseIfReady()
        if subscribedCentral == nil { onState?(.advertising) }
    }

    func stop() {
        wantsAdvertising = false
        if manager.isAdvertising { manager.stopAdvertising() }
        if manager.state == .poweredOn { manager.removeAllServices() }
        clearPublishedService()
        resetSession(reason: nil)
        onState?(.idle)
    }

    func confirmPairing() {
        guard crypto != nil else { return }
        localApproved = true
        finishApprovalIfPossible()
    }

    func rejectPairing() {
        sendPlain(["type": "rejection"])
        resetSession(reason: "페어링을 취소했어요")
    }

    func send(_ payload: SecurePayload) {
        guard authenticated, let crypto else { return }
        do {
            try enqueue(crypto.encrypt(payload))
        } catch {
            resetSession(reason: "보안 연결이 끊겼어요")
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            peripheral.removeAllServices()
            clearPublishedService()
            installServiceIfNeeded()
        case .resetting:
            invalidateBluetooth(reason: "Bluetooth를 다시 준비하고 있어요")
        case .poweredOff:
            invalidateBluetooth(reason: "Bluetooth가 꺼져 있어요")
        case .unauthorized:
            invalidateBluetooth(reason: "Bluetooth 권한이 필요해요")
        case .unsupported:
            invalidateBluetooth(reason: "이 Mac은 Bluetooth LE 주변기기를 지원하지 않아요")
        case .unknown:
            invalidateBluetooth(reason: "Bluetooth 상태를 확인하고 있어요")
        @unknown default:
            invalidateBluetooth(reason: "Bluetooth를 사용할 수 없어요")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard service.uuid == Self.serviceUUID,
              let pendingService, pendingService === service else { return }
        serviceAddInFlight = false
        self.pendingService = nil
        guard error == nil else {
            clearPublishedService()
            resetSession(reason: "Bluetooth 서비스를 시작하지 못했어요")
            return
        }
        servicePublished = true
        advertiseIfReady()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.txUUID else { return }
        if let existing = subscribedCentral, existing.identifier != central.identifier {
            return
        }
        subscribedCentral = central
        onState?(.advertising)
        handshakeTimer?.invalidate()
        handshakeTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
            self?.resetSession(reason: "페어링 시간이 초과됐어요")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard subscribedCentral?.identifier == central.identifier else { return }
        resetSession(reason: "휴대전화 연결이 끊겼어요")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == Self.rxUUID,
                  request.central.identifier == subscribedCentral?.identifier,
                  let value = request.value,
                  request.offset == 0 else {
                peripheral.respond(to: request, withResult: .unlikelyError)
                continue
            }
            do {
                for frame in try decoder.append(value) {
                    try receive(frame)
                }
                peripheral.respond(to: request, withResult: .success)
            } catch {
                peripheral.respond(to: request, withResult: .unlikelyError)
                resetSession(reason: "잘못된 연결 메시지를 받았어요")
                break
            }
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushOutgoing()
    }

    private func installServiceIfNeeded() {
        guard !servicePublished, !serviceAddInFlight, manager.state == .poweredOn else { return }
        let rx = CBMutableCharacteristic(
            type: Self.rxUUID,
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )
        let tx = CBMutableCharacteristic(
            type: Self.txUUID,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [rx, tx]
        self.rx = rx
        self.tx = tx
        serviceAddInFlight = true
        pendingService = service
        manager.add(service)
    }

    private func advertiseIfReady() {
        guard wantsAdvertising, servicePublished, manager.state == .poweredOn,
              !manager.isAdvertising else { return }
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: discoveryName
        ])
    }

    private func invalidateBluetooth(reason: String) {
        if manager.isAdvertising { manager.stopAdvertising() }
        clearPublishedService()
        resetSession(reason: reason)
    }

    private func clearPublishedService() {
        servicePublished = false
        serviceAddInFlight = false
        pendingService = nil
        rx = nil
        tx = nil
    }

    private func receive(_ frame: Data) throws {
        if crypto == nil {
            let hello = try JSONDecoder().decode(HelloMessage.self, from: frame)
            let session = try CryptoSession(identity: identity, phoneHello: hello)
            if let expectedPhoneKey, expectedPhoneKey != session.phonePublicDER {
                sendPlain(["type": "rejection"])
                throw HandoffError.peerChanged
            }
            crypto = session
            sessionToken &+= 1
            if sessionToken == 0 { sessionToken = 1 }
            localApproved = expectedPhoneKey != nil
            try enqueue(JSONEncoder().encode(session.macHello))
            if localApproved {
                onState?(.negotiating(code: session.sas, phoneName: session.phoneName))
            } else {
                onState?(.pairing(code: session.sas, phoneName: session.phoneName))
                onPairing?(session.sas, session.phoneName)
            }
            return
        }

        if !authenticated,
           let object = try? JSONSerialization.jsonObject(with: frame) as? [String: Any],
           object["type"] as? String == "rejection" {
            resetSession(reason: "휴대전화에서 페어링을 취소했어요")
            return
        }
        guard let crypto else { throw HandoffError.authenticationFailed }
        let payload = try crypto.decrypt(frame)
        if !authenticated {
            guard case .ready = payload else { throw HandoffError.authenticationFailed }
            phoneApproved = true
            finishApprovalIfPossible()
            return
        }
        onPayload?(payload)
    }

    private func finishApprovalIfPossible() {
        guard localApproved, phoneApproved, !authenticated, let crypto else { return }
        if expectedPhoneKey == nil {
            expectedPhoneKey = crypto.phonePublicDER
            onPaired?(crypto.phonePublicDER, crypto.phoneName)
        }
        do {
            let (owner, revision) = readyState?() ?? (.none, 0)
            try enqueue(crypto.encrypt(.ready(owner: owner, revision: revision)))
            authenticated = true
            handshakeTimer?.invalidate()
            onState?(.ready(phoneName: crypto.phoneName, code: crypto.sas))
            onReady?(crypto.phoneName)
        } catch {
            resetSession(reason: "보안 연결을 시작하지 못했어요")
        }
    }

    private func sendPlain(_ object: [String: String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        try? enqueue(data)
    }

    private func enqueue(_ payload: Data) throws {
        let frame = try FrameDecoder.encode(payload)
        guard outgoingFrames.count < 16, queuedBytes + frame.count <= 65_536 else {
            resetSession(reason: "연결 대기열이 가득 찼어요")
            throw HandoffError.invalidMessage
        }
        outgoingFrames.append(frame)
        queuedBytes += frame.count
        if queueTimer == nil {
            queueTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
                guard let self, !self.outgoingFrames.isEmpty else { return }
                self.resetSession(reason: "휴대전화 응답이 지연돼 연결을 닫았어요")
            }
        }
        flushOutgoing()
    }

    private func flushOutgoing() {
        guard let tx, let central = subscribedCentral else { return }
        while !outgoingFrames.isEmpty {
            let frame = outgoingFrames[0]
            let maximum = max(20, central.maximumUpdateValueLength)
            let end = min(frame.count, outgoingOffset + maximum)
            let fragment = frame.subdata(in: outgoingOffset..<end)
            guard manager.updateValue(fragment, for: tx, onSubscribedCentrals: [central]) else { return }
            outgoingOffset = end
            if outgoingOffset == frame.count {
                queuedBytes -= frame.count
                outgoingFrames.removeFirst()
                outgoingOffset = 0
            }
        }
        queueTimer?.invalidate()
        queueTimer = nil
    }

    private func resetSession(reason: String?) {
        handshakeTimer?.invalidate()
        handshakeTimer = nil
        queueTimer?.invalidate()
        queueTimer = nil
        sessionToken &+= 1
        if sessionToken == 0 { sessionToken = 1 }
        subscribedCentral = nil
        decoder.reset()
        crypto = nil
        localApproved = false
        phoneApproved = false
        authenticated = false
        outgoingFrames.removeAll(keepingCapacity: false)
        outgoingOffset = 0
        queuedBytes = 0
        if let reason { onState?(.disconnected(reason)) }
    }
}
