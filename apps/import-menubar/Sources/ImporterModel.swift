import AppKit
import Darwin
import Foundation

private let appPackage = "com.hapticasensorics.phonecapturekiosk"
private let remoteRoot = "/sdcard/Android/media/\(appPackage)"
private let remoteIndex = "\(remoteRoot)/capture-index.jsonl"
private let remoteStatus = "\(remoteRoot)/diagnostics/uvc-status.json"
private let remoteDirs = [
    "videos": "\(remoteRoot)/uvc-videos",
    "captures": "\(remoteRoot)/uvc-captures",
]

private struct AppConfig: Codable {
    var destRoot: String
    var httpBaseURL: String?
}

struct ImportStatus {
    struct Entry {
        let basename: String
        let category: String
        let isNew: Bool
    }

    let captures: [Entry]
    let destRoot: String
    let device: String
    let lastImportDir: String?
    let newFiles: Int
    let remoteRoot: String
    let totalFiles: Int
    let videos: [Entry]
}

struct RecentEntry {
    let kind: String
    let basename: String
    let isNew: Bool

    var id: String { "\(kind):\(basename)" }
}

private struct RemoteFile {
    let category: String
    let basename: String

    var key: String { "\(category):\(basename)" }
    var remotePath: String { "\(remoteDirs[category]!)/\(basename)" }
}

struct ImportResult {
    let importedCount: Int
    let destination: String
}

private struct RemoteSnapshot {
    let files: [RemoteFile]
    let captureDates: [String: Date]
    let remoteRoot: String
}

private enum TransferEndpoint {
    case http(baseURL: String)
    case adb(serial: String)

    var deviceDescription: String {
        switch self {
        case .http(let baseURL):
            return baseURL
        case .adb(let serial):
            return serial
        }
    }
}

private enum ImporterError: LocalizedError {
    case adbNotFound
    case noJellyDetected
    case missingRemoteFiles([String])

    var errorDescription: String? {
        switch self {
        case .adbNotFound:
            return "Unable to locate adb. Install Android platform-tools or add adb to /opt/homebrew/bin, /usr/local/bin, or ~/Library/Android/sdk/platform-tools."
        case .noJellyDetected:
            if ImporterBackend.adbEnabled {
                return "No Phonecapture device found on the local network or via USB."
            }
            return "No Phonecapture device found on the local network."
        case .missingRemoteFiles(let basenames):
            return "Files not found on device: \(basenames.joined(separator: ", "))"
        }
    }
}

final class ImporterBackend {
    static var adbEnabled: Bool {
#if DEBUG
        true
#else
        false
#endif
    }

    private let fileManager = FileManager.default
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private let urlSession: URLSession
    private let discoverySession: URLSession

    init() {
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 600
        configuration.waitsForConnectivity = false
        urlSession = URLSession(configuration: configuration)

        let discoveryConfiguration = URLSessionConfiguration.ephemeral
        discoveryConfiguration.timeoutIntervalForRequest = 1.0
        discoveryConfiguration.timeoutIntervalForResource = 2.0
        discoveryConfiguration.waitsForConnectivity = false
        discoverySession = URLSession(configuration: discoveryConfiguration)
    }

    func status() throws -> ImportStatus {
        let destRoot = try destinationRoot()
        let imported = try importedKeys(destRoot: destRoot)
        let endpoint = try resolveEndpoint()
        let snapshot = try fetchSnapshot(for: endpoint)
        let newFiles = snapshot.files.filter { !imported.contains($0.key) }

        func entries(for category: String) -> [ImportStatus.Entry] {
            snapshot.files
                .filter { $0.category == category }
                .map { .init(basename: $0.basename, category: $0.category, isNew: !imported.contains($0.key)) }
        }

        return ImportStatus(
            captures: entries(for: "captures"),
            destRoot: destRoot.path,
            device: endpoint.deviceDescription,
            lastImportDir: try newestImportDirectory(destRoot: destRoot)?.path,
            newFiles: newFiles.count,
            remoteRoot: snapshot.remoteRoot,
            totalFiles: snapshot.files.count,
            videos: entries(for: "videos")
        )
    }

    func importNew() throws -> ImportResult {
        NSLog("PhonecaptureImportBar importNew: begin")
        let endpoint = try resolveEndpoint()
        let imported = try importedKeys(destRoot: destinationRoot())
        let snapshot = try fetchSnapshot(for: endpoint)
        let files = snapshot.files.filter { !imported.contains($0.key) }
        NSLog("PhonecaptureImportBar importNew: endpoint=%@ new_files=%ld", endpoint.deviceDescription, files.count)
        return try importFiles(endpoint: endpoint, files: files, captureDates: snapshot.captureDates)
    }

