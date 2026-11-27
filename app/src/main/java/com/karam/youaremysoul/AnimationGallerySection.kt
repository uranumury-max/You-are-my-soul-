package com.karam.youaremysoul

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private data class SoulScene(val title: String, val symbol: String, val caption: String)
private val soulScenes = listOf(
SoulScene("قلبٌ ينبض","💗","نبضة صغيرة تضيء عالمكما"),
SoulScene("مطر النجوم","🌠","أمنيات تتساقط بهدوء"),
SoulScene("سحابة وردية","☁️","سحابة تحمل رسالة لطيفة"),
SoulScene("قمرنا","🌙","ضوء هادئ في ليلكما"),
SoulScene("مجرة القلب","💫","قلب يدور بين النجوم"),
SoulScene("زهرة الضوء","🌷","بتلات تتفتح مع اللمعان"),
SoulScene("فراشات الحلم","🦋","فراشات تعبر فضاءً ناعماً"),
SoulScene("رسالة طائرة","💌","رسالة تعبر سماء الليل"),
SoulScene("شهاب الأمنيات","☄️","لحظة تمنٍّ تحت النجوم"),
SoulScene("كوكب صغير","🪐","مدار خاص لا يشبه غيره"),
SoulScene("بحر من اللمعان","🌊","موجات ضوئية تتنفس"),
SoulScene("شجرة الحكاية","🌳","أغصان تنمو مع الأيام"),
SoulScene("نافذة المطر","🌧️","قطرات هادئة على نافذة"),
SoulScene("فوانيس الحب","🏮","فوانيس تضيء الطريق"),
SoulScene("رقصة الكواكب","✨","أجرام صغيرة في رقصة"),
SoulScene("بوابة النجوم","🌌","عبور إلى مشهد جديد"),
SoulScene("حديقة القمر","🌺","زهور تحت ضوء فضي"),
SoulScene("خيط القدر","🧵","خيط مضيء يصل بين نقطتين"),
SoulScene("شروقنا","🌅","بداية جديدة بألوان دافئة"),
SoulScene("مطر القلوب","💕","قلوب صغيرة تملأ السماء"),
SoulScene("عالمنا الأبدي","💖","مشهد ختامي يجمع رموز الرحلة")
)

