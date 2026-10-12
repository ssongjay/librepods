import CoreAudio
import Dispatch
import Foundation

@MainActor
final class PlaybackMonitor {
    var onNewPlayback: ((UInt64) -> Void)?

    private var timer: Timer?
    private var activeProcesses = Set<pid_t>()
    private var candidates = Set<pid_t>()
    private var candidateObservedAt: [pid_t: UInt64] = [:]
    private var absentPollCounts: [pid_t: Int] = [:]
    private var hasBaseline = false
    private var needsBaseline = true

    func start() {
        guard timer == nil else { return }
        poll()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        hasBaseline = false
        needsBaseline = true
        activeProcesses.removeAll()
        candidates.removeAll()
        candidateObservedAt.removeAll()
        absentPollCounts.removeAll()
    }

    private func poll() {
        guard #available(macOS 14.2, *) else { return }
        let current = Set(Self.runningOutputProcesses())
        if !hasBaseline || needsBaseline {
            activeProcesses = current
            candidates.removeAll()
            candidateObservedAt.removeAll()
            absentPollCounts.removeAll()
            hasBaseline = true
            needsBaseline = false
            return
        }

        for pid in activeProcesses.subtracting(current) {
            let count = (absentPollCounts[pid] ?? 0) + 1
            if count >= 2 {
                activeProcesses.remove(pid)
                absentPollCounts.removeValue(forKey: pid)
                candidates.remove(pid)
                candidateObservedAt.removeValue(forKey: pid)
            } else {
                absentPollCounts[pid] = count
            }
        }
        for pid in current { absentPollCounts.removeValue(forKey: pid) }

        let confirmed = current.intersection(candidates)
        let confirmedObservations = confirmed.compactMap { candidateObservedAt[$0] }.sorted()
        candidates.subtract(confirmed)
        for pid in confirmed { candidateObservedAt.removeValue(forKey: pid) }
        let vanishedCandidates = candidates.subtracting(current)
        candidates.formIntersection(current)
        for pid in vanishedCandidates { candidateObservedAt.removeValue(forKey: pid) }
        let newlySeen = current.subtracting(activeProcesses)
        let observedAt = DispatchTime.now().uptimeNanoseconds
        candidates.formUnion(newlySeen)
        for pid in newlySeen { candidateObservedAt[pid] = observedAt }
        activeProcesses.formUnion(current)
        for observation in confirmedObservations { onNewPlayback?(observation) }
    }

    @available(macOS 14.2, *)
    private static func runningOutputProcesses() -> [pid_t] {
        let system = AudioObjectID(kAudioObjectSystemObject)
        let objects: [AudioObjectID] = readArray(
            object: system,
            selector: kAudioHardwarePropertyProcessObjectList
        )
        return objects.compactMap { object in
            let running: UInt32 = readScalar(object: object, selector: kAudioProcessPropertyIsRunningOutput) ?? 0
            guard running != 0,
                  let pid: pid_t = readScalar(object: object, selector: kAudioProcessPropertyPID),
                  pid != ProcessInfo.processInfo.processIdentifier else { return nil }
            let bundleID = readCFString(object: object, selector: kAudioProcessPropertyBundleID) ?? ""
            guard !isExcluded(bundleID: bundleID) else { return nil }
            return pid
        }
    }

    private static func isExcluded(bundleID: String) -> Bool {
        let exact: Set<String> = [
            "com.apple.audio.AudioComponentRegistrar",
            "com.apple.audio.coreaudiod",
            "com.apple.controlcenter",
            "com.apple.systemuiserver"
        ]
        return exact.contains(bundleID) || bundleID.hasPrefix("com.apple.audio.AudioNotification")
    }
}

func readScalar<T>(object: AudioObjectID, selector: AudioObjectPropertySelector) -> T? {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )
    let value = UnsafeMutablePointer<T>.allocate(capacity: 1)
    defer { value.deallocate() }
    var size = UInt32(MemoryLayout<T>.size)
    let status = AudioObjectGetPropertyData(object, &address, 0, nil, &size, value)
    return status == noErr ? value.pointee : nil
}

func readArray<T>(object: AudioObjectID, selector: AudioObjectPropertySelector) -> [T] {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(object, &address, 0, nil, &size) == noErr,
          size > 0 else { return [] }
    let count = Int(size) / MemoryLayout<T>.size
    let values = UnsafeMutablePointer<T>.allocate(capacity: count)
    defer { values.deallocate() }
    let status = AudioObjectGetPropertyData(object, &address, 0, nil, &size, values)
    guard status == noErr else { return [] }
    return Array(UnsafeBufferPointer(start: values, count: count))
}

func readCFString(object: AudioObjectID, selector: AudioObjectPropertySelector) -> String? {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )
    var value: Unmanaged<CFString>?
    var size = UInt32(MemoryLayout<CFString>.size)
    let status = AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value)
    return status == noErr ? value?.takeRetainedValue() as String? : nil
}