    func openLast() throws -> String {
        let destRoot = try destinationRoot()
        guard let lastImportDir = try newestImportDirectory(destRoot: destRoot)?.path else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [NSLocalizedDescriptionKey: "No previous import recorded."])
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: lastImportDir))
        return lastImportDir
    }

    func openDestination() throws -> String {
        let dest = try destinationRoot()
        try fileManager.createDirectory(at: dest, withIntermediateDirectories: true)
        NSWorkspace.shared.open(dest)
        return dest.path
    }

    func setDestination(path: String) throws -> String {
        let url = URL(fileURLWithPath: NSString(string: path).expandingTildeInPath).standardizedFileURL
        try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        var config = try loadConfig()
        config.destRoot = url.path
        try saveConfig(config)
        return url.path
    }

    func currentDestinationPath() throws -> String {
        try destinationRoot().path
    }

    func restartADB() throws {
        guard Self.adbEnabled else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "ADB controls are only available in debug builds."
            ])
        }
        _ = try adb(["kill-server"], serial: nil, check: false)
        _ = try adb(["start-server"], serial: nil, check: true)
    }

    func adbFallbackEnabled() -> Bool {
        Self.adbEnabled
    }

    private func importFiles(endpoint: TransferEndpoint, files: [RemoteFile], captureDates: [String: Date]) throws -> ImportResult {
        guard !files.isEmpty else {
            NSLog("PhonecaptureImportBar importFiles: no files to import")
            return ImportResult(importedCount: 0, destination: try destinationRoot().path)
        }

        let destRoot = try destinationRoot()
        try fileManager.createDirectory(at: destRoot, withIntermediateDirectories: true)
        let stamp = Self.timestamp()
        let dest = destRoot.appendingPathComponent(stamp, isDirectory: true)
        try fileManager.createDirectory(at: dest.appendingPathComponent("uvc-videos"), withIntermediateDirectories: true)
        try fileManager.createDirectory(at: dest.appendingPathComponent("uvc-captures"), withIntermediateDirectories: true)
        NSLog("PhonecaptureImportBar importFiles: destination=%@ capture_dates=%ld", dest.path, captureDates.count)
        switch endpoint {
        case .adb(let serial):
            try streamFilesAsTar(serial: serial, files: files, destination: dest)
        case .http(let baseURL):
            try downloadFilesOverHTTP(baseURL: baseURL, files: files, destination: dest)
        }
        for file in files {
            let localDir = file.category == "videos"
                ? dest.appendingPathComponent("uvc-videos", isDirectory: true)
                : dest.appendingPathComponent("uvc-captures", isDirectory: true)
            let localFile = localDir.appendingPathComponent(file.basename, isDirectory: false)
            guard fileManager.fileExists(atPath: localFile.path) else {
                throw ImporterError.missingRemoteFiles([file.basename])
            }
            try preserveImportedTimestamp(localFile: localFile, remoteFile: file, captureDates: captureDates)
        }
        NSLog("PhonecaptureImportBar importFiles: completed imported=%ld destination=%@", files.count, dest.path)
        return ImportResult(importedCount: files.count, destination: dest.path)
    }

    private func resolveEndpoint() throws -> TransferEndpoint {
        var config = try loadConfig()
        if let configuredBaseURL = normalizedBaseURL(config.httpBaseURL), isHTTPHealthy(baseURL: configuredBaseURL) {
            return .http(baseURL: configuredBaseURL)
        }

        if let discoveredBaseURL = discoverHTTPBaseURLOnLAN() {
            if config.httpBaseURL != discoveredBaseURL {
                config.httpBaseURL = discoveredBaseURL
                try saveConfig(config)
            }
            return .http(baseURL: discoveredBaseURL)
        }

#if DEBUG
        if Self.adbEnabled, let serial = try? detectJellySerial() {
            if let discoveredBaseURL = try discoverHTTPBaseURL(serial: serial) {
                if config.httpBaseURL != discoveredBaseURL {
                    config.httpBaseURL = discoveredBaseURL
                    try saveConfig(config)
                }
                return .http(baseURL: discoveredBaseURL)
            }
            return .adb(serial: serial)
        }
#endif

        throw ImporterError.noJellyDetected
    }

    private func discoverHTTPBaseURLOnLAN() -> String? {
        let candidates = discoveryCandidates()
        guard !candidates.isEmpty else { return nil }

        let group = DispatchGroup()
        let semaphore = DispatchSemaphore(value: 12)
        let lock = NSLock()
        var found: String?

        for candidate in candidates {
            lock.lock()
            let alreadyFound = found != nil
            lock.unlock()
            if alreadyFound {
                break
            }

            semaphore.wait()
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async { [self] in
                defer {
                    semaphore.signal()
                    group.leave()
                }

                lock.lock()
                let shouldSkip = found != nil
                lock.unlock()
                if shouldSkip {
                    return
                }

                if isHTTPHealthy(baseURL: candidate, fast: true) {
                    lock.lock()
                    if found == nil {
                        found = candidate
                    }
                    lock.unlock()
                }
            }
        }

        group.wait()
        return found
    }

    private func discoveryCandidates() -> [String] {
        var candidates: [String] = []
        var seen = Set<String>()

        for address in localPrivateIPv4Addresses() {
            let parts = address.split(separator: ".").compactMap { Int($0) }
            guard parts.count == 4 else { continue }

            let prefix = "\(parts[0]).\(parts[1]).\(parts[2])"
            let localHost = parts[3]
            var hosts: [Int] = []

            for preferred in [localHost + 1, localHost - 1, localHost + 2, localHost - 2] where (1...254).contains(preferred) {
                if !hosts.contains(preferred) {
                    hosts.append(preferred)
                }
            }

            for host in 1...254 where host != localHost {
                if !hosts.contains(host) {
                    hosts.append(host)
                }
            }

            for host in hosts {
                let candidate = "http://\(prefix).\(host):28781"
                if seen.insert(candidate).inserted {
                    candidates.append(candidate)
                }
            }
        }

        return candidates
    }

    private func localPrivateIPv4Addresses() -> [String] {
        var result: [String] = []
        var ifaddrPointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPointer) == 0, let firstAddress = ifaddrPointer else {
            return []
        }
        defer { freeifaddrs(ifaddrPointer) }

        var pointer: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let current = pointer {
            defer { pointer = current.pointee.ifa_next }

            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let address = current.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }

            var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let nameInfoResult = getnameinfo(
                address,
                socklen_t(address.pointee.sa_len),
                &hostBuffer,
                socklen_t(hostBuffer.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard nameInfoResult == 0 else { continue }

            let ipAddress = String(cString: hostBuffer)
            if isPrivateIPv4(ipAddress) {
                result.append(ipAddress)
            }
        }

        return Array(Set(result)).sorted()
    }

    private func isPrivateIPv4(_ address: String) -> Bool {
        let parts = address.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }

        if parts[0] == 10 { return true }
        if parts[0] == 172, (16...31).contains(parts[1]) { return true }
        if parts[0] == 192, parts[1] == 168 { return true }
        return false
    }

    private func fetchSnapshot(for endpoint: TransferEndpoint) throws -> RemoteSnapshot {
        switch endpoint {
        case .adb(let serial):
            return try fetchADBSnapshot(serial: serial)
        case .http(let baseURL):
            return try fetchHTTPSnapshot(baseURL: baseURL)
        }
    }

    private func fetchADBSnapshot(serial: String) throws -> RemoteSnapshot {
        let indexResult = try adb(["shell", "cat", remoteIndex], serial: serial, check: false)
        let parsed = parseMetadataIndex(indexResult.stdout)
        if !parsed.files.isEmpty {
            return RemoteSnapshot(files: parsed.files, captureDates: parsed.captureDates, remoteRoot: remoteRoot)
        }
        let files = try listRemoteFiles(serial: serial)
        return RemoteSnapshot(files: files, captureDates: [:], remoteRoot: remoteRoot)
    }

    private func fetchHTTPSnapshot(baseURL: String) throws -> RemoteSnapshot {
        let statusObject = try fetchJSONObject(urlString: "\(baseURL)/api/status")
        let indexText = try fetchText(urlString: "\(baseURL)/api/index")
        let parsed = parseMetadataIndex(indexText)
        let saveLocations = statusObject["saveLocations"] as? [String: Any]
        let reportedRemoteRoot = saveLocations?["mediaRoot"] as? String ?? baseURL
        return RemoteSnapshot(files: parsed.files, captureDates: parsed.captureDates, remoteRoot: reportedRemoteRoot)
    }

    private func discoverHTTPBaseURL(serial: String) throws -> String? {
        let result = try adb(["shell", "cat", remoteStatus], serial: serial, check: false)
        guard result.terminationStatus == 0,
              let data = result.stdout.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let transferServer = object["transferServer"] as? [String: Any],
              (transferServer["running"] as? Bool) == true,
              let baseURLs = transferServer["baseUrls"] as? [String] else {
            return nil
        }

        for candidate in baseURLs.compactMap({ normalizedBaseURL($0) }) {
            if isHTTPHealthy(baseURL: candidate) {
                return candidate
            }
        }
        return nil
    }

    private func parseMetadataIndex(_ raw: String) -> (files: [RemoteFile], captureDates: [String: Date]) {
        var filesByKey: [String: RemoteFile] = [:]
        var captureDates: [String: Date] = [:]
        for rawLine in raw.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty,
                  let data = line.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let category = object["category"] as? String,
                  let basename = object["basename"] as? String,
                  category == "videos" || category == "captures" else {
                continue
            }
            let remoteFile = RemoteFile(category: category, basename: basename)
            filesByKey[remoteFile.key] = remoteFile
            let timestampMs = (object["recordedAtMs"] as? NSNumber)?.doubleValue
                ?? (object["startedAtMs"] as? NSNumber)?.doubleValue
            if let timestampMs {
                captureDates[remoteFile.key] = Date(timeIntervalSince1970: timestampMs / 1000.0)
            }
        }

        return (
            filesByKey.values.sorted { $0.basename > $1.basename },
            captureDates
        )
    }

    private func streamFilesAsTar(serial: String, files: [RemoteFile], destination: URL) throws {
        let adbPath = try resolveADB()
        let relativePaths = files.map { $0.category == "videos" ? "uvc-videos/\($0.basename)" : "uvc-captures/\($0.basename)" }
        let remoteCommand = "cd \(shellQuote(remoteRoot)) && tar -cf - " + relativePaths.map(shellQuote).joined(separator: " ")
        NSLog("PhonecaptureImportBar streamFilesAsTar: serial=%@ files=%ld destination=%@", serial, files.count, destination.path)

        let adbProcess = Process()
        adbProcess.executableURL = URL(fileURLWithPath: adbPath)
        adbProcess.arguments = ["-s", serial, "exec-out", "sh", "-c", remoteCommand]
        adbProcess.environment = mergedEnvironment()

        let tarProcess = Process()
        tarProcess.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        tarProcess.arguments = ["-xf", "-", "-C", destination.path]
        tarProcess.environment = mergedEnvironment()

        let streamPipe = Pipe()
        let adbErr = Pipe()
        let tarErr = Pipe()
        adbProcess.standardOutput = streamPipe.fileHandleForWriting
        adbProcess.standardError = adbErr
        tarProcess.standardInput = streamPipe.fileHandleForReading
        tarProcess.standardError = tarErr

        try tarProcess.run()
        try adbProcess.run()
        NSLog("PhonecaptureImportBar streamFilesAsTar: launched adb_pid=%d tar_pid=%d", adbProcess.processIdentifier, tarProcess.processIdentifier)

        streamPipe.fileHandleForWriting.closeFile()
        streamPipe.fileHandleForReading.closeFile()

        adbProcess.waitUntilExit()
        tarProcess.waitUntilExit()

        let adbStderr = String(data: adbErr.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        let tarStderr = String(data: tarErr.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        NSLog("PhonecaptureImportBar streamFilesAsTar: adb_exit=%d tar_exit=%d adb_err=%@ tar_err=%@", adbProcess.terminationStatus, tarProcess.terminationStatus, adbStderr, tarStderr)

        if adbProcess.terminationStatus != 0 {
            let message = adbStderr.trimmingCharacters(in: .whitespacesAndNewlines)
            throw NSError(
                domain: "PhonecaptureImportBar",
                code: Int(adbProcess.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: message.isEmpty ? "adb streamed import failed." : message]
            )
        }
        if tarProcess.terminationStatus != 0 {
            let message = tarStderr.trimmingCharacters(in: .whitespacesAndNewlines)
            throw NSError(
                domain: "PhonecaptureImportBar",
                code: Int(tarProcess.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: message.isEmpty ? "Local archive extraction failed." : message]
            )
        }
    }

    private func downloadFilesOverHTTP(baseURL: String, files: [RemoteFile], destination: URL) throws {
        for file in files {
            let localDirectory = file.category == "videos"
                ? destination.appendingPathComponent("uvc-videos", isDirectory: true)
                : destination.appendingPathComponent("uvc-captures", isDirectory: true)
            let localFile = localDirectory.appendingPathComponent(file.basename, isDirectory: false)
            let remotePathComponent = file.category == "videos" ? "videos" : "captures"
            let urlString = "\(baseURL)/api/files/\(remotePathComponent)/\(file.basename)"
            try downloadFile(urlString: urlString, destination: localFile)
        }
    }

    private func downloadFile(urlString: String, destination: URL) throws {
        guard let url = URL(string: urlString) else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Invalid download URL: \(urlString)"
            ])
        }
        try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }

        let semaphore = DispatchSemaphore(value: 0)
        let tempDirectory = fileManager.temporaryDirectory
        var downloadError: Error?

        let task = urlSession.downloadTask(with: url) { tempURL, response, error in
            defer { semaphore.signal() }
            if let error {
                downloadError = error
                return
            }
            guard let httpResponse = response as? HTTPURLResponse else {
                downloadError = NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: "Missing HTTP response while downloading \(url.lastPathComponent)."
                ])
                return
            }
            guard (200...299).contains(httpResponse.statusCode) else {
                downloadError = NSError(domain: "PhonecaptureImportBar", code: httpResponse.statusCode, userInfo: [
                    NSLocalizedDescriptionKey: "HTTP \(httpResponse.statusCode) while downloading \(url.lastPathComponent)."
                ])
                return
            }
            guard let tempURL else {
                downloadError = NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: "Download did not return a file for \(url.lastPathComponent)."
                ])
                return
            }
            do {
                let stagedURL = tempDirectory.appendingPathComponent(UUID().uuidString)
                try self.fileManager.moveItem(at: tempURL, to: stagedURL)
                try self.fileManager.moveItem(at: stagedURL, to: destination)
            } catch {
                downloadError = error
            }
        }
        task.resume()
        semaphore.wait()

        if let downloadError {
            throw downloadError
        }
    }

    private func detectJellySerial() throws -> String {
        let result = try adb(["devices", "-l"], serial: nil)
        for line in result.stdout.components(separatedBy: .newlines) {
            if line.contains("model:Jelly_Star"), line.contains(" device") {
                return line.split(separator: " ").first.map(String.init) ?? ""
            }
        }
        throw ImporterError.noJellyDetected
    }

    private func listRemoteFiles(serial: String) throws -> [RemoteFile] {
        var files: [RemoteFile] = []
        for category in ["videos", "captures"] {
            guard let remoteDir = remoteDirs[category] else { continue }
            let result = try adb(["shell", "ls", "-1", remoteDir], serial: serial, check: false)
            for line in result.stdout.components(separatedBy: .newlines) {
                let name = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty, !name.hasPrefix("ls:") {
                    files.append(RemoteFile(category: category, basename: name))
                }
            }
        }
        return files.sorted { $0.basename > $1.basename }
    }

    private func fetchJSONObject(urlString: String) throws -> [String: Any] {
        let data = try fetchData(urlString: urlString)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Invalid JSON returned from \(urlString)"
            ])
        }
        return object
    }

    private func fetchText(urlString: String, fast: Bool = false) throws -> String {
        let data = try fetchData(urlString: urlString, fast: fast)
        guard let string = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Unreadable text returned from \(urlString)"
            ])
        }
        return string
    }

    private func fetchData(urlString: String, fast: Bool = false) throws -> Data {
        guard let url = URL(string: urlString) else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Invalid URL: \(urlString)"
            ])
        }

        let semaphore = DispatchSemaphore(value: 0)
        var resultData: Data?
        var resultResponse: URLResponse?
        var resultError: Error?

        let task = (fast ? discoverySession : urlSession).dataTask(with: url) { data, response, error in
            resultData = data
            resultResponse = response
            resultError = error
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()

        if let resultError {
            throw resultError
        }
        guard let httpResponse = resultResponse as? HTTPURLResponse else {
            throw NSError(domain: "PhonecaptureImportBar", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Missing HTTP response from \(urlString)"
            ])
        }
        guard (200...299).contains(httpResponse.statusCode), let resultData else {
            throw NSError(domain: "PhonecaptureImportBar", code: httpResponse.statusCode, userInfo: [
                NSLocalizedDescriptionKey: "HTTP \(httpResponse.statusCode) from \(urlString)"
            ])
        }
        return resultData
    }

    private func isHTTPHealthy(baseURL: String, fast: Bool = false) -> Bool {
        do {
            let text = try fetchText(urlString: "\(baseURL)/healthz", fast: fast)
            return text.trimmingCharacters(in: .whitespacesAndNewlines) == "ok"
        } catch {
            return false
        }
    }

    private func normalizedBaseURL(_ baseURL: String?) -> String? {
        guard let baseURL else { return nil }
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
    }

    private func adb(_ args: [String], serial: String?, check: Bool = true) throws -> ShellResult {
        let adbPath = try resolveADB()
        var command = [adbPath]
        if let serial {
            command.append(contentsOf: ["-s", serial])
        }
        command.append(contentsOf: args)
        return try run(command, check: check)
    }

    private func run(_ command: [String], check: Bool) throws -> ShellResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: command[0])
        process.arguments = Array(command.dropFirst())
        process.environment = mergedEnvironment()

        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        process.standardOutput = stdoutPipe
        process.standardError = stderrPipe

        let stdoutHandle = stdoutPipe.fileHandleForReading
        let stderrHandle = stderrPipe.fileHandleForReading
        var stdoutData = Data()
        var stderrData = Data()
        let stdoutGroup = DispatchGroup()
        let stderrGroup = DispatchGroup()

        stdoutGroup.enter()
        stderrGroup.enter()

        stdoutHandle.readabilityHandler = { handle in
            let chunk = handle.availableData
            if chunk.isEmpty {
                handle.readabilityHandler = nil
                stdoutGroup.leave()
            } else {
                stdoutData.append(chunk)
            }
        }

        stderrHandle.readabilityHandler = { handle in
            let chunk = handle.availableData
            if chunk.isEmpty {
                handle.readabilityHandler = nil
                stderrGroup.leave()
            } else {
                stderrData.append(chunk)
            }
        }

        try process.run()
        process.waitUntilExit()
        stdoutGroup.wait()
        stderrGroup.wait()

        let stdout = String(data: stdoutData, encoding: .utf8) ?? ""
        let stderr = String(data: stderrData, encoding: .utf8) ?? ""

        if check, process.terminationStatus != 0 {
            let message = (stderr.isEmpty ? stdout : stderr).trimmingCharacters(in: .whitespacesAndNewlines)
            throw NSError(
                domain: "PhonecaptureImportBar",
                code: Int(process.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: message]
            )
        }

        return ShellResult(stdout: stdout, stderr: stderr, terminationStatus: process.terminationStatus)
    }

    private func resolveADB() throws -> String {
        let env = ProcessInfo.processInfo.environment
        let candidates = [
            env["ADB_PATH"],
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb",
        ].compactMap { $0 }

        for candidate in candidates where fileManager.isExecutableFile(atPath: candidate) {
            return candidate
        }

        if let path = env["PATH"] {
            for prefix in path.components(separatedBy: ":") {
                let candidate = URL(fileURLWithPath: prefix).appendingPathComponent("adb").path
                if fileManager.isExecutableFile(atPath: candidate) {
                    return candidate
                }
            }
        }

        throw ImporterError.adbNotFound
    }

    private func destinationRoot() throws -> URL {
        let config = try loadConfig()
        return URL(fileURLWithPath: config.destRoot, isDirectory: true)
    }

    private func loadConfig() throws -> AppConfig {
        let url = try configURL()
        if fileManager.fileExists(atPath: url.path) {
            let data = try Data(contentsOf: url)
            return try decoder.decode(AppConfig.self, from: data)
        }

        let fallbackRoot = URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Downloads", isDirectory: true)
            .appendingPathComponent("phonecapture-imports", isDirectory: true)

        let config = AppConfig(destRoot: fallbackRoot.path, httpBaseURL: nil)
        try saveConfig(config)
        return config
    }

    private func saveConfig(_ config: AppConfig) throws {
        let url = try configURL()
        try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try encoder.encode(config)
        try data.write(to: url, options: .atomic)
    }

    private func configURL() throws -> URL {
        try supportDirectory().appendingPathComponent("config.json")
    }

    private func supportDirectory() throws -> URL {
        try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("PhonecaptureImportBar", isDirectory: true)
    }

    private func mergedEnvironment() -> [String: String] {
        var environment = ProcessInfo.processInfo.environment
        environment["PATH"] = [
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/usr/sbin",
            "/sbin",
            environment["PATH"] ?? "",
        ]
        .filter { !$0.isEmpty }
        .joined(separator: ":")
        return environment
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }

    private func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
    }

    private func remoteCaptureDates(serial: String) throws -> [String: Date] {
        let result = try adb(["shell", "cat", remoteIndex], serial: serial, check: false)
        guard result.terminationStatus == 0, !result.stdout.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return [:]
        }

        var mapping: [String: Date] = [:]
        for rawLine in result.stdout.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            guard let data = line.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let category = object["category"] as? String,
                  let basename = object["basename"] as? String else {
                continue
            }
            let timestampMs = (object["recordedAtMs"] as? NSNumber)?.doubleValue
                ?? (object["startedAtMs"] as? NSNumber)?.doubleValue
            guard let timestampMs else { continue }
            mapping["\(category):\(basename)"] = Date(timeIntervalSince1970: timestampMs / 1000.0)
        }
        return mapping
    }

    private func preserveImportedTimestamp(localFile: URL, remoteFile: RemoteFile, captureDates: [String: Date]) throws {
        let date = captureDates[remoteFile.key] ?? fallbackDate(from: remoteFile.basename)
        guard let date else { return }
        try fileManager.setAttributes([.modificationDate: date], ofItemAtPath: localFile.path)
    }

    private func fallbackDate(from basename: String) -> Date? {
        let pattern = #"^uvc-(\d{8})-(\d{6})\.(mp4|jpg)$"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(location: 0, length: basename.utf16.count)
        guard let match = regex.firstMatch(in: basename, options: [], range: range),
              let dayRange = Range(match.range(at: 1), in: basename),
              let timeRange = Range(match.range(at: 2), in: basename) else {
            return nil
        }

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss"
        formatter.timeZone = .current
        return formatter.date(from: String(basename[dayRange]) + String(basename[timeRange]))
    }

    private func importedKeys(destRoot: URL) throws -> Set<String> {
        guard fileManager.fileExists(atPath: destRoot.path) else { return [] }
        let enumerator = fileManager.enumerator(
            at: destRoot,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        )

        var imported: Set<String> = []
        while let item = enumerator?.nextObject() as? URL {
            let values = try item.resourceValues(forKeys: [.isRegularFileKey])
            guard values.isRegularFile == true else { continue }
            let basename = item.lastPathComponent
            if basename.hasSuffix(".mp4") {
                imported.insert("videos:\(basename)")
            } else if basename.hasSuffix(".jpg") {
                imported.insert("captures:\(basename)")
            }
        }
        return imported
    }

    private func newestImportDirectory(destRoot: URL) throws -> URL? {
        guard fileManager.fileExists(atPath: destRoot.path) else { return nil }
        let entries = try fileManager.contentsOfDirectory(
            at: destRoot,
            includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )
        return try entries
            .filter { try $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true }
            .sorted {
                let lhs = try $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
                let rhs = try $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
                return lhs > rhs
            }
            .first
    }
}

