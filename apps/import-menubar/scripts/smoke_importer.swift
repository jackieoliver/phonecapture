import Foundation

@main
struct SmokeImporter {
    static func main() {
        let backend = ImporterBackend()
        do {
            let args = Array(CommandLine.arguments.dropFirst())
            if let destinationIndex = args.firstIndex(of: "--destination"),
               args.indices.contains(destinationIndex + 1) {
                let path = args[destinationIndex + 1]
                let updated = try backend.setDestination(path: path)
                print("destination=\(updated)")
            }

            let statusBefore = try backend.status()
            print("transport=\(statusBefore.device)")
            print("remoteRoot=\(statusBefore.remoteRoot)")
            print("totalFiles=\(statusBefore.totalFiles)")
            print("newFilesBefore=\(statusBefore.newFiles)")

            let result = try backend.importNew()
            print("importedCount=\(result.importedCount)")
            print("destinationUsed=\(result.destination)")

            let statusAfter = try backend.status()
            print("newFilesAfter=\(statusAfter.newFiles)")
            print("lastImportDir=\(statusAfter.lastImportDir ?? "")")
        } catch {
            fputs("error=\(error.localizedDescription)\n", stderr)
            exit(1)
        }
    }
}
