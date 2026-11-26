package com.karam.youaremysoul

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

private val Night = Color(0xFF100B1B)
private val Rose = Color(0xFFFF9CCB)
private val Gold = Color(0xFFFFD68A)
private val CardTone = Color(0xCC28172F)

@Composable
fun SoulWorldApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalWorldStore(context) }
    var world by remember { mutableStateOf(store.load()) }
    var tab by remember { mutableStateOf("العالم") }
    val tabs = listOf("العالم", "الربط", "الذكريات", "رحلتنا", "الإعدادات")
    var worldName by remember { mutableStateOf("عالمنا الخاص") }
    var memoryText by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var enteredCode by remember { mutableStateOf("") }
    var requestStatus by remember { mutableStateOf("") }

    fun persist(next: WorldSnapshot) {
        world = next
        store.save(next)
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night, Color(0xFF28172F))))) {
        StarField()
        Column(Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 12.dp)) {
            Text("عالمنا الخاص", color = Rose, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("You Are My Soul  ✦", color = Gold, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), containerColor = Color.Transparent, contentColor = Rose) {
                tabs.forEach { item -> Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item) }) }
            }
            Spacer(Modifier.height(10.dp))
            when (tab) {
                "الربط" -> ConnectionSection(
                    displayName = displayName, onName = { displayName = it },
                    enteredCode = enteredCode, onCode = { enteredCode = it },
                    requestStatus = requestStatus, onRequest = {
                        requestStatus = if (enteredCode.length == 9 && enteredCode != DeviceIdentity(context).code)
                            "حُفظت تفاصيل طلبك على هذا الجهاز فقط. الإرسال والموافقة بين هاتفين يحتاجان طبقة اتصال لم تُفعّل بعد."
                        else "تحقّق من الرمز؛ يجب أن يكون 9 أرقام وألا يكون رمز جهازك."
                    },
                    onCreate = {
                        val code = DeviceIdentity(context).code
                        val member = WorldMember(code, displayName.trim().ifBlank { "صاحب العالم" }, true)
                        persist(WorldSnapshot(worldName.trim().ifBlank { "عالمنا الخاص" }, code, listOf(member), 0, emptyList()))
                        requestStatus = "تم إنشاء عالم وحفظه على هذا الهاتف. هذا الجهاز هو المضيف المحلي."
                    }
                )
                "الذكريات" -> MemoriesSection(
                    memories = world?.memories.orEmpty(), text = memoryText, onText = { memoryText = it },
                    onAdd = {
                        val value = memoryText.trim()
                        if (value.isNotEmpty()) {
                            val current = world ?: WorldSnapshot("عالمنا الخاص", DeviceIdentity(context).code,
                                listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)), 0, emptyList())
                            persist(current.copy(memories = current.memories + value))
                            memoryText = ""
                        }
                    },
                    onDelete = { index ->
                        world?.let { current -> persist(current.copy(memories = current.memories.filterIndexed { i, _ -> i != index })) }
                    }
                )
                "رحلتنا" -> JourneySection(stage = world?.journeyStage ?: 0, onUnlock = { stage ->
                    world?.let { persist(it.copy(journeyStage = stage)) }
                })
                "الإعدادات" -> SettingsSection(world = world, onName = { value ->
                    world?.let { persist(it.copy(worldName = value)) }
                })
                else -> WorldHome(world = world, onCreate = {
                    val code = DeviceIdentity(context).code
                    persist(WorldSnapshot("عالمنا الخاص", code, listOf(WorldMember(code, "صاحب العالم", true)), 0, emptyList()))
                    tab = "الربط"
                })
            }
            Spacer(Modifier.height(8.dp))
            DeveloperCredit()
        }
    }
}

