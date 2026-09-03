import SwiftUI
import UniformTypeIdentifiers
import AppKit

struct DataBackupView: View {
    @Environment(FinanceStore.self) private var store

    @State private var statusMessage: StatusMessage?
    @State private var pendingRestoreURL: URL?
    @State private var isAddingCategory = false

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
            }
            .padding(20)
        }
        .background(AuroraBackground())
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
        GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("数据文件", systemImage: "doc.text.fill")
                    .font(.headline)
                Text("JSON 为 FinanceOS 完整格式（流水、分类、预算），CSV 仅包含流水并以最小货币单位保证金额无损。普通导入按 ID 合并，不删除本机已有数据。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    GlassActionButton(title: "导出 JSON", symbol: "square.and.arrow.up", action: exportJSON)
                    GlassActionButton(title: "导入 JSON", symbol: "square.and.arrow.down", action: importJSON)
                    GlassActionButton(title: "导出 CSV", symbol: "tablecells", action: exportCSV)
                    GlassActionButton(title: "导入 CSV", symbol: "tablecells.badge.ellipsis", action: importCSV)
                }
            }
        }
    }

    // MARK: - 备份

    private var backupCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("完整备份", systemImage: "externaldrive.badge.timemachine")
                    .font(.headline)
                Text("备份文件包含当前全部数据；恢复前会再次确认，恢复将在一次写入中完整替换本机数据。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                HStack {
                    GlassActionButton(title: "创建备份文件", symbol: "shippingbox.and.arrow.backward", action: createBackup)
                    GlassActionButton(title: "从备份恢复…", symbol: "clock.arrow.trianglehead.counterclockwise.rotate.90", action: startRestore)
                }
            }
        }
    }

    // MARK: - 分类管理

    private var categoriesCard: some View {
        GlassCard {
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
                    .buttonStyle(.glass)
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
                            .buttonStyle(.glass)
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
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Label("关于", systemImage: "info.circle")
                    .font(.headline)
                Text("FinanceOS for macOS · 版本 0.4.2 · isnaeguo")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Text("基于 FinanceOS shared 领域模型构建的原生 Liquid Glass 应用。")
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
                    .buttonStyle(.glass)
                    .controlSize(.small)
                }
                .font(.callout)
            }
        }
    }

    // MARK: - 动作

    private func exportJSON() {
        guard let url = makeSavePanel(
            name: "FinanceOS-数据-\(fileStamp())",
            contentType: .json
        ) else { return }
        do {
            try store.exportJSON().write(to: url, atomically: true, encoding: .utf8)
            statusMessage = .init(text: "已导出 JSON 数据到 \(url.lastPathComponent)", isError: false)
        } catch {
            statusMessage = .init(text: "导出失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func importJSON() {
        guard let url = makeOpenPanel(contentTypes: [.json]) else { return }
        do {
            let content = try String(contentsOf: url, encoding: .utf8)
            let result = try store.importJSON(content)
            statusMessage = .init(
                text: "导入完成：新增流水 \(result.transactionCount) 笔、分类 \(result.categoryCount) 个、预算 \(result.budgetCount) 条。",
                isError: false
            )
        } catch let error as DataTransferError {
            statusMessage = .init(text: error.message, isError: true)
        } catch {
            statusMessage = .init(text: "导入失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func exportCSV() {
        guard let url = makeSavePanel(
            name: "FinanceOS-流水-\(fileStamp())",
            contentType: .commaSeparatedText
        ) else { return }
        do {
            try store.exportCSV().write(to: url, atomically: true, encoding: .utf8)
            statusMessage = .init(text: "已导出流水 CSV 到 \(url.lastPathComponent)", isError: false)
        } catch {
            statusMessage = .init(text: "导出失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func importCSV() {
        guard let url = makeOpenPanel(contentTypes: [.commaSeparatedText, .plainText, .spreadsheet, .data]) else { return }
        do {
            let data = try Data(contentsOf: url)
            let result = try store.importSpreadsheetFile(data)
            statusMessage = .init(
                text: "导入完成：新增流水 \(result.transactionCount) 笔。",
                isError: false
            )
        } catch let error as DataTransferError {
            statusMessage = .init(text: error.message, isError: true)
        } catch {
            statusMessage = .init(text: "导入失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func createBackup() {
        guard let url = makeSavePanel(
            name: "FinanceOS-备份-\(fileStamp())",
            contentType: .json
        ) else { return }
        do {
            try store.exportJSON().write(to: url, atomically: true, encoding: .utf8)
            statusMessage = .init(text: "备份已创建：\(url.lastPathComponent)", isError: false)
        } catch {
            statusMessage = .init(text: "备份失败：\(error.localizedDescription)", isError: true)
        }
    }

    private func startRestore() {
        guard let url = makeOpenPanel(contentTypes: [.json]) else { return }
        pendingRestoreURL = url
    }

    private func performRestore(from url: URL) {
        do {
            let content = try String(contentsOf: url, encoding: .utf8)
            let result = try store.restoreFromBackup(content)
            statusMessage = .init(
                text: "恢复完成：本机数据已替换为 \(result.transactionCount) 笔流水、\(result.categoryCount) 个分类、\(result.budgetCount) 条预算。",
                isError: false
            )
        } catch let error as DataTransferError {
            statusMessage = .init(text: error.message, isError: true)
        } catch {
            statusMessage = .init(text: "恢复失败：\(error.localizedDescription)", isError: true)
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

// MARK: - 通用玻璃按钮

struct GlassActionButton: View {
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
        .buttonStyle(.glass)
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
                    .buttonStyle(.glassProminent)
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
