package com.usagelimits.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.ui.ProviderIconCatalog
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

@Composable
fun ProviderIconSettings(selected: Map<String, String>, onChoose: (ProviderId, String) -> Unit) {
    var editing by remember { mutableStateOf<ProviderId?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Provider icons")
        UsageCard {
            ProviderId.entries.forEach { provider ->
                val icon = ProviderIconCatalog.selected(provider, selected[provider.id])
                Row(Modifier.fillMaxWidth().clickable { editing = provider }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(icon.drawable), null, Modifier.size(30.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
                        Text(icon.label, style = MaterialTheme.typography.bodySmall, color = UsageColors.TextSecondary)
                    }
                    Text("Choose", color = UsageColors.Terracotta, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    editing?.let { provider ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("${provider.displayName} icon") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderIconCatalog.choices(provider).forEach { choice ->
                        val checked = choice.id == ProviderIconCatalog.selected(provider, selected[provider.id]).id
                        Row(Modifier.fillMaxWidth().selectable(checked, role = Role.RadioButton,
                            onClick = { onChoose(provider, choice.id); editing = null }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(choice.drawable), null, Modifier.size(36.dp))
                            Text(choice.label, Modifier.weight(1f).padding(horizontal = 12.dp))
                            RadioButton(checked, onClick = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } })
    }
}
