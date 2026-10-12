import Foundation

struct FrameDecoder {
    static let maximumPayload = 8_192
    private var buffer = Data()

    mutating func append(_ fragment: Data) throws -> [Data] {
        guard buffer.count + fragment.count <= Self.maximumPayload + 2 else {
            buffer.removeAll(keepingCapacity: false)
            throw HandoffError.invalidMessage
        }
        buffer.append(fragment)
        var frames: [Data] = []
        while buffer.count >= 2 {
            let length = Int(buffer[buffer.startIndex]) << 8 | Int(buffer[buffer.startIndex + 1])
            guard length <= Self.maximumPayload else {
                buffer.removeAll(keepingCapacity: false)
                throw HandoffError.invalidMessage
            }
            guard buffer.count >= length + 2 else { break }
            frames.append(buffer.subdata(in: 2..<(length + 2)))
            buffer.removeSubrange(0..<(length + 2))
        }
        return frames
    }

    mutating func reset() {
        buffer.removeAll(keepingCapacity: false)
    }

    static func encode(_ payload: Data) throws -> Data {
        guard payload.count <= maximumPayload else { throw HandoffError.invalidMessage }
        var length = UInt16(payload.count).bigEndian
        var result = Data(bytes: &length, count: MemoryLayout<UInt16>.size)
        result.append(payload)
        return result
    }
}
