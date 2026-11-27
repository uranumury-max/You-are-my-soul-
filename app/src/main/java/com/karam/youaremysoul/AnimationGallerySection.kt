package com.karam.youaremysoul

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration

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
internal fun AnimationGallerySection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("soul_animation_gallery", Context.MODE_PRIVATE) }
    val startedAt = remember {
        val saved = prefs.getLong("started_at", 0L)
        if (saved > 0L) saved else System.currentTimeMillis().also { prefs.edit().putLong("started_at", it).apply() }
    }
    val dayNumber = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / Duration.ofDays(1).toMillis()).toInt() + 1
    val earned = minOf(21, dayNumber)
    var unlocked by remember { mutableIntStateOf(maxOf(1, prefs.getInt("unlocked_count", 1), earned)) }
    LaunchedEffect(earned) {
        if (earned > unlocked) { unlocked = earned; prefs.edit().putInt("unlocked_count", unlocked).apply() }
    }
    var selected by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF100B1B), Color(0xFF241235), Color(0xFF100B1B)))).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("مجرة الأنيميشن", color = Color(0xFFFF9CCB), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("21 مشهداً صغيراً لعالمكما", color = Color(0xFFFFD68A), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Surface(color = Color(0xFF30203F), shape = RoundedCornerShape(18.dp)) {
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
                                Surface(modifier = Modifier.weight(1f).clickable(enabled = open) { selected = index }, color = Color(0xCC28172F), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (open) Color(0x66FFFFD6) else Color(0x334D4058))) {
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
    val pulse by transition.animateFloat(initialValue = 0.82f, targetValue = 1.16f, animationSpec = infiniteRepeatable(tween(1300), repeatMode = RepeatMode.Reverse), label = "scene_pulse")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onBack) { Text("← العودة إلى المشاهد", color = Color(0xFFFF9CCB)) }
        Spacer(Modifier.height(28.dp))
        Text(scene.symbol, fontSize = (76 * pulse).sp)
        Spacer(Modifier.height(20.dp))
        Text(scene.title, color = Color(0xFFFFD68A), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(scene.caption, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("المشهد $number من 21", color = Color(0xFFFF9CCB), fontSize = 12.sp)
    }
}
