@preconcurrency import FinanceOSShared
import SwiftUI

struct StatementImportSheet: View {
    enum Source: Identifiable, Sendable {
        case file(URL)
        case paste(String)

        var id: String {
            switch self {
            case .file(let url): url.absoluteString
            case .paste: "paste"
            }
        }
    }

    private struct Mapping: Sendable, Equatable {
        var date = ""
        var amount = ""
        var credit = ""
        var debit = ""
        var direction = ""
        var merchant = ""
        var note = ""
        var account = ""
        var category = ""
        var externalId = ""
        var invert = false

        init() {}

        init(_ mapping: StatementColumnMapping) {
            date = mapping.date
            amount = mapping.amount
            credit = mapping.credit
            debit = mapping.debit
            direction = mapping.direction
            merchant = mapping.merchant
            note = mapping.note
            account = mapping.account
            category = mapping.category
            externalId = mapping.externalId
            invert = mapping.invertAmountSign
        }

        func toKMP() -> StatementColumnMapping {
            StatementColumnMapping(
                date: date, amount: amount, direction: direction, category: category,
                merchant: merchant, note: note, account: account, externalId: externalId,
                credit: credit, debit: debit, invertAmountSign: invert
            )
        }
    }

    nonisolated private static func prepare(source: Source, text: String) throws -> PreparedStatement {
        let importer = TableTransactionImporter.shared
        switch source {
        case .paste:
            return try importer.prepareText(content: text)
        case .file(let url):
            let hasAccess = url.startAccessingSecurityScopedResource()
            defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            let maximum = 16 * 1024 * 1024
            let bytes = try handle.read(upToCount: maximum + 1) ?? Data()
            guard bytes.count <= maximum else {
                throw DataTransferError(message: "账单文件超过 16 MB，请拆分后导入。")
            }
            return try importer.prepare(bytes: bytes.statementKotlinBytes)
        }
    }

    let source: Source
    let onImported: (Int, Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(FinanceStore.self) private var store
    @State private var statement: PreparedStatement?
    @State private var text: String
    @State private var error = ""
    @State private var busy = false
    @State private var mapping = Mapping()
    @State private var preview: KotlinImportResult?
    @State private var existingCount = 0
    @State private var converted: (result: KotlinImportResult, existingCount: Int)?
    @State private var rate = ""
    @State private var mappingOpen = false

    init(source: Source, onImported: @escaping (Int, Int) -> Void) {
        self.source = source
        self.onImported = onImported
        if case .paste(let contents) = source {
            _text = State(initialValue: contents)
        } else {
            _text = State(initialValue: "")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("导入账单").font(.title2.bold())
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if case .file(let url) = source {
                        Label(url.lastPathComponent, systemImage: "doc.text")
                    } else {
                        pasteEditor
                    }
                    if let preview { previewContent(preview) }
                    if statement != nil { mappingControls }
                    if !error.isEmpty {
                        Text(error).foregroundStyle(.red).font(.callout)
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Divider()
            HStack {
                if busy { ProgressView().controlSize(.small) }
                Spacer()
                Button("取消") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .disabled(busy)
                Button("确认导入", action: commit)
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || readyPreview == nil || readyPreview?.transactions.isEmpty == true)
            }
        }
        .padding(22)
        .frame(width: 660, height: 600)
        .interactiveDismissDisabled(busy)
        .task {
            switch source {
            case .file: await inspectAndPreview()
            case .paste(let contents) where !contents.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                await inspectAndPreview()
            case .paste: break
            }
        }
    }

    private var pasteEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("从电子表格复制含表头的账单，或粘贴 CSV 文本。").font(.callout)
            TextEditor(text: $text)
                .font(.body.monospaced())
                .frame(height: 100)
                .border(.quaternary)
                .accessibilityLabel("账单文本")
                .onChange(of: text) {
                    statement = nil
                    invalidatePreview()
                }
            Button("预览账单") { Task { await inspectAndPreview() } }
                .disabled(busy || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .disabled(busy)
    }

    private var mappingControls: some View {
        DisclosureGroup("调整列对应关系", isExpanded: $mappingOpen) {
            Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 8) {
                GridRow { column("日期", $mapping.date); column("金额", $mapping.amount) }
                GridRow { column("收入金额", $mapping.credit); column("支出金额", $mapping.debit) }
                GridRow { column("收支方向", $mapping.direction); column("分类", $mapping.category) }
                GridRow { column("商户", $mapping.merchant); column("备注", $mapping.note) }
                GridRow { column("账户", $mapping.account); column("订单号", $mapping.externalId) }
            }
            Toggle("反转金额正负（适用于支出为正数的账单）", isOn: Binding(
                get: { mapping.invert },
                set: { mapping.invert = $0; invalidatePreview() }
            ))
            Button("应用列对应关系", action: createPreview)
        }
        .disabled(busy)
    }

