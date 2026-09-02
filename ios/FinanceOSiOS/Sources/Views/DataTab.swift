import SwiftUI
import UniformTypeIdentifiers

/// 数据页：导入（JSON/CSV/XLSX 自动识别）、导出与备份恢复。
struct DataTab: View {
    @Environment(FinanceStore.self) private var store

    @State private var showImporter = false
    @State private var showExporterJSON = false
    @State private var showExporterCSV = false
    @State private var jsonExport: JSONDocument?
    @State private var csvExport: CSVDocument?
    @State private var message: String?
    @State private var pendingRestore: PendingRestore?

    struct PendingRestore: Identifiable {
        let id = UUID()
        let text: String
        let transactionCount: Int
        let categoryCount: Int
        let budgetCount: Int
    }

    var body: some View {
        NavigationStack {
            List {
                Section("导入数据") {
                    Button {
                        showImporter = true
                    } label: {
                        Label("导入 JSON / CSV / XLSX", systemImage: "square.and.arrow.down")
                    }
                    Text("自动识别格式并按 ID 合并；不删除本机数据；<0.45 元与不计收支/退款行自动跳过")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("导出") {
                    Button {
                        do {
                            jsonExport = JSONDocument(data: Data(try store.exportJSON().utf8))
                            showExporterJSON = true
                        } catch {
                            message = "导出失败：\(error.localizedDescription)"
                        }
                    } label: {
                        Label("导出完整数据（JSON）", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        do {
                            csvExport = CSVDocument(data: Data(try store.exportCSV().utf8))
                            showExporterCSV = true
                        } catch {
                            message = "导出失败：\(error.localizedDescription)"
                        }
                    } label: {
                        Label("导出流水（CSV）", systemImage: "tablecells")
                    }
                }

                Section("备份") {
                    Button {
                        do {
                            jsonExport = JSONDocument(data: Data(try store.exportJSON().utf8))
                            showExporterJSON = true
                        } catch {
                            message = "备份失败：\(error.localizedDescription)"
                        }
                    } label: {
                        Label("创建备份文件", systemImage: "externaldrive")
                    }
                    Button {
                        showImporter = true
                    } label: {
                        Label("从备份恢复…", systemImage: "clock.arrow.circlepath")
                    }
                }

                Section("局域网共享") {
                    NavigationLink {
                        LanShareView()
                    } label: {
                        Label("局域网共享", systemImage: "network")
                    }
                    Text("在同一局域网内与电脑/安卓端手动同步（明文 HTTP，默认端口 45678）")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("关于") {
                    LabeledContent("版本", value: "0.4.2 · isnaeguo")
                    Text("数据保存在本机 Application Support/FinanceOS/store.json")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("数据")
#if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .fileImporter(
                isPresented: $showImporter,
                allowedContentTypes: spreadsheetTypes,
                allowsMultipleSelection: false
            ) { result in
                handleImported(result)
            }
            .fileExporter(
                isPresented: $showExporterJSON,
                document: jsonExport,
                contentType: .json,
                defaultFilename: "FinanceOS-data-\(fileStamp()).json"
            ) { _ in jsonExport = nil }
            .fileExporter(
                isPresented: $showExporterCSV,
                document: csvExport,
                contentType: .commaSeparatedText,
                defaultFilename: "FinanceOS-transactions-\(fileStamp()).csv"
            ) { _ in csvExport = nil }
            .alert(
                "结果",
                isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })
            ) {
                Button("好") { message = nil }
            } message: {
                Text(message ?? "")
            }
            .alert(
                "恢复本地备份？",
                isPresented: Binding(get: { pendingRestore != nil }, set: { if !$0 { pendingRestore = nil } }),
                presenting: pendingRestore
            ) { restore in
                Button("替换本机数据", role: .destructive) {
                    do {
                        let result = try store.restoreFromBackup(restore.text)
                        message = "恢复完成：\(result.transactionCount) 笔流水、\(result.categoryCount) 个分类、\(result.budgetCount) 条预算"
                    } catch {
                        message = "恢复失败：\(error.localizedDescription)"
                    }
                }
                Button("取消", role: .cancel) { pendingRestore = nil }
            } message: { restore in
                Text("备份含 \(restore.transactionCount) 笔流水、\(restore.categoryCount) 个分类、\(restore.budgetCount) 条预算，将完整替换本机数据。")
            }
        }
    }

    private var spreadsheetTypes: [UTType] {
        var types: [UTType] = [.json, .commaSeparatedText, .data]
        if let xlsx = UTType(filenameExtension: "xlsx") { types.append(xlsx) }
        if let csv = UTType(filenameExtension: "csv") { types.append(csv) }
        return types
    }

    private func handleImported(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            message = "导入失败：\(error.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let content = String(decoding: data, as: UTF8.self)
                    .replacingOccurrences(of: "\u{FEFF}", with: "")
                if content.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
                    // JSON：走“备份恢复”确认流程。
                    let snapshot = try FinanceDataJsonCodec.decode(content)
                    pendingRestore = PendingRestore(
                        text: content,
                        transactionCount: snapshot.transactions.count,
                        categoryCount: snapshot.categories.count,
                        budgetCount: snapshot.budgets.count
                    )
                } else {
                    let result = try store.importSpreadsheetFile(data)
                    message = "导入完成：\(result.transactionCount) 笔流水、\(result.categoryCount) 个分类、\(result.budgetCount) 条预算"
                }
            } catch {
                message = "导入失败：\(error.localizedDescription)"
            }
        }
    }

    private func fileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return formatter.string(from: Date())
    }
}
// MARK: - 导出文档

struct JSONDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct CSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