private struct ShellResult {
    let stdout: String
    let stderr: String
    let terminationStatus: Int32
}

@MainActor
final class ImporterModel: ObservableObject {
    @Published var status: ImportStatus?
    @Published var isBusy = false
    @Published var lastMessage: String?
    @Published var connectionIssue: String?
    @Published var currentOperation: String?
    @Published var configuredDestinationPath: String

    private let backend: ImporterBackend
    private var pollTimer: Timer?
    private var refreshRequestedWhileBusy = false

    init() {
        let backend = ImporterBackend()
        self.backend = backend
        self.configuredDestinationPath = (try? backend.currentDestinationPath()) ?? ""
    }

    var menuTitle: String {
        "Fone"
    }

    var menuImage: String {
        if isImporting {
            return "iphone.gen3.radiowaves.left.and.right"
        }
        return "iphone.gen3"
    }

    var statusHeadline: String {
        if isImporting {
            return "Syncing Memories"
        }
        guard let status else { return "Fone" }
        if status.newFiles > 0 {
            return "Memories Ready"
        }
        return "All Good"
    }

    var statusDetail: String {
        if isImporting {
            return currentOperation ?? "Working in the background. You can close this window while it runs."
        }
        guard status != nil else {
            return ""
        }
        return "Fone found"
    }