    private func column(_ label: String, _ value: Binding<String>) -> some View {
        Picker(label, selection: Binding(
            get: { value.wrappedValue },
            set: { value.wrappedValue = $0; invalidatePreview() }
        )) {
            Text("未选择").tag("")
            ForEach(statement?.columns.headers ?? [], id: \.self) { Text($0).tag($0) }
        }
    }

    private func previewContent(_ result: KotlinImportResult) -> some View {
        let visible = converted?.result ?? result
        let alreadyPresent = converted?.existingCount ?? existingCount
        let rows = visible.transactions
        return VStack(alignment: .leading, spacing: 8) {
            Text("\(visible.format) · 可新增 \(rows.count - alreadyPresent) 笔 · 重复 \(Int(visible.duplicateRows) + alreadyPresent) 笔 · 跳过 \(visible.skippedRows) 笔")
                .font(.headline)
            if result.requiresCurrencyConversion, let currency = result.sourceCurrencyCode {
                HStack {
                    TextField("1 \(currency) = 多少 CNY", text: $rate)
                        .onChange(of: rate) { converted = nil }
                    Button("按汇率预览") { convert(result) }
                }
                Text("账本以人民币记账。请按实际结算汇率填写。").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(Array(visible.issueMessages.enumerated()), id: \.offset) { _, message in
                Text(message).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            }
            ForEach(rows.prefix(8).map(Transaction.init(kmp:))) { row in
                HStack {
                    Text(row.dateTime.formatted(date: .numeric, time: .omitted))
                    Text(row.note ?? store.category(id: row.categoryId)?.name ?? "流水")
                        .lineLimit(1)
                    Spacer()
                    Text(row.type == .income ? "收入" : "支出")
                    Text(Decimal(row.amount) / 100, format: .number.precision(.fractionLength(2)))
                        .monospacedDigit()
                    if store.containsTransaction(id: row.id) { Text("已存在").font(.caption).foregroundStyle(.secondary) }
                }
                .font(.callout)
            }
        }
        .disabled(busy)
    }

    private var readyPreview: KotlinImportResult? {
        converted?.result ?? preview.flatMap { $0.requiresCurrencyConversion ? nil : $0 }
    }

    private func invalidatePreview() {
        preview = nil
        existingCount = 0
        converted = nil
        error = ""
    }

    private func inspectAndPreview() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        invalidatePreview()
        let source = source
        let text = text
        do {
            let prepared = try await Task.detached(priority: .userInitiated) {
                try Self.prepare(source: source, text: text)
            }.value
            statement = prepared.columns.format == "OFX" ? nil : prepared
            mapping = Mapping(prepared.columns.suggestedMapping)
            await decodePreview(prepared, mapping: nil)
        } catch { self.error = error.localizedDescription }
    }

    private func createPreview() {
        guard let statement, !busy else { return }
        let selected = mapping
        busy = true
        Task {
            defer { busy = false }
            await decodePreview(statement, mapping: selected)
        }
    }

    private func decodePreview(_ statement: PreparedStatement, mapping: Mapping?) async {
        invalidatePreview()
        let categories = store.categories
        let ledger = store.ledger
        do {
            let (result, count) = try await Task.detached(priority: .userInitiated) {
                let result = try statement.preview(
                    categories: categories.map { $0.toKMP() },
                    importedAtEpochMillis: SystemEpochClock().nowMillis(),
                    mapping: mapping?.toKMP()
                )
                let count = result.transactions.lazy.filter { ledger[$0.id] != nil }.count
                return (result, count)
            }.value
            existingCount = count
            preview = result
        } catch {
            self.error = error.localizedDescription
            mappingOpen = true
        }
    }

    private func convert(_ result: KotlinImportResult) {
        guard !busy else { return }
        busy = true
        error = ""
        let rate = rate
        let ledger = store.ledger
        Task {
            defer { busy = false }
            do {
                converted = try await Task.detached(priority: .userInitiated) {
                    let result = try TableTransactionImporter.shared.convertToCny(result: result, exchangeRateText: rate)
                    let count = result.transactions.lazy.filter { ledger[$0.id] != nil }.count
                    return (result, count)
                }.value
            } catch { self.error = error.localizedDescription }
        }
    }

    private func commit() {
        guard !busy, let result = readyPreview, !result.transactions.isEmpty else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let rows = result.transactions.map(Transaction.init(kmp:))
                let written = try await store.insertStatementTransactions(rows)
                onImported(written.transactionCount, rows.count - written.transactionCount)
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}

private extension Data {
    var statementKotlinBytes: KotlinByteArray {
        KotlinByteArray(size: Int32(count)) { index in
            KotlinByte(char: Int8(bitPattern: self[self.startIndex + Int(index.intValue)]))
        }
    }
}