@Composable
private fun DeveloperCredit() {
    Text(WorldLimits.DEVELOPER_CREDIT, color = Gold.copy(alpha = .78f), fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun StarField() {
    Canvas(Modifier.fillMaxSize()) {
        val random = Random(41)
        repeat(105) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            drawCircle(if (it % 7 == 0) Gold.copy(alpha = .65f) else Color.White.copy(alpha = .35f),
                radius = if (it % 9 == 0) 2.5f else 1.1f, center = Offset(x, y))
        }
    }
}

@Composable
private fun WorldHome(world: WorldSnapshot?, onCreate: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Brush.radialGradient(listOf(Rose.copy(alpha = .28f), Color.Transparent)), radius = size.minDimension * .48f)
                drawCircle(Rose.copy(alpha = .36f), radius = size.minDimension * .38f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                drawCircle(Gold.copy(alpha = .5f), radius = size.minDimension * .25f, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawCircle(Rose, radius = 8f)
            }
            Text("✦", color = Gold, fontSize = 42.sp)
        }
        Text(world?.worldName ?: "عالم يتكوّن منكم", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(if (world == null) "ابدأ بإنشاء عالم محفوظ على هذا الهاتف" else "عالم محفوظ محلياً • ${world.members.size} مشارك",
            color = Rose.copy(alpha = .9f), fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (world == null) "بداية عالمكم" else "مركز عالمكم", color = Gold, fontWeight = FontWeight.Bold)
                Text(if (world == null) "إنشاء العالم يثبت هذا الهاتف كمضيف محلي ويحفظ بياناته." else "المضيف: ${world.hostCode}\nالذكريات المحفوظة: ${world.memories.size}\nمرحلة الرحلة: ${world.journeyStage}",
                    color = Color.White, modifier = Modifier.padding(top = 6.dp))
                if (world == null) Button(onClick = onCreate, modifier = Modifier.padding(top = 8.dp)) { Text("إنشاء عالمي") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("المزامنة بين الأجهزة لم تُفعّل بعد؛ لن أصف البيانات المحلية بأنها مشتركة.", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun ConnectionSection(
    displayName: String, onName: (String) -> Unit, enteredCode: String, onCode: (String) -> Unit,
    requestStatus: String, onRequest: () -> Unit, onCreate: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val code = remember { DeviceIdentity(context).code }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("هوية هذا الموبايل", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = CardTone)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("رمزك الثابت (9 أرقام)", color = Rose)
                Text(code, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("رمز تطبيق مستقل، ليس رقم هاتف، ولا يتغير دورياً.", color = Color.LightGray, fontSize = 12.sp)
                Button(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("رمز عالمنا", code))
                }) { Text("نسخ الرمز") }
            }
        }
        OutlinedTextField(value = displayName, onValueChange = { onName(it.take(24)) }, label = { Text("اسمك داخل العالم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = enteredCode, onValueChange = { onCode(it.filter(Char::isDigit).take(9)) }, label = { Text("رمز المضيف أو المشارك") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onRequest, enabled = enteredCode.length == 9 && enteredCode != code) { Text("تجهيز طلب ربط") }
        if (requestStatus.isNotBlank()) Text(requestStatus, color = Gold, fontSize = 12.sp)
        Divider(color = Rose.copy(alpha = .25f))
        Text("العالم يدعم تصميمياً حتى ${WorldLimits.MAX_MEMBERS} مشاركين. حالياً إنشاء العالم وحفظه محليان؛ الطلب الشبكي وقبول المضيف يتطلبان خدمة اتصال فعلية.", color = Color.LightGray, fontSize = 12.sp)
        Button(onClick = onCreate) { Text("إنشاء عالم على هذا الهاتف") }
    }
}

@Composable
private fun MemoriesSection(memories: List<String>, text: String, onText: (String) -> Unit, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("ذكرياتنا", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("تُحفظ الذكريات على هذا الجهاز حتى إغلاق التطبيق.", color = Rose, fontSize = 12.sp)
        OutlinedTextField(value = text, onValueChange = { onText(it.take(500)) }, label = { Text("اكتب ذكرى أو رسالة قصيرة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = onAdd, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text("حفظ الذكرى") }
        if (memories.isEmpty()) Text("لم تُحفظ ذكريات بعد.", color = Color.LightGray)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories.indices.toList()) { index ->
                Card(colors = CardDefaults.cardColors(containerColor = CardTone)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(memories[index], color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDelete(index) }) { Text("حذف", color = Rose) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneySection(stage: Int, onUnlock: (Int) -> Unit) {
    val milestones = WorldLimits.journeyMilestones
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("رحلتنا معاً", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("مراحل الرحلة محفوظة محلياً. لا تُفتح تلقائياً قبل ربطها بتاريخ بداية موثوق.", color = Rose, fontSize = 12.sp)
        milestones.forEachIndexed { index, day ->
            val unlocked = index <= stage
            Card(colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("اليوم $day • ${listOf("الشرارة الأولى", "مدارنا", "نجمة مشتركة", "مجرة الذكريات", "عالم لا ينتهي")[index]}", color = if (unlocked) Gold else Rose, fontWeight = FontWeight.Bold)
                        Text(if (unlocked) "مرحلة مسجّلة" else "مرحلة مقفلة", color = Color.LightGray, fontSize = 12.sp)
                    }
                    if (!unlocked) TextButton(onClick = { onUnlock(index) }) { Text("تجربة فتح", color = Rose) }
                }
            }
        }
        Text("تنبيه: زر التجربة يغيّر التقدم المحفوظ يدوياً، وليس إثباتاً لمرور الأيام.", color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsSection(world: WorldSnapshot?, onName: (String) -> Unit) {
    var name by remember(world?.worldName) { mutableStateOf(world?.worldName ?: "عالمنا الخاص") }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("إعدادات العالم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("اسم العالم") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onName(name.trim().ifBlank { "عالمنا الخاص" }) }, enabled = world != null) { Text("حفظ الاسم") }
        Text("بيانات هذا الإصدار تحفظ محلياً. النسخ الاحتياطي والمزامنة وحماية بيانات المشاركين ستُضاف قبل اعتبار التطبيق مكتملاً.", color = Color.LightGray, fontSize = 12.sp)
    }
}
