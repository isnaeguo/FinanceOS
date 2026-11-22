import SwiftUI

/// 跨页面的轻量路由状态，供菜单栏命令触发各页面的动作。
@Observable
@MainActor
final class AppRouter {
    var isAddSheetPresented = false
    var section: SidebarSection = .workspace
    var droppedStatement: URL?
    var showWorkspaceChat = false
    var workspaceBookId = ""

    func openWorkspaceChat() {
        showWorkspaceChat = true
        section = .workspace
    }
}

/// 侧边栏分区。
enum SidebarSection: String, CaseIterable, Identifiable, Hashable {
    case workspace
    case dashboard
    case transactions
    case budgets
    case ai
    case sync
    case data

    var id: String { rawValue }

    var label: String {
        switch self {
        case .workspace: "财务工作台"
        case .dashboard: "收支趋势"
        case .transactions: "流水"
        case .budgets: "预算"
        case .ai: "AI 账单助手"
        case .sync: "局域网共享"
        case .data: "数据与备份"
        }
    }

    var symbol: String {
        switch self {
        case .workspace: "square.grid.2x2"
        case .dashboard: "chart.xyaxis.line"
        case .transactions: "list.bullet.rectangle.fill"
        case .budgets: "target"
        case .ai: "sparkles"
        case .sync: "antenna.radiowaves.left.and.right"
        case .data: "externaldrive.fill"
        }
    }
}

@main
struct FinanceOSApp: App {
    @State private var store = FinanceStore()
    @State private var router = AppRouter()
    @State private var workspace = MacFinanceWorkspace()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(router)
                .environment(workspace)
                .tint(WorkspacePalette.accent)
                .frame(minWidth: 900, minHeight: 640)
        }
        .windowResizability(.contentMinSize)
        .windowToolbarStyle(.unifiedCompact)
        .defaultSize(width: 1180, height: 800)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("记一笔…") {
                    router.isAddSheetPresented = true
                }
                .keyboardShortcut("n", modifiers: .command)
            }
        }
    }
}

struct RootView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(MacFinanceWorkspace.self) private var workspace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        @Bindable var router = router
        NavigationSplitView {
            sidebar
        } detail: {
            detailPane
                .id(router.section)
                .transition(reduceMotion ? .identity : FinanceMotion.reveal)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(WorkspacePalette.background)
                .animation(reduceMotion ? nil : FinanceMotion.short, value: router.section)
        }
            .task { await workspace.load(beside: store.storeURL()) }
            .sheet(isPresented: $router.isAddSheetPresented) {
                AddTransactionSheet(store: store, draft: TransactionDraft.new())
            }
            .alert("账本操作", isPresented: Binding(
                get: { store.lastError != nil },
                set: { if !$0 { store.clearError() } }
            )) {
                Button("好") { store.clearError() }
            } message: {
                Text(store.lastError ?? "")
            }
    }

    private var sidebar: some View {
        List(selection: sectionSelection) {
            Section("账本") {
                navigationRow(.workspace)
                navigationRow(.dashboard)
                navigationRow(.transactions)
                navigationRow(.budgets)
            }
            Section("工具") {
                navigationRow(.ai)
                navigationRow(.sync)
                navigationRow(.data)
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("FinanceOS")
        .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 260)
    }

    private func navigationRow(_ section: SidebarSection) -> some View {
        Label {
            Text(section.label)
        } icon: {
            if section == .workspace || section == .ai {
                (section == .workspace ? FinanceGlyph.books : FinanceGlyph.chat)
                    .stroke(style: StrokeStyle(lineWidth: 1.2, lineCap: .round, lineJoin: .round))
                    .frame(width: 16, height: 16)
            } else {
                Image(systemName: section.symbol)
            }
        }
            .font(.callout.weight(.medium))
            .tag(section)
    }

    /// 显式把行 tag（SidebarSection）映射回 router 状态；可选项让 macOS 能处理未选中态。
    private var sectionSelection: Binding<SidebarSection?> {
        Binding(
            get: { router.section },
            set: { newValue in
                if let newValue { router.section = newValue }
            }
        )
    }

    @ViewBuilder
    private var detailPane: some View {
        switch router.section {
        case .workspace:
            FinanceWorkspaceView()
        case .dashboard:
            DashboardView()
        case .transactions:
            TransactionsView()
        case .budgets:
            BudgetView()
        case .ai:
            AiInsightsView()
        case .sync:
            LanShareView()
        case .data:
            DataBackupView()
        }
    }
}