@Composable
internal fun AnimationGallerySection(relationshipStartDate: String = "", names: List<String> = emptyList()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("soul_animation_gallery", Context.MODE_PRIVATE) }
    val today = LocalDate.now(ZoneOffset.UTC)
    val journeyStart = remember(relationshipStartDate) {
        runCatching { LocalDate.parse(relationshipStartDate) }.getOrNull()
            ?: run {
                val saved = prefs.getLong("started_at", 0L)
                if (saved > 0L) Instant.ofEpochMilli(saved).atZone(ZoneOffset.UTC).toLocalDate()
                else today.also { prefs.edit().putLong("started_at", System.currentTimeMillis()).apply() }
            }
    }
    val dayNumber = calculateUnlockedAnimationCount(journeyStart, today)
    val earned = dayNumber
    var unlocked by remember { mutableIntStateOf(maxOf(1, prefs.getInt("unlocked_count", 1), earned)) }
    LaunchedEffect(earned) {
        if (earned > unlocked) { unlocked = earned; prefs.edit().putInt("unlocked_count", unlocked).apply() }
    }
    var selected by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC080610), Color(0xAA241235), Color(0xCC100B1B)))).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("مجرة الأنيميشن", color = Color(0xFFFF9CCB), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(if (names.isNotEmpty()) "21 مشهداً لـ ${names.take(2).joinToString(" و")}" else "21 مشهداً صغيراً لعالمكما", color = Color(0xFFFFD68A), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Surface(color = Color(0xAA30203F), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FF9CCB)) ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✦", color = Color(0xFFFFD68A), fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("تعمل المشاهد دون اتصال", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("المفتوح يبقى محفوظاً على هذا الجهاز", color = Color.LightGray, fontSize = 10.sp)
                }
                Text("$unlocked / 21", color = Color(0xFFFF9CCB), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (selected >= 0) {
            SoulSceneViewer(scene = soulScenes[selected], number = selected + 1, onBack = { selected = -1 })
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(11) { rowIndex ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                        repeat(2) { col ->
                            val index = rowIndex * 2 + col
                            if (index < soulScenes.size) {
                                val scene = soulScenes[index]
                                val open = index < unlocked
                                Surface(modifier = Modifier.weight(1f).clickable(enabled = open) { selected = index }, color = Color(0xAA28172F), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (open) Color(0x77FF9CCB) else Color(0x334D4058))) {
                                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (open) scene.symbol else "🔒", fontSize = 30.sp)
                                        Text(scene.title, color = if (open) Color(0xFFFFD68A) else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                        Text(if (open) "المشهد ${index + 1}" else "يفتح في اليوم ${index + 1}", color = Color.LightGray, fontSize = 10.sp)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoulSceneViewer(scene: SoulScene, number: Int, onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "soul_scene_motion")
    val pulse by transition.animateFloat(
        initialValue = 0.78f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse),
        label = "scene_pulse"
    )
    val drift by transition.animateFloat(
        initialValue = -0.06f, targetValue = 0.06f,
        animationSpec = infiniteRepeatable(tween(5200), repeatMode = RepeatMode.Reverse),
        label = "scene_drift"
    )
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onBack) { Text("← العودة إلى المشاهد", color = Color(0xFFFF9CCB)) }
        Canvas(Modifier.fillMaxWidth().height(235.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = minOf(size.width, size.height)
            // Each unlocked scene has its own animated visual motif, not a shared placeholder.
            when (number) {
                1, 20, 21 -> {
                    val heart = androidx.compose.ui.graphics.Path().apply {
                        moveTo(center.x, center.y + base * 0.25f * pulse)
                        cubicTo(center.x - base * 0.48f, center.y - base * 0.08f, center.x - base * 0.30f, center.y - base * 0.43f, center.x, center.y - base * 0.16f)
                        cubicTo(center.x + base * 0.30f, center.y - base * 0.43f, center.x + base * 0.48f, center.y - base * 0.08f, center.x, center.y + base * 0.25f * pulse)
                        close()
                    }
                    drawPath(heart, Color(0x99FF75B8))
                    drawPath(heart, Color(0xFFFFD68A), style = Stroke(width = 2.2.dp.toPx()))
                    drawCircle(Color(0x55FF9CCB), base * 0.38f * pulse, center)
                }
                2, 9, 16 -> {
                    repeat(7) { i ->
                        val x = size.width * (0.12f + i * 0.13f) + base * drift * (i % 2)
                        val y = size.height * ((i * 31 % 83) / 100f) + (pulse - 0.9f) * base
                        drawLine(Color(0xFFFFD68A), Offset(x, y), Offset(x - base * 0.10f, y + base * 0.17f), strokeWidth = (1.2f + i % 3).dp.toPx())
                        drawCircle(Color(0xFFFFF0C8), base * 0.018f, Offset(x, y))
                    }
                }
                3 -> {
                    // Soft cloud layers drift at different speeds.
                    repeat(4) { i ->
                        val x = center.x + (i - 1.5f) * base * 0.13f + base * drift * (if (i % 2 == 0) 1f else -1f)
                        val y = center.y + base * 0.035f * (i % 2)
                        drawCircle(Color(0x88FFFFFF), base * (0.12f + (i % 2) * 0.035f) * pulse, Offset(x, y))
                    }
                    drawCircle(Color(0x66FF9CCB), base * 0.28f, center, style = Stroke(width = 2.dp.toPx()))
                }
                13 -> {
                    // Rain on a luminous window: droplets descend in separate lanes.
                    drawRoundRect(Color(0x223E8BC4), Offset(center.x - base * 0.30f, center.y - base * 0.34f), androidx.compose.ui.geometry.Size(base * 0.60f, base * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(base * 0.035f), style = Stroke(width = 2.dp.toPx()))
                    repeat(11) { i ->
                        val x = center.x - base * 0.25f + (i % 6) * base * 0.10f
                        val phase = (i * 0.13f + (pulse - 0.78f) * 0.65f + 1f) % 1f
                        val y = center.y - base * 0.28f + phase * base * 0.55f
                        drawLine(Color(0xFF9EDCFF), Offset(x, y), Offset(x - base * 0.018f, y + base * 0.055f), strokeWidth = 1.6.dp.toPx())
                    }
                    drawCircle(Color(0x66A8DFFF), base * 0.38f, center, style = Stroke(width = 1.dp.toPx()))
                }
                4 -> {
                    drawCircle(Color(0xFFE8D9FF), base * 0.22f * pulse, center)
                    drawCircle(Color(0xFF241235), base * 0.19f, Offset(center.x + base * 0.11f, center.y - base * 0.08f))
                    repeat(12) { i -> drawCircle(Color(0xFFFFD68A), base * 0.012f, Offset(center.x + kotlin.math.cos(i * 0.52f) * base * 0.38f, center.y + kotlin.math.sin(i * 0.52f) * base * 0.38f)) }
                }
                5 -> {
                    // Heart constellation: three small lights orbit a steady center.
                    drawCircle(Color(0x88FF9CCB), base * 0.20f * pulse, center)
                    drawCircle(Color(0xFFFF75B8), base * 0.12f, center)
                    repeat(3) { i ->
                        val angle = pulse * 2.1f + i * (Math.PI * 2.0 / 3.0)
                        val orbit = Offset(
                            center.x + kotlin.math.cos(angle).toFloat() * base * 0.30f,
                            center.y + kotlin.math.sin(angle).toFloat() * base * 0.22f
                        )
                        drawCircle(Color(0x66FFD68A), base * 0.075f, orbit)
                        drawCircle(Color(0xFFFFD68A), base * 0.025f, orbit)
                    }
                    drawCircle(Color(0x66FF9CCB), base * 0.39f, center, style = Stroke(width = 1.5.dp.toPx()))
                }
                6 -> {
                    repeat(8) { i ->
                        val angle = i * (Math.PI / 4.0)
                        val p = Offset(center.x + kotlin.math.cos(angle).toFloat() * base * 0.19f * pulse, center.y + kotlin.math.sin(angle).toFloat() * base * 0.19f * pulse)
                        drawCircle(if (i % 2 == 0) Color(0xFFFF9CCB) else Color(0xFFFFD68A), base * 0.09f, p)
                    }
                    drawCircle(Color.White, base * 0.035f, center)
                }
                7 -> {
                    drawLine(Color(0xFFFF9CCB), Offset(center.x - base * 0.3f, center.y), Offset(center.x + base * 0.3f, center.y), strokeWidth = 3.dp.toPx())
                    drawLine(Color(0xFFFFD68A), Offset(center.x, center.y - base * 0.3f), Offset(center.x, center.y + base * 0.3f), strokeWidth = 3.dp.toPx())
                    repeat(4) { i -> drawCircle(Color(0xFFFFD68A), base * 0.045f, Offset(center.x + (i - 1.5f) * base * 0.15f, center.y + kotlin.math.sin(i + pulse) * base * 0.08f)) }
                }
                8 -> {
                    drawRoundRect(Color(0xAAFFF0D0), Offset(center.x - base * 0.22f, center.y - base * 0.15f), androidx.compose.ui.geometry.Size(base * 0.44f, base * 0.30f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(base * 0.035f))
                    drawLine(Color(0xFFFF9CCB), Offset(center.x - base * 0.22f, center.y - base * 0.15f), Offset(center.x, center.y + base * 0.02f), strokeWidth = 2.dp.toPx())
                    drawLine(Color(0xFFFF9CCB), Offset(center.x + base * 0.22f, center.y - base * 0.15f), Offset(center.x, center.y + base * 0.02f), strokeWidth = 2.dp.toPx())
                }
                14 -> {
                    // Lanterns: glowing hanging lights at different heights.
                    repeat(5) { i ->
                        val x = center.x + (i - 2) * base * 0.16f
                        val y = center.y + kotlin.math.sin(i * 0.8f + drift * 8f) * base * 0.08f
                        drawLine(Color(0x88FFD68A), Offset(x, y - base * 0.2f), Offset(x, y - base * 0.08f), strokeWidth = 1.5.dp.toPx())
                        drawRoundRect(Color(0xFFFFB86B).copy(alpha = 0.75f), Offset(x - base * 0.045f, y - base * 0.08f), androidx.compose.ui.geometry.Size(base * 0.09f, base * 0.13f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(base * 0.025f))
                        drawCircle(Color(0xFFFFF0B0).copy(alpha = 0.65f), base * 0.09f * pulse, Offset(x, y - base * 0.015f))
                    }
                }
                17 -> {
                    // Moon garden: silver moon above a small ring of flowers.
                    drawCircle(Color(0xFFE8D9FF), base * 0.16f * pulse, Offset(center.x, center.y - base * 0.19f))
                    drawCircle(Color(0xFF241235), base * 0.14f, Offset(center.x + base * 0.08f, center.y - base * 0.24f))
                    repeat(5) { i ->
                        val angle = Math.PI + i * (Math.PI / 4.0)
                        val flower = Offset(center.x + kotlin.math.cos(angle).toFloat() * base * 0.23f, center.y + kotlin.math.sin(angle).toFloat() * base * 0.18f)
                        repeat(5) { petal ->
                            val a = petal * (Math.PI * 2.0 / 5.0)
                            drawCircle(Color(0xFFFF9CCB), base * 0.035f, Offset(flower.x + kotlin.math.cos(a).toFloat() * base * 0.045f, flower.y + kotlin.math.sin(a).toFloat() * base * 0.045f))
                        }
                        drawCircle(Color(0xFFFFD68A), base * 0.018f, flower)
                    }
                }
                18 -> {
                    // A luminous thread connecting two drifting stars.
                    val left = Offset(center.x - base * 0.28f, center.y + base * drift)
                    val right = Offset(center.x + base * 0.28f, center.y - base * drift)
                    drawLine(Color(0xFFFF9CCB), left, right, strokeWidth = 2.4.dp.toPx())
                    drawCircle(Color(0xFFFFD68A), base * 0.075f * pulse, left)
                    drawCircle(Color(0xFFE8D9FF), base * 0.075f * pulse, right)
                    drawCircle(Color(0x66FFFFD6), base * 0.16f, center, style = Stroke(width = 1.5.dp.toPx()))
                }
                10 -> {
                    // A small planet with a tilted ring and a moving moon.
                    drawCircle(Color(0xFF8B75C8), base * 0.19f * pulse, center)
                    drawOval(Color(0xFFFFD68A), Offset(center.x - base * 0.34f, center.y - base * 0.10f), androidx.compose.ui.geometry.Size(base * 0.68f, base * 0.22f), style = Stroke(width = 2.2.dp.toPx()))
                    val moon = Offset(center.x + kotlin.math.cos(pulse * 2.5f) * base * 0.34f, center.y + kotlin.math.sin(pulse * 2.5f) * base * 0.13f)
                    drawCircle(Color(0xFFE8D9FF), base * 0.055f, moon)
                    drawCircle(Color(0x66FF9CCB), base * 0.27f, center, style = Stroke(width = 1.dp.toPx()))
                }
                15 -> {
                    // Three planets follow separate elliptical paths around a bright star.
                    repeat(3) { i ->
                        val angle = pulse * 2.0f + i * (Math.PI * 2.0 / 3.0)
                        val x = center.x + kotlin.math.cos(angle).toFloat() * base * (0.20f + i * 0.09f)
                        val y = center.y + kotlin.math.sin(angle).toFloat() * base * (0.12f + i * 0.055f)
                        drawOval(Color(0x66FFD68A), Offset(center.x - base * (0.20f + i * 0.09f), center.y - base * (0.12f + i * 0.055f)), androidx.compose.ui.geometry.Size(base * (0.40f + i * 0.18f), base * (0.24f + i * 0.11f)), style = Stroke(width = 1.3.dp.toPx()))
                        drawCircle(if (i == 1) Color(0xFFFF9CCB) else Color(0xFFE8D9FF), base * (0.045f + i * 0.012f), Offset(x, y))
                    }
                    drawCircle(Color(0xFFFFD68A), base * 0.075f * pulse, center)
                    drawCircle(Color(0xFFFFF0C8), base * 0.025f, center)
                }
                11 -> {
                    repeat(6) { i -> drawLine(Color(0xFF7FD9C4), Offset(center.x, center.y + base * 0.24f), Offset(center.x + (i - 2.5f) * base * 0.12f, center.y - base * (0.12f + (i % 3) * 0.11f) * pulse), strokeWidth = 3.dp.toPx()) }
                    drawCircle(Color(0xFFFFD68A), base * 0.06f, center)
                }
                12 -> {
                    repeat(24) { i ->
                        val x = ((i * 41) % 97) / 100f * size.width
                        val y = ((i * 23) % 91) / 100f * size.height
                        drawLine(Color(0x887FD9FF), Offset(x, y), Offset(x - base * 0.015f, y + base * 0.08f), strokeWidth = 1.5.dp.toPx())
                    }
                }
                19 -> {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFB36B), Color(0xFFFFE6A8), Color(0xFF5D477E))), Offset.Zero, androidx.compose.ui.geometry.Size(size.width, size.height))
                    drawCircle(Color(0xFFFFF0B0), base * 0.16f * pulse, Offset(center.x, center.y + base * 0.1f))
                    drawLine(Color(0xFF8A6A9F), Offset(0f, center.y + base * 0.3f), Offset(size.width, center.y + base * 0.3f), strokeWidth = 2.dp.toPx())
                }
                else -> {
                    drawCircle(Color(0x44FF9CCB), base * 0.31f * pulse, center)
                    drawCircle(Color(0x33FFD68A), base * 0.22f * pulse, center)
                    drawCircle(Color(0x88FFD68A), base * 0.31f, center, style = Stroke(width = 1.5.dp.toPx()))
                    drawCircle(Color(0x66FF9CCB), base * 0.42f, center, style = Stroke(width = 1.dp.toPx()))
                }
            }
            for (i in 0 until 42) {
                val x = ((i * 73 + number * 29) % 997) / 997f * size.width
                val y = ((i * 137 + number * 61) % 991) / 991f * size.height
                val radius = (1.1f + (i % 4) * 0.55f) * pulse
                drawCircle(if (i % 5 == 0) Color(0xFFFFD68A) else Color(0xCCFFFFFF), radius, Offset(x, y))
            }
            val orbitX = center.x + base * drift
            drawLine(Color(0x77FFD68A), Offset(center.x - base * 0.38f, center.y + base * 0.08f), Offset(center.x + base * 0.38f, center.y - base * 0.08f), strokeWidth = 1.dp.toPx())
            drawCircle(Color(0xFFFFD68A), radius = base * 0.035f, center = Offset(orbitX + base * 0.31f, center.y - base * 0.13f))
            drawCircle(Color(0xFFFF9CCB), radius = base * 0.025f, center = Offset(center.x - base * 0.29f, center.y + base * 0.15f))
        }
        Text(scene.symbol, fontSize = (56 * pulse).sp)
        Spacer(Modifier.height(8.dp))
        Text(scene.title, color = Color(0xFFFFD68A), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(scene.caption, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("المشهد $number من 21", color = Color(0xFFFF9CCB), fontSize = 12.sp)
    }
}
