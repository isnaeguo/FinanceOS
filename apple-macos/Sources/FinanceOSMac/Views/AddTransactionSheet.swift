import SwiftUI
import FinanceOSCore

/// 新增 / 编辑流水的可观察表单模型。
@Observable
@MainActor
final class TransactionDraft {
    var type: TransactionType = .expense
    var amountText = ""
    var categoryId: String?
    var date = Date()
    var account = ""
    var note = ""
    /// 正在编辑的流水 ID；`nil` 表示新增。
    var editingId: String?

    static func new() -> TransactionDraft { TransactionDraft() }

    static func editing(_ transaction: FinanceOSCore.Transaction) -> TransactionDraft {
        let draft = TransactionDraft()
        draft.type = transaction.type
        draft.amountText = formatMajorPlain(transaction.amount)
        draft.categoryId = transaction.categoryId
        draft.date = transaction.dateTime
        draft.account = transaction.accountId ?? ""
        draft.note = transaction.note ?? ""
        draft.editingId = transaction.id
        return draft
    }

    var parsedAmount: Int64? {
        parseAmountInMinorUnits(amountText)
    }

    var isValid: Bool {
        parsedAmount != nil && categoryId != nil && date <= Date().addingTimeInterval(1)
    }

    func save(to store: FinanceStore) {
        guard let amount = parsedAmount, let categoryId else { return }
        let note = note.trimmingCharacters(in: .whitespaces)
        let account = account.trimmingCharacters(in: .whitespaces)
        let transaction = FinanceOSCore.Transaction(
            id: editingId ?? UUID().uuidString,
            amount: amount,
            type: type,
            categoryId: categoryId,
            accountId: account.isEmpty ? nil : account,
            dateTime: date,
            note: note.isEmpty ? nil : note
        )
        if editingId != nil {
            store.updateTransaction(transaction)
        } else {
            store.addTransaction(transaction)
        }
    }

    /// 金额仅用于回填展示：整数部分 + 两位小数，不经过 Double。
    private static func formatMajorPlain(_ minor: Int64) -> String {
        let magnitude = abs(minor)
        return "\(magnitude / 100).\(String(format: "%02d", magnitude % 100))"
    }
}

struct AddTransactionSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: FinanceStore
    @State var draft: TransactionDraft

    @State private var previousAmountText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            amountField
            categoryPicker
            detailFields
            footer
        }
        .padding(24)
        .frame(width: 520)
        .frame(minHeight: 600)
        .onAppear {
            if draft.categoryId == nil {
                draft.categoryId = store.categories(for: draft.type).first?.id
            }
            previousAmountText = draft.amountText
        }
    }

    // MARK: - 类型

    private var header: some View {
        Picker("类型", selection: typeBinding) {
            Text("支出").tag(TransactionType.expense)
            Text("收入").tag(TransactionType.income)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }

    private var typeBinding: Binding<TransactionType> {
        Binding(
            get: { draft.type },
            set: { newValue in
                draft.type = newValue
                // 切换方向后，当前分类必须仍然可用。
                if let category = store.category(id: draft.categoryId), !category.accepts(newValue) {
                    draft.categoryId = store.categories(for: newValue).first?.id
                }
            }
        )
    }

    // MARK: - 金额

    private var amountField: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("¥")
                .font(.system(size: 30, weight: .semibold, design: .rounded))
                .foregroundStyle(.secondary)
            TextField("0.00", text: amountBinding)
                .textFieldStyle(.plain)
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .monospacedDigit()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 18))
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { draft.amountText },
            set: { candidate in
                if let normalized = normalizeAmountInput(candidate) {
                    draft.amountText = normalized
                    previousAmountText = normalized
                } else {
                    // 输入非法时保持上一次的合法内容。
                    draft.amountText = previousAmountText
                }
            }
        )
    }

    // MARK: - 分类

    private var categoryPicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("分类")
                .font(.callout)
                .foregroundStyle(.secondary)
            let options = store.categories(for: draft.type)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], spacing: 8) {
                ForEach(options) { category in
                    let isSelected = draft.categoryId == category.id
                    Button {
                        draft.categoryId = category.id
                    } label: {
                        HStack(spacing: 6) {
                            CategoryIconView(category: category, size: 22)
                            Text(category.name)
                                .font(.callout)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .frame(maxWidth: .infinity)
                        .contentShape(.rect(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .background {
                        if isSelected {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(category.colorForSelection.opacity(0.22))
                        } else {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(.quaternary.opacity(0.55))
                        }
                    }
                    .overlay {
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(isSelected ? category.colorForSelection.opacity(0.7) : .clear, lineWidth: 1.2)
                    }
                }
            }
        }
    }

    // MARK: - 明细

    private var detailFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            LabeledContent {
                DatePicker("日期", selection: $draft.date, in: ...Date())
                    .labelsHidden()
            } label: {
                Text("日期")
                    .foregroundStyle(.secondary)
            }

            LabeledContent {
                HStack {
                    TextField("可选，如：微信、招行卡", text: $draft.account)
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                    if !store.knownAccounts.isEmpty {
                        Menu {
                            ForEach(store.knownAccounts, id: \.self) { account in
                                Button(account) { draft.account = account }
                            }
                        } label: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()
                    }
                }
            } label: {
                Text("账户")
                    .foregroundStyle(.secondary)
            }

            LabeledContent {
                TextField("可选", text: $draft.note)
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
            } label: {
                Text("备注")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 18))
    }

    // MARK: - 底部操作

    private var footer: some View {
        HStack {
            if let error = validationError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            Spacer()
            Button("取消") { dismiss() }
                .keyboardShortcut(.cancelAction)
            Button(draft.editingId == nil ? "保存" : "更新") {
                draft.save(to: store)
                dismiss()
            }
            .buttonStyle(.glassProminent)
            .keyboardShortcut(.defaultAction)
            .disabled(!draft.isValid)
        }
    }

    private var validationError: String? {
        if draft.parsedAmount == nil {
            return draft.amountText.isEmpty ? "请输入金额" : "金额格式不正确"
        }
        if draft.categoryId == nil { return "请选择分类" }
        return nil
    }
}

private extension FinanceOSCore.Category {
    var colorForSelection: Color {
        CategoryVisual.resolved(for: self).color
    }
}
