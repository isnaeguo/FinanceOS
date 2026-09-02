import SwiftUI
import WidgetKit
import FinanceOSCore

/// 跨页面的轻量路由状态，供菜单栏命令触发各页面的动作。
@Observable
@MainActor
final class AppRouter {
    var isAddSheetPresented = false
    var section: SidebarSection = .dashboard
}

/// 侧边栏分区。
enum SidebarSection: String, CaseIterable, Identifiable, Hashable {
    case dashboard
    case transactions
    case budgets
    case sync
    case data

    var id: String { rawValue }

    var label: String {
        switch self {
        case .dashboard: "总览"
        case .transactions: "流水"
        case .budgets: "预算"
        case .sync: "局域网共享"
        case .data: "数据与备份"
        }
    }

    var symbol: String {
        switch self {
        case .dashboard: "gauge.with.dots.needle.bottom.50percent"
        case .transactions: "list.bullet.rectangle.fill"
        case .budgets: "target"
        case .sync: "antenna.radiowaves.left.and.right"
        case .data: "externaldrive.fill"
        }
    }
}

@main
struct FinanceOSApp: App {
    @State private var store = FinanceStore()
    @State private var router = AppRouter()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(router)
                .frame(minWidth: 980, minHeight: 640)
        }
        .windowResizability(.contentMinSize)
        .defaultSize(width: 1160, height: 780)
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

    var body: some View {
        @Bindable var router = router
        NavigationSplitView(sidebar: { sidebar }, detail: { detailPane })
            .sheet(isPresented: $router.isAddSheetPresented) {
                AddTransactionSheet(store: store, draft: TransactionDraft.new())
            }
            // 数据落盘成功后通知小组件即时刷新，保证“本月概览”始终接近实时。
            .onReceive(NotificationCenter.default.publisher(for: .financeosDataDidChange)) { _ in
                Task { @MainActor in
                    WidgetCenter.shared.reloadTimelines(ofKind: "FinanceOSWidget")
                }
            }
    }

    private var sidebar: some View {
        List(selection: sectionSelection) {
            Section("FinanceOS") {
                ForEach(SidebarSection.allCases) { section in
                    Label(section.label, systemImage: section.symbol)
                        .tag(section)
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("FinanceOS")
        .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 260)
    }

    /// 显式把行 tag（SidebarSection）映射回 router 状态；可选项让 macOS 能处理未选中态。
    private var sectionSelection: Binding<SidebarSection?> {
        Binding(
            get: { router.section },
            set: { newValue in
                if let newValue {
                    router.section = newValue
                }
            }
        )
    }

    @ViewBuilder
    private var detailPane: some View {
        switch router.section {
        case .dashboard:
            DashboardView()
        case .transactions:
            TransactionsView()
        case .budgets:
            BudgetView()
        case .sync:
            LanShareView()
        case .data:
            DataBackupView()
        }
    }
}
