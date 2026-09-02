import SwiftUI

/// 新增/编辑流水的表单（iOS）。
struct AddTransactionSheetContent: View {
    @Environment(\.dismiss) private var dismiss
    let store: FinanceStore
    var editing: Transaction? = nil

    @State private var type: TransactionType = .expense
    @State private var amountText = ""
    @State private var categoryId: String?
    @State private var date = Date()
    @State private var account = ""
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("类型", selection: $type) {
                    Text("支出").tag(TransactionType.expense)
                    Text("收入").tag(TransactionType.income)
                }
                .pickerStyle(.segmented)
                .onChange(of: type) { _, newValue in
                    if let current = categoryId,
                       let category = store.category(id: current),
                       !category.accepts(newValue) {
                        categoryId = store.categories(for: newValue).first?.id
                    }
                }

                Section("金额") {
                    HStack {
                        Text("¥").foregroundStyle(.secondary)
                        TextField("0.00", text: amountBinding)
#if os(iOS)
                            .keyboardType(.decimalPad)
#endif
                            .font(.title2.weight(.semibold).monospacedDigit())
                    }
                }

                Section("分类") {
                    let options = store.categories(for: type)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                        ForEach(options) { category in
                            let selected = categoryId == category.id
                            Button {
                                categoryId = category.id
                            } label: {
                                HStack(spacing: 6) {
                                    CategoryIconView(category: category, size: 22)
                                    Text(category.name).font(.callout).lineLimit(1)
                                }
                                .padding(.vertical, 8)
                                .frame(maxWidth: .infinity)
                                .background(selected ? Color.accentColor.opacity(0.18) : Color.fosField)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .strokeBorder(selected ? Color.accentColor : .clear, lineWidth: 1.2)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section {
                    DatePicker("日期", selection: $date, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                    TextField("账户（可选）", text: $account)
#if os(iOS)
                        .textInputAutocapitalization(.never)
#endif
                    TextField("备注（可选）", text: $note)
                }
            }
            .navigationTitle(editing == nil ? "记一笔" : "编辑流水")
#if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(editing == nil ? "保存" : "更新") { save() }
                        .disabled(!isValid)
                }
            }
            .onAppear {
                if editing == nil, categoryId == nil {
                    categoryId = store.categories(for: type).first?.id
                }
                if let editing {
                    type = editing.type
                    categoryId = editing.categoryId
                    date = editing.dateTime
                    account = editing.accountId ?? ""
                    note = editing.note ?? ""
                    amountText = formatMajorPlain(editing.amount)
                }
            }
        }
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { amountText },
            set: { candidate in
                if let normalized = normalizeAmountInput(candidate) {
                    amountText = normalized
                }
            }
        )
    }

    private var parsedAmount: Int64? { parseAmountInMinorUnits(amountText) }

    private var isValid: Bool {
        parsedAmount != nil && categoryId != nil && date <= Date().addingTimeInterval(60)
    }

    private func save() {
        guard let amount = parsedAmount, let categoryId else { return }
        let transaction = Transaction(
            id: editing?.id ?? UUID().uuidString,
            amount: amount,
            type: type,
            categoryId: categoryId,
            accountId: account.trimmingCharacters(in: .whitespaces).isEmpty ? nil : account.trimmingCharacters(in: .whitespaces),
            dateTime: date,
            note: note.trimmingCharacters(in: .whitespaces).isEmpty ? nil : note.trimmingCharacters(in: .whitespaces)
        )
        if editing != nil {
            store.updateTransaction(transaction)
        } else {
            store.addTransaction(transaction)
        }
        dismiss()
    }

    private func formatMajorPlain(_ minor: Int64) -> String {
        "\(minor / 100).\(String(format: "%02lld", minor % 100))"
    }
}
