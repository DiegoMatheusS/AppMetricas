package br.com.medidor.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import br.com.medidor.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.medidor.app.data.MeasurementRecord

private val HomeBackground = Color(0xFFF7F8F5)
private val HomeInk = Color(0xFF17251F)
private val HomeMuted = Color(0xFF7B847D)
private val HomeLime = Color(0xFFC0F565)

@Composable
fun HomeScreen(
    onOpen: (Destination) -> Unit,
    records: List<MeasurementRecord> = emptyList(),
    proLocked: Boolean = false
) {
    Column(Modifier.fillMaxSize().background(HomeBackground)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(38.dp)
                    )
                    Text("  APP MÉTRICAS", fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                        color = HomeInk)
                }
                TextButton(onClick = { onOpen(Destination.MORE) }) {
                    Text("☷", fontSize = 22.sp, color = HomeInk)
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth().height(110.dp)
                    .clickable { onOpen(Destination.RULER) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HomeLime),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("♢", fontSize = 25.sp, color = HomeInk)
                        Text("Régua e área", fontSize = 20.sp,
                            fontWeight = FontWeight.Medium, color = HomeInk)
                    }
                    Text("◢", fontSize = 62.sp, color = HomeInk,
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeToolCard(
                    title = "Tanques",
                    pro = proLocked,
                    symbol = "▢", modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.TANK_CAMERA) }
                )
                HomeToolCard(
                    title = "Arquiteto",
                    pro = proLocked,
                    symbol = "◇", modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.ARCHITECT_SCAN) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeToolCard(
                    title = "Contar peças",
                    pro = proLocked,
                    symbol = "◎", modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.COUNT) }
                )
                HomeToolCard(
                    title = "Scanner 3D",
                    pro = proLocked,
                    symbol = "◈", modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.SCANNER) }
                )
            }
            if (records.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recentes", fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, color = HomeInk)
                TextButton(onClick = { onOpen(Destination.HISTORY) }) {
                    Text("Ver tudo", fontSize = 12.sp, color = HomeMuted)
                }
            }
                records.take(3).forEach { record ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(Destination.HISTORY) }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp)
                                .background(Color.White, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("□", color = HomeInk, fontSize = 21.sp)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = HomeInk, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium)
                            Text(record.createdAt, fontSize = 11.sp, color = HomeMuted)
                        }
                        Text(record.details, maxLines = 1,
                            modifier = Modifier.weight(0.9f), color = HomeInk,
                            fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.White)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HomeNavItem("⌂", "Início", active = true, onClick = {})
            HomeNavItem("▣", "Medições", active = false,
                onClick = { onOpen(Destination.HISTORY) })
            HomeNavItem("☷", "Ajustes", active = false,
                onClick = { onOpen(Destination.MORE) })
        }
    }
}

@Composable
private fun HomeToolCard(
    title: String, symbol: String,
    modifier: Modifier = Modifier,
    pro: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(110.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(symbol, fontSize = 22.sp, color = HomeInk)
                Text(if (pro) "PRO ↗" else "↗", fontSize = if (pro) 12.sp else 17.sp, color = HomeMuted)
            }
            Column {
                Text(title, color = HomeInk, fontWeight = FontWeight.Medium,
                    fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun HomeNavItem(
    symbol: String, label: String, active: Boolean, onClick: () -> Unit
) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(symbol, color = if (active) HomeInk else HomeMuted, fontSize = 22.sp)
        Text(label, color = if (active) HomeInk else HomeMuted, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun MoreToolsScreen(onOpen: (Destination) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(HomeBackground)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("Ferramentas e ajustes", style = MaterialTheme.typography.headlineSmall,
            color = HomeInk)
        listOf(
            Destination.RULER, Destination.AREA, Destination.TANK_CAMERA,
            Destination.TANK, Destination.BOX, Destination.ARCHITECT_SCAN, Destination.ARCHITECT,
            Destination.SCANNER, Destination.COUNT, Destination.HISTORY,
            Destination.PRO
        ).forEach { destination ->
            Card(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpen(destination) },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(destination.label, color = HomeInk)
                    Text("↗", color = HomeMuted)
                }
            }
        }
    }
}
