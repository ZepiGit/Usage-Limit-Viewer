import SwiftUI
import UsageLimitsKit

struct ProviderIconsScreen: View {
    @EnvironmentObject private var store: UsageStore

    var body: some View {
        List {
            ForEach(ProviderID.allCases, id: \.self) { provider in
                Section(provider.displayName) {
                    ForEach(ProviderIconCatalog.choices(for: provider)) { choice in
                        let selected = ProviderIconCatalog.selected(for: provider, id: store.settings.providerIcons[provider.rawValue]).id == choice.id
                        Button {
                            store.settings.providerIcons[provider.rawValue] = choice.id
                        } label: {
                            HStack(spacing: 16) {
                                Image(choice.assetName).resizable().scaledToFit().frame(width: 36, height: 36)
                                Text(choice.label).foregroundStyle(UsageColors.textPrimary)
                                Spacer()
                                if selected { Image(systemName: "checkmark").foregroundStyle(UsageColors.terracotta) }
                            }.padding(.vertical, 6)
                        }.accessibilityLabel(choice.label).accessibilityValue(selected ? "Selected" : "Not selected")
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(UsageColors.background)
        .navigationTitle("Provider icons")
        .accessibilityIdentifier("provider-icons-list")
    }
}
