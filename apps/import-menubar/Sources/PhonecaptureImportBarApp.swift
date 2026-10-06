import ServiceManagement
import SwiftUI

@main
struct PhonecaptureImportBarApp: App {
    @StateObject private var model = ImporterModel()

    init() {
        registerLaunchAtLoginIfNeeded()
    }

    var body: some Scene {
        MenuBarExtra {
            ContentView(model: model)
                .frame(width: 360)
        } label: {
            Label(model.menuTitle, systemImage: model.menuImage)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
                .frame(width: 420)
        }
    }

    private func registerLaunchAtLoginIfNeeded() {
        do {
            if SMAppService.mainApp.status != .enabled {
                try SMAppService.mainApp.register()
            }
        } catch {
            NSLog("PhonecaptureImportBar failed to register launch at login: %@", String(describing: error))
        }
    }
}

struct SettingsView: View {
    @ObservedObject var model: ImporterModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Remembering Location")
                .font(.headline)

            Text(model.configuredDestinationPath.isEmpty ? "Loading..." : model.configuredDestinationPath)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(3)

            HStack {
                Button("Choose Folder") { model.chooseDestination() }
                Button("Open Folder") { model.openDestination() }
            }

            Text("New installs default to ~/Downloads/phonecapture-imports.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()

            Text(Bundle.main.phonecaptureVersionString)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .task {
            model.refresh()
        }
    }
}

struct ContentView: View {
    @ObservedObject var model: ImporterModel

    private var statusTint: Color {
        switch model.statusTintName {
        case "green":
            return .green
        case "orange":
            return .orange
        case "accent":
            return .accentColor
        default:
            return .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Circle()
                        .fill(statusTint)
                        .frame(width: 10, height: 10)
                    Text(model.statusHeadline)
                        .font(.headline)
                    Spacer()
                }
                Text(model.statusDetail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if model.isImporting {
                VStack(alignment: .leading, spacing: 8) {
                    ProgressView()
                        .progressViewStyle(.linear)
                    Text("Sync continues in the background.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let message = model.lastMessage {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }

            Divider()

            HStack {
                Button("Import New") { model.importNew() }
                    .disabled(!model.canImport)
                Spacer()
                Menu("Remembering Location") {
                    Text(model.configuredDestinationPath.isEmpty ? "Loading..." : model.configuredDestinationPath)
                    Divider()
                    Button("Choose Location…") { model.chooseDestination() }
                    Button("Open Location") { model.openDestination() }
                }
            }

            Divider()

            HStack {
                Text(Bundle.main.phonecaptureVersionString)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Spacer()
                Button("Quit Fone") {
                    NSApp.terminate(nil)
                }
            }
        }
        .padding(16)
        .task {
            model.startPolling()
            model.refresh()
        }
    }
}

private extension Bundle {
    var phonecaptureVersionString: String {
        let version = object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "Fone \(version) (\(build))"
    }
}
