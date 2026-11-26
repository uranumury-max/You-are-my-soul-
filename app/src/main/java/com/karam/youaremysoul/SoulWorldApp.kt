package com.karam.youaremysoul

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

private val Night = Color(0xFF100B1B)
private val Rose = Color(0xFFFF9CCB)
private val Gold = Color(0xFFFFD68A)

@Composable
fun SoulWorldApp() {
    var tab by remember { mutableStateOf("العالم") }
    val tabs = listOf("العالم", "الربط", "الذكريات", "رحلتنا", "الإعدادات")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night, Color(0xFF28172F))))) {
        StarField()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("عالمنا الخاص", color = Rose, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("You Are My Soul  ✦", color = Gold, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), containerColor = Color.Transparent, contentColor = Rose) {
                tabs.forEach { item -> Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item) }) }
            }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                "الربط" -> ConnectionSection()
                "الذكريات" -> SimpleSection("ذكرياتنا", "مساحة لحفظ اللحظات والرسائل والصور المشتركة.")
                "رحلتنا" -> SimpleSection("رحلتنا معاً", "مراحل متدرجة ومشاهد متحركة تُفتح مع تقدم العالم.")
                "الإعدادات" -> SimpleSection("إعدادات العالم", "الصوت، الاهتزاز، الخصوصية، وإدارة المشاركين.")
                else -> WorldHome()
            }
            Spacer(Modifier.weight(1f))
            Text("برمجة وتطوير: \"كرم أكرم محمد الشيخ\"", color = Gold.copy(alpha = .72f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun StarField() {
    Canvas(Modifier.fillMaxSize()) {
        val random = Random(41)
        repeat(90) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            drawCircle(if (it % 7 == 0) Gold.copy(alpha = .65f) else Color.White.copy(alpha = .35f), radius = if (it % 9 == 0) 2.5f else 1.1f, center = Offset(x, y))
        }
    }
}

@Composable
private fun WorldHome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Brush.radialGradient(listOf(Rose.copy(alpha = .23f), Color.Transparent)), radius = size.minDimension * .48f)
                drawCircle(Rose.copy(alpha = .32f), radius = size.minDimension * .38f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                drawCircle(Gold.copy(alpha = .45f), radius = size.minDimension * .25f, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawCircle(Rose, radius = 8f)
            }
            Text("✦", color = Gold, fontSize = 42.sp)
        }
        Text("عالم يتكوّن منكم", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text("مساحة مشتركة، ذكريات، ومجرّة تتطور مع الوقت", color = Rose.copy(alpha = .85f), fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xAA28172F)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("بداية عالمكم", color = Gold, fontWeight = FontWeight.Bold)
                Text("أنشئ العالم من تبويب الربط، أو أدخل رمز مشارك للانضمام.", color = Color.White, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ConnectionSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val code = remember { DeviceIdentity(context).code }
    var displayName by remember { mutableStateOf("") }
    var enteredCode by remember { mutableStateOf("") }
    var request by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("هوية هذا الموبايل", color = Gold, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xAA28172F))) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("رمزك الثابت (9 أرقام)", color = Rose)
                Text(code, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("هذا رمز تطبيق، وليس رقم هاتف. انسخه وشاركه.", color = Color.LightGray, fontSize = 12.sp)
                Button(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("رمز عالمنا", code))
                }) { Text("نسخ الرمز") }
            }
        }
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(24) }, label = { Text("اسمك داخل العالم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = enteredCode, onValueChange = { enteredCode = it.filter(Char::isDigit).take(9) }, label = { Text("رمز الشخص الذي تريد الانضمام إليه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { request = enteredCode.length == 9 && enteredCode != code }, enabled = enteredCode.length == 9 && enteredCode != code) { Text("إرسال طلب ربط") }
        if (request) Text("تم تجهيز طلب الربط محلياً. يحتاج إرسال الطلب وقبوله إلى اتصال بالخادم.", color = Gold)
        Text("العالم مصمم ليستوعب حتى 5 مشاركين. الربط الشبكي الفعلي سيُستكمل ضمن طبقة الخادم.", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun SimpleSection(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Color.White)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xAA28172F))) {
            Text("سيتم بناء هذه التجربة كجزء من عالم متزامن، مع حفظ دائم وتفاعل بصري.", color = Rose, modifier = Modifier.padding(16.dp))
        }
    }
}
