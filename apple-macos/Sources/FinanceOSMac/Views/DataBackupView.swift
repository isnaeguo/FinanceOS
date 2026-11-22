import SwiftUI
import UniformTypeIdentifiers
import AppKit

struct DataBackupView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router

    @State private var statusMessage: StatusMessage?
    @State private var pendingRestoreURL: URL?
    @State private var isAddingCategory = false
    @State private var statementSource: StatementImportSheet.Source?
    @State private var busy = false

    struct StatusMessage: Equatable {
        let text: String
        let isError: Bool
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                dataFilesCard
                backupCard
                categoriesCard
                aboutCard
                if busy { ProgressView().controlSize(.small) }
            }
            .disabled(busy)
            .padding(20)
        }
        .navigationTitle("数据与备份")
        .alert(
            statusMessage?.text ?? "",
            isPresented: isStatusPresented
        ) {
            Button("好") { statusMessage = nil }
        }
        .confirmationDialog(
            "用备份文件完整替换本机数据？",
            isPresented: isRestorePresented,
            titleVisibility: .visible
        ) {
            Button("替换本机数据", role: .destructive) {
                if let url = pendingRestoreURL { performRestore(from: url) }
                pendingRestoreURL = nil
            }
            Button("取消", role: .cancel) { pendingRestoreURL = nil }
        } message: {
            Text("本机现有的流水、分类和预算将全部被备份内容覆盖，该操作无法撤销。")
        }
        .sheet(isPresented: $isAddingCategory) {
            categorySheet
        }
        .sheet(item: $statementSource) { source in
            StatementImportSheet(source: source) { count, duplicates in
                statusMessage = .init(text: "已导入 \(count) 笔，跳过本机已有的 \(duplicates) 笔。", isError: false)
            }
        }
        .task(id: router.droppedStatement) {
            guard let url = router.droppedStatement else { return }
            statementSource = .file(url)
            router.droppedStatement = nil
        }
    }

    private var isStatusPresented: Binding<Bool> {
        Binding(
            get: { statusMessage != nil },
            set: { if !$0 { statusMessage = nil } }
        )
    }

    private var isRestorePresented: Binding<Bool> {
        Binding(
            get: { pendingRestoreURL != nil },
            set: { if !$0 { pendingRestoreURL = nil } }
        )
    }

    private var categorySheet: some View {
        AddCategorySheet(
            store: store,
            onCreated: { name in
                statusMessage = .init(text: "已添加分类：\(name)", isError: false)
            },
            onFailed: { message in
                statusMessage = .init(text: message, isError: true)
            }
        )
    }

    // MARK: - 数据文件

    private var dataFilesCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 12) {
                Label("数据文件", systemImage: "doc.text.fill")
                    .font(.headline)
                Text("JSON 备份包含流水、分类和预算。账单可从 CSV、TSV、XLSX、OFX、QFX、QBO 或剪贴板导入，确认前可预览和调整表头。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    FinanceActionButton(title: "导出 JSON", symbol: "square.and.arrow.up", action: exportJSON)
                    FinanceActionButton(title: "导入 JSON", symbol: "square.and.arrow.down", action: importJSON)
                    FinanceActionButton(title: "导出 CSV", symbol: "tablecells", action: exportCSV)
                    FinanceActionButton(title: "导入账单文件…", symbol: "tablecells.badge.ellipsis", action: pickStatement)
                    FinanceActionButton(title: "粘贴账单…", symbol: "clipboard") {
                        statementSource = .paste(NSPasteboard.general.string(forType: .string) ?? "")
                    }
                }
            }
        }
    }

    // MARK: - 备份

    private var backupCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 12) {
                Label("完整备份", systemImage: "externaldrive.badge.timemachine")
                    .font(.headline)
                Text("备份文件包含当前全部数据；恢复前会再次确认，恢复将在一次写入中完整替换本机数据。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                HStack {
                    FinanceActionButton(title: "创建备份文件", symbol: "shippingbox.and.arrow.backward", action: createBackup)
                    FinanceActionButton(title: "从备份恢复…", symbol: "clock.arrow.trianglehead.counterclockwise.rotate.90", action: startRestore)
                }
            }
        }
    }

    // MARK: - 分类管理

    private var categoriesCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("分类管理", systemImage: "square.grid.2x2")
                        .font(.headline)
                    Spacer()
                    Button {
                        isAddingCategory = true
                    } label: {
                        Label("新建分类", systemImage: "plus")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                ForEach(store.categories) { category in
                    HStack(spacing: 10) {
                        CategoryIconView(category: category, size: 28)
                        Text(category.name)
                            .font(.callout)
                        if category.isSystem {
                            Text("内置")
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.quaternary, in: Capsule())
                                .foregroundStyle(.secondary)
                        }
                        Text(category.type.label)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        if !category.isSystem {
                            Button(role: .destructive) {
                                _ = store.deleteCategory(id: category.id)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    // MARK: - 关于

    private var aboutCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 8) {
                Label("关于", systemImage: "info.circle")
                    .font(.headline)
                Text("FinanceOS for macOS · \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "开发版")")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    Text("数据文件：")
                        .foregroundStyle(.secondary)
                    Text(store.storeLocationDescription)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                    Button("打开文件夹") {
                        let url = store.storeURL().deletingLastPathComponent()
                        NSWorkspace.shared.open(url)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .font(.callout)
            }
        }
    }

    // MARK: - 动作

    private func exportJSON() {
        saveJSON(prefix: "FinanceOS-数据")
    }

    private func createBackup() {
        saveJSON(prefix: "FinanceOS-备份")
    }

    private func saveJSON(prefix: String) {
        guard let url = makeSavePanel(name: "\(prefix)-\(fileStamp())", contentType: .json) else { return }
        perform {
            let text = try await store.exportCommittedJSON()
            try text.write(to: url, atomically: true, encoding: .utf8)
            return "已保存 \(url.lastPathComponent)。"
        }
    }

    private func importJSON() {
        guard let url = makeOpenPanel(contentTypes: [.json]) else { return }
        perform {
            let snapshot = try await readBackup(url)
            let result = try await store.mergeBackup(snapshot)
            return "已合并备份。已处理流水 \(result.transactionCount) 笔、分类 \(result.categoryCount) 个、预算 \(result.budgetCount) 条。"
        }
    }

    private func exportCSV() {
        guard let url = makeSavePanel(name: "FinanceOS-流水-\(fileStamp())", contentType: .commaSeparatedText) else { return }
        perform {
            try await store.exportCommittedCSV().write(to: url, atomically: true, encoding: .utf8)
            return "已导出 \(url.lastPathComponent)。"
        }
    }

    private func pickStatement() {
        let extensions = ["csv", "tsv", "txt", "xlsx", "ofx", "qfx", "qbo"]
        let types = extensions.compactMap { UTType(filenameExtension: $0) }
        guard let url = makeOpenPanel(contentTypes: types) else { return }
        statementSource = .file(url)
    }

    private func startRestore() {
        pendingRestoreURL = makeOpenPanel(contentTypes: [.json])
    }

    private func performRestore(from url: URL) {
        perform {
            let snapshot = try await readBackup(url)
            let result = try await store.replaceBackup(snapshot)
            return "已恢复流水 \(result.transactionCount) 笔、分类 \(result.categoryCount) 个、预算 \(result.budgetCount) 条。"
        }
    }

    private func readBackup(_ url: URL) async throws -> FinanceDataSnapshot {
        try await Task.detached(priority: .userInitiated) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            let maximum = 64 * 1024 * 1024
            let data = try handle.read(upToCount: maximum + 1) ?? Data()
            guard data.count <= maximum, let content = String(data: data, encoding: .utf8) else {
                throw DataTransferError(message: "备份须为 UTF-8 JSON 文件，且不超过 64 MB。")
            }
            return try FinanceDataJsonCodec.decode(content)
        }.value
    }

    private func perform(_ operation: @escaping @MainActor () async throws -> String) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                statusMessage = .init(text: try await operation(), isError: false)
            } catch {
                statusMessage = .init(text: error.localizedDescription, isError: true)
            }
        }
    }

    // MARK: - 文件面板

    private func fileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return formatter.string(from: Date())
    }

    @discardableResult
    private func makeSavePanel(name: String, contentType: UTType) -> URL? {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [contentType]
        panel.nameFieldStringValue = name
        panel.canCreateDirectories = true
        return panel.runModal() == .OK ? panel.url : nil
    }

    private func makeOpenPanel(contentTypes: [UTType]) -> URL? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = contentTypes
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        return panel.runModal() == .OK ? panel.url : nil
    }
}