    var showsADBDebugControls: Bool {
        backend.adbFallbackEnabled()
    }

    var canImport: Bool {
        guard let status else { return false }
        return !isBusy && status.newFiles > 0
    }

    var isImporting: Bool {
        currentOperation == "Importing new files..."
    }

    var statusTintName: String {
        if isImporting {
            return "accent"
        }
        guard let status else { return "secondary" }
        return status.newFiles > 0 ? "orange" : "green"
    }

    func startPolling() {
        guard pollTimer == nil else { return }
        pollTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refresh()
            }
        }
    }

    func refresh() {
        if isBusy {
            refreshRequestedWhileBusy = true
            return
        }
        Task {
            await runBusyTask(operationLabel: "Refreshing device status...", messageOnSuccess: nil, operation: {
                try self.backend.status()
            }, applyResult: { status in
                self.connectionIssue = nil
                self.status = status
                self.configuredDestinationPath = status.destRoot
            })
        }
    }

    func importNew() {
        Task {
            await runBusyTask(operationLabel: "Importing new files...", messageOnSuccess: nil, operation: {
                let result = try self.backend.importNew()
                let status = try self.backend.status()
                return (result, status)
            }, applyResult: { payload in
                let (result, status) = payload
                if result.importedCount == 0 {
                    self.lastMessage = "No new memories right now."
                } else {
                    self.lastMessage = "Memories imported to \(result.destination)"
                }
                self.connectionIssue = nil
                self.status = status
                self.configuredDestinationPath = status.destRoot
            })
        }
    }

    func openLast() {
        Task {
            await runBusyTask(operationLabel: "Opening last import...", messageOnSuccess: nil, operation: {
                try self.backend.openLast()
            }, applyResult: { path in
                self.lastMessage = "Opened \(path)"
            })
        }
    }

    func openDestination() {
        Task {
            await runBusyTask(operationLabel: "Opening destination...", messageOnSuccess: nil, operation: {
                try self.backend.openDestination()
            }, applyResult: { path in
                self.configuredDestinationPath = path
                self.lastMessage = "Opened \(path)"
            })
        }
    }

    func chooseDestination() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Use Folder"
        panel.message = "Choose where imported Phonecapture files should be saved."
        if panel.runModal() == .OK, let url = panel.url {
            Task {
                await runBusyTask(operationLabel: "Updating destination...", messageOnSuccess: nil, operation: {
                    let path = try self.backend.setDestination(path: url.path)
                    let status = try self.backend.status()
                    return (path, status)
                }, applyResult: { payload in
                    let (path, status) = payload
                    self.lastMessage = "Saving imports to \(path)"
                    self.connectionIssue = nil
                    self.status = status
                    self.configuredDestinationPath = path
                })
            }
        }
    }

    func restartADB() {
        Task {
            await runBusyTask(operationLabel: "Restarting adb...", messageOnSuccess: nil, operation: {
                try self.backend.restartADB()
                return try self.backend.status()
            }, applyResult: { status in
                self.connectionIssue = nil
                self.status = status
                self.configuredDestinationPath = status.destRoot
                self.lastMessage = "Restarted adb."
            })
        }
    }

    private func runBusyTask<T>(operationLabel: String, messageOnSuccess: String?, operation: @escaping () throws -> T, applyResult: @escaping (T) throws -> Void) async {
        while isBusy {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        isBusy = true
        currentOperation = operationLabel
        NSLog("PhonecaptureImportBar runBusyTask: begin")
        defer {
            isBusy = false
            currentOperation = nil
            if refreshRequestedWhileBusy {
                refreshRequestedWhileBusy = false
                refresh()
            }
        }

        do {
            let result = try await runOffMain(operation)
            try applyResult(result)
            if let messageOnSuccess {
                lastMessage = messageOnSuccess
            }
            NSLog("PhonecaptureImportBar runBusyTask: success")
        } catch {
            NSLog("PhonecaptureImportBar runBusyTask: error=%@", error.localizedDescription)
            if isConnectivityError(error) {
                status = nil
                connectionIssue = error.localizedDescription
                if isImporting || operationLabel == "Restarting adb..." {
                    lastMessage = error.localizedDescription
                }
            } else {
                lastMessage = error.localizedDescription
            }
        }
    }

    private func runOffMain<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    continuation.resume(returning: try operation())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func isConnectivityError(_ error: Error) -> Bool {
        let message = error.localizedDescription.lowercased()
        return message.contains("no phonecapture device found")
            || message.contains("unable to locate adb")
            || message.contains("device offline")
            || message.contains("no devices/emulators found")
            || message.contains("http ")
            || message.contains("could not connect to the server")
            || message.contains("timed out")
    }
}
