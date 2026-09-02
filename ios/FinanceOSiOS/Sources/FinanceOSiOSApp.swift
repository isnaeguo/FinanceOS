import SwiftUI
import Observation
import WidgetKit

@main
struct FinanceOSiOSApp: App {
    @State private var store = FinanceStore()

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(store)
        }
    }
}

/// 四个主标签：总览 / 流水 / 预算 / 数据。
struct RootTabView: View {
    enum Tab: Hashable {
        case home, transactions, budget, data
    }

    @State private var selection: Tab = .home

    var body: some View {
        TabView(selection: $selection) {
            HomeTab()
                .tabItem { Label("总览", systemImage: "gauge.with.dots.needle.bottom.50percent") }
                .tag(Tab.home)
            TransactionsTab()
                .tabItem { Label("流水", systemImage: "list.bullet.rectangle.fill") }
                .tag(Tab.transactions)
            BudgetTab()
                .tabItem { Label("预算", systemImage: "target") }
                .tag(Tab.budget)
            DataTab()
                .tabItem { Label("数据", systemImage: "externaldrive.fill") }
                .tag(Tab.data)
        }
        .onReceive(NotificationCenter.default.publisher(for: .financeosDataDidChange)) { _ in
            WidgetCenter.shared.reloadTimelines(ofKind: "FinanceOSWidget")
        }
    }
}
