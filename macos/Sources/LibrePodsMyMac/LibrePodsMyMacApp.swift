import AppKit
import SwiftUI

@main
struct LibrePodsMyMacApp: App {
    @StateObject private var coordinator = HandoffCoordinator.shared

    var body: some Scene {
        MenuBarExtra("LibrePods My", systemImage: coordinator.enabled ? "airpodspro" : "airpodspro.chargingcase.wireless") {
            MenuBarContent(coordinator: coordinator)
        }

        Window("LibrePods My 설정", id: "setup") {
            SetupView(coordinator: coordinator)
                .frame(width: 460, height: 520)
        }
        .windowResizability(.contentSize)
    }
}

private struct MenuBarContent: View {
    @ObservedObject var coordinator: HandoffCoordinator
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(coordinator.statusMessage)
        Text("최근 재생 기기: \(ownerLabel)")
        Divider()
        Toggle("새 재생 기기로 자동 전환", isOn: Binding(
            get: { coordinator.enabled },
            set: coordinator.setEnabled
        ))
        Button("전환 다시 시도") { coordinator.retryLastRequestedOwner() }
            .disabled(!coordinator.canRetry)
        Button("설정 열기") {
            NSApplication.shared.activate(ignoringOtherApps: true)
            openWindow(id: "setup")
        }
        Divider()
        Button("LibrePods My 종료") { NSApplication.shared.terminate(nil) }
    }

    private var ownerLabel: String {
        switch coordinator.owner {
        case .none: return "정해지지 않음"
        case .mac: return "Mac"
        case .phone: return "휴대전화"
        }
    }
}

private struct SetupView: View {
    @ObservedObject var coordinator: HandoffCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text("LibrePods My")
                    .font(.largeTitle.bold())
                Text("새 재생을 시작한 기기가 AirPods를 이어받고, 다음 재생이 시작될 때까지 유지해요.")
                    .foregroundStyle(.secondary)
            }

            GroupBox("AirPods") {
                VStack(alignment: .leading, spacing: 10) {
                    Picker("사용할 기기", selection: Binding(
                        get: { coordinator.selectedAirPodsAddress },
                        set: coordinator.selectAirPods
                    )) {
                        Text("선택 안 함").tag("")
                        ForEach(coordinator.airPods) { device in
                            Text(device.name).tag(device.id)
                        }
                    }
                    Button("목록 새로고침") { coordinator.refreshAirPods() }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
            }

            GroupBox("휴대전화") {
                VStack(alignment: .leading, spacing: 10) {
                    Text(coordinator.connection.summary)
                    HStack(spacing: 4) {
                        Text("검색 목록 이름:")
                            .foregroundStyle(.secondary)
                        Text(coordinator.discoveryName)
                            .font(.system(.body, design: .monospaced).bold())
                            .textSelection(.enabled)
                    }
                    pairingControls
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
            }

            Toggle("새 재생 기기로 자동 전환", isOn: Binding(
                get: { coordinator.enabled },
                set: coordinator.setEnabled
            ))
            Button("전환 다시 시도") { coordinator.retryLastRequestedOwner() }
                .disabled(!coordinator.canRetry)
            Text(coordinator.statusMessage)
                .font(.callout)
                .foregroundStyle(.secondary)

            Spacer()
            Text("버전 0.1.0 · 오디오 내용은 읽거나 전송하지 않아요.")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(24)
    }

    @ViewBuilder
    private var pairingControls: some View {
        if case let .pairing(code, phoneName) = coordinator.connection {
            Text(phoneName)
                .font(.headline)
            Text(code)
                .font(.system(size: 34, weight: .bold, design: .monospaced))
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .textSelection(.enabled)
            Text("휴대전화에도 같은 코드가 보일 때만 양쪽에서 확인하세요.")
                .font(.callout)
            HStack {
                Button("코드가 같아요") { coordinator.confirmPairing() }
                    .buttonStyle(.borderedProminent)
                Button("취소") { coordinator.rejectPairing() }
            }
        } else if case let .negotiating(code, phoneName) = coordinator.connection {
            sessionCode(code, phoneName: phoneName)
        } else if case let .ready(phoneName, code) = coordinator.connection {
            sessionCode(code, phoneName: phoneName)
            Button("휴대전화 등록 해제", role: .destructive) { coordinator.forgetPhone() }
            Text("완전히 다시 연결하려면 휴대전화에서도 Mac 등록을 해제하세요.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if coordinator.isPaired {
            HStack {
                Button("연결 대기 시작") { coordinator.beginPairing() }
                Button("휴대전화 등록 해제", role: .destructive) { coordinator.forgetPhone() }
            }
        } else {
            Button("휴대전화 페어링 시작") { coordinator.beginPairing() }
                .buttonStyle(.borderedProminent)
        }
    }

    private func sessionCode(_ code: String, phoneName: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(phoneName).font(.headline)
            Text("보안 코드 \(code)")
                .font(.system(.body, design: .monospaced).bold())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Text("연결할 때 양쪽 코드가 같은지 확인할 수 있어요.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