// MARK: - 数据操作按钮

struct FinanceActionButton: View {
    let title: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .contentShape(.rect(cornerRadius: 14))
        }
        .buttonStyle(.bordered)
    }
}

// MARK: - 新建分类

struct AddCategorySheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: FinanceStore
    let onCreated: (String) -> Void
    let onFailed: (String) -> Void

    @State private var name = ""
    @State private var type: CategoryType = .expense
    @State private var iconKey = "other"

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("新建分类")
                .font(.title3.weight(.semibold))

            VStack(alignment: .leading, spacing: 8) {
                Text("名称").font(.callout).foregroundStyle(.secondary)
                TextField("例如：宠物", text: $name)
                    .textFieldStyle(.roundedBorder)
            }

            Picker("类型", selection: $type) {
                Text("支出").tag(CategoryType.expense)
                Text("收入").tag(CategoryType.income)
                Text("通用").tag(CategoryType.common)
            }
            .pickerStyle(.segmented)

            VStack(alignment: .leading, spacing: 8) {
                Text("图标").font(.callout).foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 56), spacing: 8)], spacing: 8) {
                    ForEach(selectableIconKeys, id: \.self) { key in
                        let isSelected = iconKey == key
                        Button {
                            iconKey = key
                        } label: {
                            let visual = CategoryVisual.resolved(iconKey: key, type: type)
                            Image(systemName: visual.symbol)
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(visual.color)
                                .frame(width: 44, height: 44)
                                .background(
                                    isSelected ? visual.color.opacity(0.25) : Color.primary.opacity(0.05),
                                    in: Circle()
                                )
                                .overlay(Circle().strokeBorder(isSelected ? visual.color : .clear, lineWidth: 1.5))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            HStack {
                Spacer()
                Button("取消") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("创建") { save() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 420)
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        if store.categories.contains(where: { $0.name == trimmed }) {
            onFailed("已存在同名分类：\(trimmed)")
            dismiss()
            return
        }
        _ = store.addCategory(name: trimmed, type: type, iconKey: iconKey)
        onCreated(trimmed)
        dismiss()
    }
}
