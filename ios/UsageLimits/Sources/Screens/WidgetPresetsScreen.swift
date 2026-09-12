import SwiftUI
import WidgetKit
import UsageLimitsKit

struct WidgetPresetsScreen: View {
    @EnvironmentObject private var store: UsageStore
    @State private var presets: [WidgetPreset] = []
    @State private var editing: WidgetPreset?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Text("Choose Custom when editing a widget, then select one of these layouts. Each layout keeps its own account selection and order.")
                    .foregroundStyle(UsageColors.textSecondary)
            }
            Section("Custom layouts") {
                ForEach(presets) { preset in
                    Button { editing = preset } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(preset.name).foregroundStyle(UsageColors.textPrimary)
                                Text("\(preset.accountIDs.count) accounts").font(.caption).foregroundStyle(UsageColors.textSecondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(UsageColors.textTertiary)
                        }
                    }
                }
                Button("New custom layout") {
                    editing = WidgetPreset(name: "Custom \(presets.count + 1)", accountIDs: [])
                }
            }
            if let error { Section { Text(error).foregroundStyle(UsageColors.red) } }
        }
        .scrollContentBackground(.hidden).background(UsageColors.background).navigationTitle("Widget layouts")
        .task {
            do { presets = try await WidgetPresetStore.shared.load() }
            catch { self.error = "Could not load your widget layouts." }
        }
        .sheet(item: $editing) { preset in
            WidgetPresetEditor(preset: preset, accounts: store.accounts) { saved in
                var updated = presets.filter { $0.id != saved.id }
                if let index = presets.firstIndex(where: { $0.id == saved.id }) { updated.insert(saved, at: min(index, updated.count)) }
                else { updated.append(saved) }
                try await WidgetPresetStore.shared.save(updated)
                presets = updated
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
    }
}

private struct WidgetPresetEditor: View {
    @Environment(\.dismiss) private var dismiss
    let preset: WidgetPreset
    let accounts: [AccountUsage]
    let onSave: ([WidgetPreset].Element) async throws -> Void
    @State private var name: String
    @State private var order: [String]
    @State private var selected: Set<String>
    @State private var error: String?
    @State private var saving = false
    @State private var editMode: EditMode = .active

    init(preset: WidgetPreset, accounts: [AccountUsage], onSave: @escaping (WidgetPreset) async throws -> Void) {
        self.preset = preset; self.accounts = accounts; self.onSave = onSave
        _name = State(initialValue: preset.name)
        _selected = State(initialValue: Set(preset.accountIDs))
        _order = State(initialValue: preset.accountIDs.filter { id in accounts.contains { $0.account.id == id } } +
            accounts.map { $0.account.id }.filter { !preset.accountIDs.contains($0) })
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Layout name") { TextField("Name", text: $name) }
                Section("Preview") {
                    HStack(spacing: 12) {
                        ForEach(order.filter { selected.contains($0) }.prefix(5), id: \.self) { id in
                            if let account = accounts.first(where: { $0.account.id == id }) {
                                ProviderBadge(provider: account.account.provider)
                            }
                        }
                        if selected.isEmpty { Text("Select accounts below").foregroundStyle(UsageColors.textSecondary) }
                    }
                }
                Section("Accounts · drag to reorder") {
                    ForEach(order, id: \.self) { id in
                        if let usage = accounts.first(where: { $0.account.id == id }) {
                            HStack(spacing: 12) {
                                Button {
                                    if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
                                } label: {
                                    Image(systemName: selected.contains(id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(selected.contains(id) ? UsageColors.terracotta : UsageColors.textTertiary)
                                }.buttonStyle(.plain).accessibilityLabel("Show \(usage.account.label)")
                                    .accessibilityValue(selected.contains(id) ? "Selected" : "Not selected")
                                ProviderBadge(provider: usage.account.provider)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(usage.account.label).foregroundStyle(UsageColors.textPrimary)
                                    Text(usage.account.provider.displayName).font(.caption).foregroundStyle(UsageColors.textSecondary)
                                }
                            }
                        }
                    }.onMove { offsets, destination in order.move(fromOffsets: offsets, toOffset: destination) }
                }
                if let error { Text(error).foregroundStyle(UsageColors.red) }
            }
            .environment(\.editMode, $editMode)
            .scrollContentBackground(.hidden).background(UsageColors.background).navigationTitle("Custom layout")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save") {
                        saving = true
                        Task {
                            do {
                                try await onSave(WidgetPreset(id: preset.id, name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                    accountIDs: order.filter { selected.contains($0) }))
                                dismiss()
                            } catch { self.error = "Could not save this layout. Try again."; saving = false }
                        }
                    }.disabled(saving || selected.isEmpty || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
