package com.karam.youaremysoul

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private val Night = Color(0xFF100B1B)
private val Rose = Color(0xFFFF9CCB)
private val Gold = Color(0xFFFFD68A)
private val CardTone = Color(0xCC28172F)

@Composable
fun SoulWorldApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalWorldStore(context) }
    val authClient = remember { SupabaseAuthClient(context) }
    val deviceRegistrar = remember { SupabaseDeviceRegistrar(context) }
    val worldRepository = remember { SupabaseWorldRepository() }
    val pairingRepository = remember { SupabasePairingRepository() }
    val worldKeyExchangeRepository = remember { SupabaseWorldKeyExchangeRepository(context) }
    val snapshotBootstrapper = remember { WorldSnapshotBootstrapper(context) }
    val deviceKeyVault = remember { DeviceKeyVault() }
    val worldKeyVault = remember { WorldKeyVault(context) }
    val serverClock = remember { SupabaseServerClock() }
    val syncCoordinator = remember { WorldSyncCoordinator(context) }
    val mutationSync = remember { WorldMutationSync(RoomSyncStore(context), worldKeyVault) }
    val syncMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val cloudBootstrapMutex = remember { Mutex() }
    var pendingJoinRequests by remember { mutableStateOf(emptyList<PendingWorldJoinRequest>()) }
    var pendingRequestsLoadFailed by remember { mutableStateOf(false) }
    var cloudStatus by remember { mutableStateOf("") }
    var cloudAuthenticated by remember { mutableStateOf(false) }
    var networkRevision by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                networkRevision += 1
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
    suspend fun bootstrapCloud() {
        cloudBootstrapMutex.withLock {
            if (!SupabaseRuntimeConfig.fromBuildConfig().isConfigured) {
                cloudAuthenticated = false
                cloudStatus = "المزامنة السحابية بانتظار إعداد الخدمة."
                return@withLock
            }
            cloudStatus = "جارٍ تأمين هوية الاتصال..."
            try {
                val session = authClient.ensureAuthenticatedSession()
                deviceRegistrar.register(session.accessToken)
                cloudAuthenticated = true
                cloudStatus = "هوية الاتصال والجهاز مسجلتان بأمان."
            } catch (error: SupabaseAuthException) {
                cloudAuthenticated = false
                cloudStatus = when (error.safeCode) {
                    "auth_rate_limited", "device_registration_rate_limited" -> "الخدمة مزدحمة مؤقتاً؛ لم يكتمل تسجيل الاتصال."
                    "auth_network_unavailable", "auth_service_unavailable", "device_registration_network_unavailable", "device_registration_unavailable" -> "تعذر الاتصال مؤقتاً؛ بياناتكم المحلية آمنة."
                    "service_role_key_forbidden" -> "إعداد المفتاح غير آمن؛ أوقفنا الاتصال."
                    "device_registration_rejected", "device_registration_not_confirmed" -> "تعذر تسجيل هوية هذا الجهاز؛ لن نبدأ الربط قبل تأكيدها."
                    "device_code_collision" -> "تعذر حجز رمز فريد بعد عدة محاولات؛ تم توليد رمز جديد للمحاولة التالية."
                    else -> "تعذر تأمين الاتصال (${error.safeCode})."
                }
            } catch (_: Exception) {
                cloudAuthenticated = false
                cloudStatus = "تعذر استعادة جلسة الاتصال؛ لم ننشئ هوية بديلة."
            }
        }
    }
    LaunchedEffect(authClient, deviceRegistrar) {
        bootstrapCloud()
    }
    LaunchedEffect(networkRevision) {
        if (networkRevision > 0) scope.launch { bootstrapCloud() }
    }
    var world by remember { mutableStateOf<WorldSnapshot?>(null) }
    var lastPersistedWorld by remember { mutableStateOf<WorldSnapshot?>(null) }
    var savedCloudWorldId by remember { mutableStateOf("") }
    var savedWorldName by remember { mutableStateOf("") }
    var worldKeyReady by remember { mutableStateOf(false) }
    var worldLoaded by remember { mutableStateOf(false) }
    var storageWarning by remember { mutableStateOf("") }
    val saveMutex = remember { Mutex() }
    val saveRevision = remember { AtomicLong(0L) }
    LaunchedEffect(store) {
        try {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            val resolved = if (loaded != null && loaded.cloudWorldId.isBlank()) {
                loaded.copy(cloudWorldId = java.util.UUID.randomUUID().toString()).also { migrated ->
                    withContext(Dispatchers.IO) { store.save(migrated) }
                }
            } else {
                loaded
            }
            world = resolved
            lastPersistedWorld = resolved
            savedCloudWorldId = resolved?.cloudWorldId.orEmpty()
            savedWorldName = resolved?.worldName.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            storageWarning = "تعذر فتح بيانات العالم بأمان. أوقفنا إنشاء عالم جديد حتى لا تُستبدل بيانات قديمة."
        } finally {
            worldLoaded = true
        }
    }
    var tab by remember { mutableStateOf("العالم") }
    val tabs = listOf("العالم", "الربط", "الذكريات", "رسائلنا", "لحظاتنا", "الإعدادات")
    val symbols = listOf("✧", "⌁", "♡", "✉", "✦", "⚙")
    var worldName by remember { mutableStateOf("عالمنا الخاص") }
    var memoryText by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var enteredCode by remember { mutableStateOf("") }
    var requestStatus by remember { mutableStateOf("") }
    var ownJoinRequestId by remember { mutableStateOf("") }

    LaunchedEffect(tab, world?.cloudWorldId, world?.localIsOwner, cloudAuthenticated, networkRevision) {
        val current = world
        if (tab == "الربط" && cloudAuthenticated && current != null &&
            current.cloudWorldId.isNotBlank() && current.localIsOwner
        ) {
            try {
                val session = authClient.ensureAuthenticatedSession()
                pendingJoinRequests = pairingRepository.pendingRequests(session, current.cloudWorldId)
                pendingRequestsLoadFailed = false
            } catch (_: Exception) {
                pendingJoinRequests = emptyList()
                pendingRequestsLoadFailed = true
            }
        } else {
            pendingJoinRequests = emptyList()
            pendingRequestsLoadFailed = false
        }
    }

    LaunchedEffect(worldLoaded, world?.cloudWorldId, world?.members, world?.localIsOwner, savedCloudWorldId, savedWorldName) {
        val current = world
        if (!worldLoaded || current == null || current.cloudWorldId.isBlank() ||
            current.cloudWorldId != savedCloudWorldId || current.worldName != savedWorldName
        ) {
            worldKeyReady = false
            return@LaunchedEffect
        }
        try {
            worldKeyReady = withContext(Dispatchers.IO) {
                val id = java.util.UUID.fromString(current.cloudWorldId)
                if (current.localIsOwner && !worldKeyVault.hasWorldKey(id, 1)) {
                    worldKeyVault.createWorldKey(id, 1)
                }
                worldKeyVault.hasWorldKey(id, 1)
            }
            if (!worldKeyReady && !current.localIsOwner) {
                cloudStatus = "ينتظر هذا الجهاز استلام مفتاح تشفير العالم من صاحبه."
            }
        } catch (_: Exception) {
            worldKeyReady = false
            cloudStatus = "تعذر تجهيز مفتاح تشفير العالم؛ أوقفنا أي إرسال للمحتوى."
        }
    }

    LaunchedEffect(worldLoaded, cloudAuthenticated, worldKeyReady, world?.cloudWorldId, world?.worldName, world?.localIsOwner, savedCloudWorldId, savedWorldName, networkRevision) {
        if (!worldLoaded || !cloudAuthenticated || !worldKeyReady) return@LaunchedEffect
        val current = world ?: return@LaunchedEffect
        if (current.cloudWorldId.isBlank() || current.cloudWorldId != savedCloudWorldId ||
            current.worldName != savedWorldName
        ) return@LaunchedEffect
        try {
            val session = authClient.ensureAuthenticatedSession()
            worldRepository.ensureWorld(session, current.cloudWorldId, current.worldName)
            cloudStatus = "العالم محفوظ محلياً ومُسجّل سحابياً."
        } catch (error: SupabaseAuthException) {
            cloudStatus = when (error.safeCode) {
                "cloud_world_network_unavailable", "cloud_world_service_unavailable" ->
                    "تعذر الوصول إلى العالم السحابي مؤقتاً؛ النسخة المحلية محفوظة."
                "cloud_world_authorization_failed", "cloud_world_owner_mismatch" ->
                    "تعذر التحقق من ملكية العالم؛ أوقفنا المزامنة لحماية البيانات."
                else -> "لم يكتمل تسجيل العالم السحابي (${error.safeCode})."
            }
        } catch (_: Exception) {
            cloudStatus = "تعذر تجهيز العالم السحابي؛ النسخة المحلية لم تتغير."
        }
    }

    fun persist(next: WorldSnapshot) {
        if (storageWarning.isNotBlank()) return
        world = next
        val revision = saveRevision.incrementAndGet()
        scope.launch {
            val result = try {
                syncMutex.withLock {
                    val previousPersisted = lastPersistedWorld
                    saveMutex.withLock {
                        if (revision == saveRevision.get()) {
                            withContext(Dispatchers.IO) { store.save(next) }
                            if (revision == saveRevision.get()) {
                                savedCloudWorldId = next.cloudWorldId
                                savedWorldName = next.worldName
                            }
                        }
                    }
                    if (revision == saveRevision.get() &&
                        cloudAuthenticated && worldKeyReady &&
                        previousPersisted != null && previousPersisted.cloudWorldId == next.cloudWorldId &&
                        next.cloudWorldId.isNotBlank()
                    ) {
                        try {
                            val session = authClient.ensureAuthenticatedSession()
                            withContext(Dispatchers.IO) {
                                mutationSync.enqueueChanges(requireNotNull(previousPersisted), next, session)
                            }
                            lastPersistedWorld = next
                            val worldId = java.util.UUID.fromString(next.cloudWorldId)
                            val (_, applied) = syncCoordinator.syncAndApply(
                                session, worldId, worldKeyVault
                            )
                            if (revision == saveRevision.get() && applied != null) {
                                world = applied
                                lastPersistedWorld = applied
                                savedCloudWorldId = applied.cloudWorldId
                                savedWorldName = applied.worldName
                                cloudStatus = "تم حفظ التعديلات ومزامنتها مع العالم المشترك."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            cloudStatus = "حُفظ التعديل محلياً؛ ستُعاد محاولة المزامنة تلقائياً."
                        }
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (revision == saveRevision.get()) {
                storageWarning = if (result) "" else "تعذر حفظ التغييرات على هذا الجهاز. تحقق من مساحة التخزين."
            }
        }
    }

    LaunchedEffect(worldLoaded, cloudAuthenticated, worldKeyReady, world?.cloudWorldId, networkRevision) {
        val current = world
        if (!worldLoaded || !cloudAuthenticated || !worldKeyReady ||
            current == null || current.cloudWorldId.isBlank()
        ) return@LaunchedEffect
        val cloudId = current.cloudWorldId
        while (true) {
            try {
                syncMutex.withLock {
                    val session = authClient.ensureAuthenticatedSession()
                    val baseline = lastPersistedWorld
                    val latest = world
                    if (baseline != null && latest != null &&
                        baseline.cloudWorldId == cloudId && latest.cloudWorldId == cloudId &&
                        baseline != latest
                    ) {
                        withContext(Dispatchers.IO) {
                            mutationSync.enqueueChanges(baseline, latest, session)
                        }
                        lastPersistedWorld = latest
                    }
                    val (_, applied) = syncCoordinator.syncAndApply(
                        session, java.util.UUID.fromString(cloudId), worldKeyVault
                    )
                    if (applied != null && world?.cloudWorldId == cloudId) {
                        world = applied
                        lastPersistedWorld = applied
                        savedCloudWorldId = applied.cloudWorldId
                        savedWorldName = applied.worldName
                        cloudStatus = "العالم متزامن بين الأجهزة."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cloudStatus = "المزامنة متوقفة مؤقتاً؛ ستُعاد المحاولة تلقائياً."
            }
            kotlinx.coroutines.delay(20_000L)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, Color(0xFF1B1028), Color(0xFF100B1B))))
    ) {
        StarField()
        if (!worldLoaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Rose, strokeWidth = 2.dp)
                    Text("نجهّز عالمكم...", color = Gold, fontSize = 14.sp)
                }
            }
        } else {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("عالمنا الخاص", color = Rose, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("YOU ARE MY SOUL  ·  OUR LITTLE UNIVERSE", color = Gold.copy(alpha = .78f), fontSize = 9.sp, letterSpacing = 1.2.sp)
                    }
                    Box(
                        Modifier.size(42.dp).background(Color.White.copy(alpha = .06f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = Gold, fontSize = 22.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (tab) {
                        "العالم" -> "مساحتكم التي تكبر معكم"
                        "الربط" -> "لكل شخص هويته الخاصة"
                        "الذكريات" -> "تفاصيل صغيرة تستحق البقاء"
                        "رسائلنا" -> "مساحة خاصة للكلمات بينكما"
                        "لحظاتنا" -> "تجارب صغيرة تصنع عالمكم"
                        else -> "رتّبوا عالمكم كما تحبّون"
                    },
                    color = Color.White.copy(alpha = .62f), fontSize = 12.sp
                )
                if (storageWarning.isNotBlank()) {
                    Text(storageWarning, color = Gold, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
                if (cloudStatus.isNotBlank()) {
                    Text(cloudStatus, color = Color.White.copy(alpha = .52f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(tween(260)) +
                        androidx.compose.animation.slideInVertically(tween(260)) { it / 18 }) togetherWith
                        (androidx.compose.animation.fadeOut(tween(150)) +
                            androidx.compose.animation.slideOutVertically(tween(150)) { -it / 28 })
                },
                label = "soul_screen_transition"
            ) { page ->
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (storageWarning.isNotBlank()) {
                            Column(
                                Modifier.fillMaxSize().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("وضع حماية البيانات", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(storageWarning, color = Color.White, fontSize = 13.sp)
                                Text("أوقفنا التعديلات مؤقتاً حتى لا نخاطر باستبدال نسخة عالمكم.", color = Rose, fontSize = 12.sp)
                            }
                        } else {
                        when (page) {
                            "الربط" -> ConnectionSection(
                                displayName = displayName, onName = { displayName = it },
                                enteredCode = enteredCode, onCode = { enteredCode = it },
                                requestStatus = requestStatus,
                                canCreate = world == null && storageWarning.isBlank(),
                                pendingRequests = pendingJoinRequests,
                                requestsLoadFailed = pendingRequestsLoadFailed,
                                hasOwnRequest = ownJoinRequestId.isNotBlank(),
                                onRefreshOwnRequest = {
                                    scope.launch {
                                        try {
                                            val session = authClient.ensureAuthenticatedSession()
                                            val status = pairingRepository.requestStatus(session, ownJoinRequestId)
                                            requestStatus = when (status?.status) {
                                                "pending" -> "طلبك ما زال بانتظار موافقة صاحب العالم."
                                                "approved" -> {
                                                    val envelope = worldKeyExchangeRepository.fetchOwnEnvelope(
                                                        session, status.worldId, 1
                                                    )
                                                    if (envelope == null) {
                                                        "تمت الموافقة، لكن مفتاح التشفير لم يصل بعد. حاول تحديث الحالة لاحقاً."
                                                    } else {
                                                        withContext(Dispatchers.IO) {
                                                            val cloudId = java.util.UUID.fromString(status.worldId)
                                                            if (!worldKeyVault.hasWorldKey(cloudId, envelope.keyVersion)) {
                                                                worldKeyVault.importDeviceEnvelope(
                                                                    cloudId,
                                                                    envelope.keyVersion,
                                                                    envelope.envelope,
                                                                    deviceKeyVault
                                                                )
                                                            }
                                                        }
                                                        val restored = snapshotBootstrapper.restoreMemberSnapshot(
                                                            session = session,
                                                            worldId = status.worldId,
                                                            worldKeyVault = worldKeyVault
                                                        )
                                                        world = restored
                                                        lastPersistedWorld = restored
                                                        savedCloudWorldId = restored.cloudWorldId
                                                        savedWorldName = restored.worldName
                                                        "تم استلام العالم المشفر واستعادته محلياً بأمان."
                                                    }
                                                }
                                                "declined" -> "لم تتم الموافقة على طلب الانضمام."
                                                "expired" -> "انتهت صلاحية طلب الانضمام؛ يمكنك إرسال طلب جديد."
                                                else -> "لم نتمكن من العثور على حالة الطلب."
                                            }
                                        } catch (_: Exception) {
                                            requestStatus = "تعذر تحديث حالة الطلب حالياً."
                                        }
                                    }
                                },
                                isOwner = world?.localIsOwner == true,
                                canManageRequests = cloudAuthenticated && world?.cloudWorldId?.isNotBlank() == true,
                                onRefreshRequests = {
                                    scope.launch {
                                        val current = world
                                        if (cloudAuthenticated && current != null && current.cloudWorldId.isNotBlank()) {
                                            try {
                                                val session = authClient.ensureAuthenticatedSession()
                                                pendingJoinRequests = pairingRepository.pendingRequests(session, current.cloudWorldId)
                                                pendingRequestsLoadFailed = false
                                            } catch (_: Exception) {
                                                pendingRequestsLoadFailed = true
                                                requestStatus = "تعذر تحديث طلبات الانضمام حالياً."
                                            }
                                        }
                                    }
                                },
                                onDecideRequest = { requestId, approve ->
                                    scope.launch {
                                        try {
                                            val session = authClient.ensureAuthenticatedSession()
                                            val decided = pairingRepository.decideRequest(session, requestId, approve)
                                            if (decided) {
                                                val current = world
                                                if (current != null) {
                                                    pendingJoinRequests = pairingRepository.pendingRequests(session, current.cloudWorldId)
                                                    pendingRequestsLoadFailed = false
                                                    if (approve) {
                                                        check(worldKeyReady && worldKeyVault.hasWorldKey(
                                                            java.util.UUID.fromString(current.cloudWorldId), 1
                                                        )) { "World key is not ready for secure member delivery" }
                                                        snapshotBootstrapper.publishOwnerSnapshot(
                                                            session = session,
                                                            world = current,
                                                            worldKeyVault = worldKeyVault
                                                        )
                                                        val deliveredDevices = worldKeyExchangeRepository.distributeOwnerWorldKey(
                                                            session = session,
                                                            worldId = current.cloudWorldId,
                                                            keyVersion = 1,
                                                            worldKeyVault = worldKeyVault,
                                                            deviceKeyVault = deviceKeyVault
                                                        )
                                                        lastPersistedWorld = current
                                                        requestStatus = "تمت الموافقة وتأمين مفاتيح التشفير وإرسال نسخة العالم المشفرة إلى الأجهزة المسجلة ($deliveredDevices)."
                                                    } else {
                                                        requestStatus = "تم رفض طلب الانضمام."
                                                    }
                                                }
                                            } else {
                                                requestStatus = "انتهت صلاحية الطلب أو لم يعد متاحاً."
                                            }
                                        } catch (_: Exception) {
                                            requestStatus = "تعذر إكمال العملية أو تسليم مفاتيح التشفير؛ تحقّق من الاتصال ثم حدّث الحالة."
                                        }
                                    }
                                },
                                onRequest = {
                                    val fromCode = DeviceIdentity(context).code
                                    val name = displayName.trim().ifBlank { "مشارك" }
                                    val validation = PairingSecurity.validateJoinRequestInput(
                                        fromCode = fromCode,
                                        hostCode = enteredCode,
                                        displayName = name
                                    )
                                    if (validation != null) {
                                        requestStatus = validation
                                    } else if (!cloudAuthenticated) {
                                        requestStatus = "الاتصال السحابي غير جاهز؛ لم يُرسل أي طلب."
                                    } else {
                                        requestStatus = "جارٍ إرسال طلب الانضمام..."
                                        scope.launch {
                                            try {
                                                val session = authClient.ensureAuthenticatedSession()
                                                val requestId = pairingRepository.requestJoin(session, enteredCode, name)
                                                requestStatus = if (requestId != null) {
                                                    ownJoinRequestId = requestId
                                                    "وصل طلبك إلى صاحب العالم. انتظر موافقته."
                                                } else {
                                                    "تعذر العثور على رمز نشط أو قبول الطلب حالياً."
                                                }
                                            } catch (_: Exception) {
                                                requestStatus = "تعذر إرسال الطلب؛ تحقق من الاتصال وحاول لاحقاً."
                                            }
                                        }
                                    }
                                },
                                onCreate = {
                                    val code = DeviceIdentity(context).code
                                    val member = WorldMember(code, displayName.trim().ifBlank { "صاحب العالم" }, true)
                                    persist(WorldSnapshot(worldName.trim().ifBlank { "عالمنا الخاص" }, code, listOf(member), 0, emptyList(), cloudWorldId = java.util.UUID.randomUUID().toString()))
                                    requestStatus = "أُنشئ العالم وحُفظ محلياً؛ سيُسجّل سحابياً عند جاهزية الاتصال."
                                }
                            )
                            "الذكريات" -> MemoriesSection(
                                memories = world?.memories.orEmpty(), text = memoryText, onText = { memoryText = it },
                                onAdd = {
                                    val value = memoryText.trim()
                                    if (value.isNotEmpty()) {
                                        val current = world ?: WorldSnapshot("عالمنا الخاص", DeviceIdentity(context).code,
                                            listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)), 0, emptyList(), cloudWorldId = java.util.UUID.randomUUID().toString())
                                        persist(current.copy(memories = current.memories + value))
                                        memoryText = ""
                                    }
                                },
                                onDelete = { index ->
                                    world?.let { current -> persist(current.copy(memories = current.memories.filterIndexed { i, _ -> i != index })) }
                                }
                            )
                            "رسائلنا" -> MessagesSection(
                                messages = world?.messages.orEmpty(),
                                text = messageText,
                                onText = { messageText = it },
                                enabled = world != null,
                                onSend = {
                                    val current = world
                                    val value = messageText.trim()
                                    if (current != null && value.isNotEmpty()) {
                                        val deviceCode = DeviceIdentity(context).code
                                        val sender = displayName.trim().ifBlank {
                                            current.members.firstOrNull { it.localCode == deviceCode }?.displayName
                                                ?: "مشارك"
                                        }
                                        val message = WorldMessage(
                                            id = java.util.UUID.randomUUID().toString(),
                                            senderName = sender,
                                            text = value,
                                            createdAtEpochMs = System.currentTimeMillis()
                                        )
                                        persist(current.copy(messages = (current.messages + message).takeLast(200)))
                                        messageText = ""
                                    }
                                }
                            )
                            "لحظاتنا" -> MomentsHub(
                                flowers = world?.gardenFlowers ?: 0,
                                onPlant = {
                                    val current = world ?: WorldSnapshot(
                                        "عالمنا الخاص", DeviceIdentity(context).code,
                                        listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)),
                                        cloudWorldId = java.util.UUID.randomUUID().toString()
                                    )
                                    persist(current.copy(gardenFlowers = current.gardenFlowers + 1, gardenLastWateredAt = System.currentTimeMillis()))
                                },
                                answer = world?.takeIf { it.dailyAnswerDate == java.time.LocalDate.now().toString() }?.dailyAnswer.orEmpty(),
                                onAnswer = { value ->
                                    val current = world ?: WorldSnapshot(
                                        "عالمنا الخاص", DeviceIdentity(context).code,
                                        listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)),
                                        cloudWorldId = java.util.UUID.randomUUID().toString()
                                    )
                                    persist(current.copy(dailyAnswerDate = java.time.LocalDate.now().toString(), dailyAnswer = value))
                                },
                                capsules = world?.capsules.orEmpty(),
                                onCreateCapsule = { message, unlockAt ->
                                    val current = world ?: WorldSnapshot(
                                        "عالمنا الخاص", DeviceIdentity(context).code,
                                        listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)),
                                        cloudWorldId = java.util.UUID.randomUUID().toString()
                                    )
                                    val now = System.currentTimeMillis()
                                    val capsule = FutureCapsule(
                                        id = java.util.UUID.randomUUID().toString(),
                                        message = message,
                                        createdAtEpochMs = now,
                                        unlockAtEpochMs = unlockAt
                                    )
                                    persist(current.copy(capsules = (current.capsules + capsule).takeLast(100)))
                                },
                                onOpenCapsule = { capsuleId ->
                                    scope.launch {
                                        try {
                                            val session = authClient.ensureAuthenticatedSession()
                                            val trustedNow = withContext(Dispatchers.IO) {
                                                serverClock.nowEpochMillis(session)
                                            }
                                            val current = world
                                            val capsule = current?.capsules?.firstOrNull { it.id == capsuleId }
                                            if (current != null && capsule != null &&
                                                capsule.openedAtEpochMs == null &&
                                                trustedNow >= capsule.unlockAtEpochMs
                                            ) {
                                                persist(current.copy(capsules = current.capsules.map {
                                                    if (it.id == capsuleId && it.openedAtEpochMs == null) {
                                                        it.copy(openedAtEpochMs = trustedNow)
                                                    } else it
                                                }))
                                                cloudStatus = "تم التحقق من موعد الكبسولة وفتحها."
                                            } else if (capsule != null) {
                                                cloudStatus = "لم يحِن موعد فتح هذه الكبسولة وفق توقيت الخادم."
                                            }
                                        } catch (_: Exception) {
                                            cloudStatus = "تعذر التحقق من توقيت الخادم؛ بقيت الكبسولة مغلقة."
                                        }
                                    }
                                },
                                stage = calculateJourneyStage(world?.relationshipStartDate.orEmpty())
                            )
                            "الإعدادات" -> SettingsSection(
                                world = world,
                                onName = { value -> world?.let { persist(it.copy(worldName = value)) } },
                                onRelationshipDate = { value -> world?.let { persist(it.copy(relationshipStartDate = value)) } }
                            )
                            else -> WorldHome(world = world, canCreate = storageWarning.isBlank(), onCreate = {
                                val code = DeviceIdentity(context).code
                                persist(WorldSnapshot("عالمنا الخاص", code, listOf(WorldMember(code, "صاحب العالم", true)), 0, emptyList(), cloudWorldId = java.util.UUID.randomUUID().toString()))
                                tab = "الربط"
                            })
                        }
                        }
                    }
                    DeveloperCredit()
                    Spacer(Modifier.height(5.dp))
                }
            }

            NavigationBar(
                containerColor = Color(0xFF160F22).copy(alpha = .97f),
                contentColor = Rose,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Text(
                                symbols[index],
                                fontSize = if (tab == item) 21.sp else 19.sp,
                                color = if (tab == item) Gold else Color.White.copy(alpha = .58f)
                            )
                        },
                        label = { Text(item, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Gold,
                            selectedTextColor = Gold,
                            indicatorColor = Rose.copy(alpha = .13f),
                            unselectedIconColor = Color.White.copy(alpha = .55f),
                            unselectedTextColor = Color.White.copy(alpha = .62f)
                        )
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun DeveloperCredit() {
    Text(
        WorldLimits.DEVELOPER_CREDIT,
        color = Gold.copy(alpha = .76f),
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

private data class StarDot(
    val x: Float,
    val y: Float,
    val phase: Float,
    val isGold: Boolean,
    val isLarge: Boolean
)

@Composable
private fun StarField() {
    // Generate the star map once; drawing frames only updates their twinkle.
    val stars = remember {
        val random = Random(41)
        List(105) { index ->
            StarDot(
                x = random.nextFloat(),
                y = random.nextFloat(),
                phase = index * .73f,
                isGold = index % 7 == 0,
                isLarge = index % 9 == 0
            )
        }
    }
    val motion = rememberInfiniteTransition(label = "starfield_motion")
    val phase by motion.animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(14000), repeatMode = RepeatMode.Restart),
        label = "star_twinkle"
    )
    Canvas(Modifier.fillMaxSize()) {
        stars.forEach { star ->
            val twinkle = (sin(phase + star.phase) + 1f) / 2f
            val base = if (star.isGold) Gold else Color.White
            val glow = if (star.isGold) .58f else .28f
            drawCircle(
                base.copy(alpha = .16f + twinkle * glow),
                radius = (if (star.isLarge) 1.8f else .7f) + twinkle * .9f,
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
    }
}

@Composable
private fun WorldHome(world: WorldSnapshot?, canCreate: Boolean, onCreate: () -> Unit) {
    val orbit = rememberInfiniteTransition(label = "world_orbit")
    val angle by orbit.animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18000), repeatMode = RepeatMode.Restart),
        label = "orbit_angle"
    )
    val pulse by orbit.animateFloat(
        initialValue = .88f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2600), repeatMode = RepeatMode.Reverse),
        label = "heart_pulse"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension * .39f
                val inner = size.minDimension * .25f
                drawCircle(Brush.radialGradient(listOf(Rose.copy(alpha = .34f), Color.Transparent)), radius = size.minDimension * .49f)
                drawCircle(Rose.copy(alpha = .32f), radius = outer, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4f))
                drawCircle(Gold.copy(alpha = .46f), radius = inner, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                val outerPoint = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer)
                val innerPoint = Offset(center.x + cos(-angle * .72f) * inner, center.y + sin(-angle * .72f) * inner)
                drawCircle(Gold, radius = 4.5f, center = outerPoint)
                drawCircle(Rose.copy(alpha = .95f), radius = 3.5f, center = innerPoint)
                drawCircle(Rose.copy(alpha = .16f), radius = 12f * pulse, center = center)
                drawCircle(Rose, radius = 7f * pulse, center = center)
            }
            Text("✦", color = Gold, fontSize = 35.sp)
        }
        Text(world?.worldName ?: "عالم يتكوّن منكم", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(if (world == null) "ابدأ بإنشاء عالم محفوظ على هذا الهاتف" else "عالم محفوظ محلياً • ${world.members.size} مشارك",
            color = Rose.copy(alpha = .9f), fontSize = 13.sp)
        world?.relationshipStartDate?.let { startDate ->
            daysSinceRelationshipStart(startDate)?.let { days ->
                Text("منذ $days يوم من الحكاية", color = Gold.copy(alpha = .9f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (world == null) "بداية عالمكم" else "مركز عالمكم", color = Gold, fontWeight = FontWeight.Bold)
                Text(if (world == null) "إنشاء العالم يحفظ نسخة محلية مشفّرة. المزامنة السحابية لم تُفعّل بعد." else "رمز المنشئ: ${world.hostCode}\nالذكريات المحفوظة: ${world.memories.size}\nمرحلة الرحلة: ${calculateJourneyStage(world.relationshipStartDate) + 1}",
                    color = Color.White, modifier = Modifier.padding(top = 6.dp))
                if (world == null) Button(onClick = onCreate, enabled = canCreate, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (canCreate) "إنشاء عالمي" else "حُميت البيانات من الاستبدال")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("المزامنة بين الأجهزة لم تُفعّل بعد؛ لن أصف البيانات المحلية بأنها مشتركة.", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun ConnectionSection(
    displayName: String,
    onName: (String) -> Unit,
    enteredCode: String,
    onCode: (String) -> Unit,
    requestStatus: String,
    canCreate: Boolean,
    pendingRequests: List<PendingWorldJoinRequest>,
    requestsLoadFailed: Boolean,
    hasOwnRequest: Boolean,
    onRefreshOwnRequest: () -> Unit,
    isOwner: Boolean,
    canManageRequests: Boolean,
    onRefreshRequests: () -> Unit,
    onDecideRequest: (String, Boolean) -> Unit,
    onRequest: () -> Unit,
    onCreate: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val code = DeviceIdentity(context).code
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
        OutlinedTextField(value = enteredCode, onValueChange = { onCode(it.filter { ch -> ch in '0'..'9' }.take(9)) }, label = { Text("رمز صاحب العالم") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("تنبيه أمان: رمز الـ9 أرقام للتعرّف فقط، وليس كلمة مرور. لا يعني وجود الجهازين على شبكة Wi‑Fi واحدة أن الاتصال آمن.", color = Gold.copy(alpha = .9f), fontSize = 11.sp)
        Button(onClick = onRequest, enabled = enteredCode.length == 9 && enteredCode != code) { Text("إرسال طلب الانضمام") }
        if (requestStatus.isNotBlank()) Text(requestStatus, color = Gold, fontSize = 12.sp)
        if (hasOwnRequest && !isOwner) {
            TextButton(onClick = onRefreshOwnRequest) {
                Text("تحديث حالة طلب الانضمام", color = Rose)
            }
        }
        Divider(color = Rose.copy(alpha = .25f))
        if (isOwner) {
            Divider(color = Rose.copy(alpha = .25f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("طلبات الانضمام", color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefreshRequests, enabled = canManageRequests) {
                    Text("تحديث", color = if (canManageRequests) Rose else Color.Gray)
                }
            }
            if (!canManageRequests) {
                Text("تظهر الطلبات بعد تفعيل الاتصال السحابي.", color = Color.LightGray, fontSize = 11.sp)
            } else if (requestsLoadFailed) {
                Text("تعذر تحميل الطلبات؛ اضغط تحديث للمحاولة مجدداً.", color = Rose, fontSize = 12.sp)
            } else if (pendingRequests.isEmpty()) {
                Text("لا توجد طلبات معلّقة حالياً.", color = Color.LightGray, fontSize = 12.sp)
            } else {
                pendingRequests.forEach { request ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardTone)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(request.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("طلب انضمام بانتظار قرارك", color = Rose, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onDecideRequest(request.requestId, true) }) {
                                    Text("موافقة")
                                }
                                OutlinedButton(onClick = { onDecideRequest(request.requestId, false) }) {
                                    Text("رفض")
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("العالم يدعم حتى ${WorldLimits.MAX_MEMBERS} مشاركين. طلب الانضمام يحتاج موافقة صاحب العالم، ولا يضيف عضوية قبل تأكيد الخادم.", color = Color.LightGray, fontSize = 12.sp)
        Button(onClick = onCreate, enabled = canCreate) {
            Text(if (canCreate) "إنشاء عالم على هذا الهاتف" else "العالم موجود بالفعل")
        }
    }
}

@Composable
private fun MemoriesSection(memories: List<String>, text: String, onText: (String) -> Unit, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("ذكرياتنا", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("تبقى الذكريات محفوظة على هذا الجهاز حتى بعد إغلاق التطبيق.", color = Rose, fontSize = 12.sp)
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
private fun MessagesSection(
    messages: List<WorldMessage>,
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("رسائلنا", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("كل رسالة تُحفظ مشفّرة وتصل إلى الجهاز الآخر عند توفر الاتصال.", color = Rose, fontSize = 12.sp)
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (enabled) "لم تُكتب رسائل بعد. ابدأوا أول محادثة هنا."
                    else "أنشئوا عالمكم أولاً لبدء تبادل الرسائل.",
                    color = Color.LightGray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardTone),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(message.senderName, color = Rose, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(message.createdAtEpochMs)),
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                            Text(message.text, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { onText(it.take(2000)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 4,
            label = { Text("اكتب رسالة") }
        )
        Button(
            onClick = onSend,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) { Text("إرسال الرسالة") }
    }
}

@Composable
private fun MomentsHub(
    flowers: Int, onPlant: () -> Unit, answer: String,
    onAnswer: (String) -> Unit,
    capsules: List<FutureCapsule>,
    onCreateCapsule: (String, Long) -> Unit,
    onOpenCapsule: (String) -> Unit,
    stage: Int
) {
    var selected by remember { mutableStateOf("الحديقة") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("الحديقة", "سؤال اليوم", "الرحلة", "كبسولة المستقبل").forEach { item ->
                FilterChip(
                    selected = selected == item,
                    onClick = { selected = item },
                    label = { Text(item, fontSize = 11.sp) }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                "الحديقة" -> GardenSection(flowers, onPlant)
                "سؤال اليوم" -> DailyQuestionSection(answer, onAnswer)
                "كبسولة المستقبل" -> CapsuleSection(capsules, onCreateCapsule, onOpenCapsule)
                else -> JourneySection(stage)
            }
        }
    }
}

@Composable
private fun GardenSection(flowers: Int, onPlant: () -> Unit) {
    val sway = rememberInfiniteTransition(label = "garden_sway")
    val breeze by sway.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2200), repeatMode = RepeatMode.Reverse),
        label = "flower_breeze"
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("حديقتنا الصغيرة", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("ازرعوا زهرة في كل مرة تتركون فيها أثراً جميلاً.", color = Rose, fontSize = 12.sp)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardTone),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(230.dp)) {
                    val ground = size.height * .82f
                    drawLine(Color(0xFF8A6C70).copy(alpha = .8f), Offset(0f, ground), Offset(size.width, ground), 2f)
                    val count = flowers.coerceAtMost(28)
                    repeat(count) { index ->
                        val x = size.width * ((index % 7) + 1) / 8f
                        val row = index / 7
                        val baseY = ground - row * 42f
                        val height = 35f + (index % 3) * 7f
                        val top = Offset(x + breeze, baseY - height)
                        drawLine(Color(0xFF76B98A), Offset(x, baseY), top, 3f)
                        drawCircle(
                            when (index % 4) {
                                0 -> Rose
                                1 -> Gold
                                2 -> Color(0xFFC6A6FF)
                                else -> Color(0xFFFFB58E)
                            },
                            radius = 5.5f + (index % 2),
                            center = top
                        )
                        drawCircle(Color(0xFFFFF1C7), radius = 2f, center = top)
                        drawCircle(Color(0xFF76B98A), radius = 5f, center = Offset(x - 5f, baseY - height * .45f))
                        drawCircle(Color(0xFF76B98A), radius = 5f, center = Offset(x + 5f, baseY - height * .65f))
                    }
                    if (flowers == 0) {
                        drawCircle(Gold.copy(alpha = .55f), radius = 4f, center = Offset(size.width / 2f, ground - 8f))
                    }
                }
                Text("$flowers زهرة في حديقتكم", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (flowers == 0) "ابدأوا أول غرسة، وستبقى محفوظة على هذا الهاتف."
                    else "كل زهرة تمثل لحظة أضفتموها إلى عالمكم.",
                    color = Color.White.copy(alpha = .72f), fontSize = 12.sp
                )
                Button(onClick = onPlant, modifier = Modifier.padding(top = 10.dp)) {
                    Text("ازرع زهرة جديدة  ✿")
                }
            }
        }
        Text(
            "تُحفظ تغييرات الحديقة محلياً وتنتقل إلى بقية الأجهزة عند توفر الاتصال.",
            color = Color.LightGray, fontSize = 11.sp
        )
    }
}

@Composable
private fun DailyQuestionSection(answer: String, onAnswer: (String) -> Unit) {
    val questions = listOf(
        "ما التفصيل الصغير الذي جعل يومك ألطف؟",
        "لو نقدر نعيد لحظة واحدة من هذا الأسبوع، أي لحظة تختار؟",
        "ما الشيء الذي تتمنى أن نتعلمه معاً؟",
        "ما مكان تتمنى أن نزوره يوماً؟",
        "ما صفة في تعاوننا تحب أن نحافظ عليها؟",
        "ما فكرة بسيطة ممكن نحولها إلى ذكرى؟",
        "ما الشيء الذي يجعلك تشعر بأن صوتك مسموع؟"
    )
    val today = java.time.LocalDate.now()
    val question = questions[(today.toEpochDay() % questions.size).toInt()]
    var draft by remember(today, answer) { mutableStateOf(answer) }
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("سؤال اليوم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(today.toString(), color = Rose.copy(alpha = .75f), fontSize = 11.sp)
        Card(colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("✧  مساحة للحوار", color = Rose, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(question, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(700) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("اكتب إجابتك") }
                )
                Button(
                    onClick = { onAnswer(draft.trim()) },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.align(Alignment.End).padding(top = 10.dp)
                ) { Text("حفظ إجابتي") }
            }
        }
        Text(
            "يتغير السؤال بحسب اليوم، وتُزامَن الإجابة المشفّرة مع بقية أجهزة العالم عند توفر الاتصال.",
            color = Color.LightGray, fontSize = 11.sp
        )
    }
}

@Composable
private fun CapsuleSection(
    capsules: List<FutureCapsule>,
    onCreate: (String, Long) -> Unit,
    onOpen: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var unlockDateText by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    val parsedDate = remember(unlockDateText) {
        runCatching { java.time.LocalDate.parse(unlockDateText) }.getOrNull()
    }
    val unlockAt = parsedDate?.atStartOfDay(java.time.ZoneId.systemDefault())
        ?.toInstant()?.toEpochMilli()
    val canCreate = draft.isNotBlank() && unlockAt != null &&
        unlockAt > System.currentTimeMillis()

    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("كبسولة المستقبل", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("اكتبوا رسالة لا تُفتح إلا في موعدها. لا يمكن تغيير الموعد بعد الحفظ.", color = Rose, fontSize = 12.sp)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(1200); feedback = "" },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            label = { Text("ماذا تريدون أن تتذكروا؟") }
        )
        OutlinedTextField(
            value = unlockDateText,
            onValueChange = { unlockDateText = it.take(10); feedback = "" },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("موعد الفتح YYYY-MM-DD") }
        )
        Button(
            onClick = {
                val timestamp = unlockAt
                if (timestamp != null && timestamp > System.currentTimeMillis() && draft.isNotBlank()) {
                    onCreate(draft.trim(), timestamp)
                    draft = ""
                    unlockDateText = ""
                    feedback = "حُفظت الكبسولة، وثُبّت موعد فتحها."
                } else {
                    feedback = "أدخل رسالة وموعداً مستقبلياً صحيحاً."
                }
            },
            enabled = canCreate
        ) { Text("حفظ الكبسولة") }
        if (feedback.isNotBlank()) Text(feedback, color = Gold, fontSize = 12.sp)
        Divider(color = Rose.copy(alpha = .25f))
        if (capsules.isEmpty()) {
            Text("لا توجد كبسولات بعد.", color = Color.LightGray)
        } else {
            capsules.sortedBy { it.unlockAtEpochMs }.forEach { capsule ->
                val isOpened = capsule.openedAtEpochMs != null
                val isReady = System.currentTimeMillis() >= capsule.unlockAtEpochMs
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardTone),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            if (isOpened) "كبسولة مفتوحة" else "رسالة تنتظر موعدها",
                            color = Gold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                                .format(java.util.Date(capsule.unlockAtEpochMs)),
                            color = Rose,
                            fontSize = 11.sp
                        )
                        if (isOpened) {
                            Text(capsule.message, color = Color.White)
                        } else if (isReady) {
                            Button(onClick = { onOpen(capsule.id) }) { Text("افتحوا الكبسولة") }
                        } else {
                            Text("ستبقى الرسالة مغلقة حتى الموعد المحدد.", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneySection(stage: Int) {
    val milestones = WorldLimits.journeyMilestones
    val titles = listOf("الشرارة الأولى", "مدارنا", "نجمة مشتركة", "مجرة الذكريات", "عالم لا ينتهي")
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("رحلتنا معاً", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(
            if (stage < 0) "أدخلوا تاريخ بداية رحلتكم من الإعدادات لتُفتح المراحل تلقائياً."
            else "تُفتح المراحل تلقائياً عند اكتمال الأيام المحددة.",
            color = Rose, fontSize = 12.sp
        )
        milestones.forEachIndexed { index, day ->
            val unlocked = index <= stage
            Card(colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("اليوم $day • ${titles[index]}", color = if (unlocked) Gold else Rose, fontWeight = FontWeight.Bold)
                        Text(if (unlocked) "مفتوحة تلقائياً" else "تُفتح بعد اكتمال $day أيام", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text(if (unlocked) "✦" else "🔒", color = if (unlocked) Gold else Color.LightGray)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    world: WorldSnapshot?,
    onName: (String) -> Unit,
    onRelationshipDate: (String) -> Unit
) {
    var name by remember(world?.worldName) { mutableStateOf(world?.worldName ?: "عالمنا الخاص") }
    var startDate by remember(world?.relationshipStartDate) {
        mutableStateOf(world?.relationshipStartDate.orEmpty())
    }
    var dateMessage by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("إعدادات العالم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("اسم العالم") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onName(name.trim().ifBlank { "عالمنا الخاص" }) }, enabled = world != null) { Text("حفظ الاسم") }
        Divider(color = Rose.copy(alpha = .25f))
        Text("بداية الرحلة", color = Gold, fontWeight = FontWeight.Bold)
        Text("اكتبوا التاريخ بصيغة سنة-شهر-يوم، مثال: 2026-05-18. تُفتح المراحل بعد مرور 7 و10 و12 و15 و20 يوماً كاملاً.", color = Color.LightGray, fontSize = 12.sp)
        OutlinedTextField(
            value = startDate,
            onValueChange = { startDate = it.take(10); dateMessage = "" },
            label = { Text("تاريخ البداية YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val parsed = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull()
                when {
                    world == null -> dateMessage = "أنشئوا العالم أولاً."
                    parsed == null -> dateMessage = "صيغة التاريخ غير صحيحة. استخدم YYYY-MM-DD."
                    parsed.isAfter(java.time.LocalDate.now()) -> dateMessage = "لا يمكن اختيار تاريخ في المستقبل."
                    else -> {
                        onRelationshipDate(parsed.toString())
                        dateMessage = "تم حفظ تاريخ البداية."
                    }
                }
            },
            enabled = world != null && startDate.isNotBlank()
        ) { Text("حفظ تاريخ البداية") }
        if (dateMessage.isNotBlank()) Text(dateMessage, color = Rose, fontSize = 12.sp)
        Text("تُحفظ بيانات العالم مشفّرة على هذا الجهاز، وتُزامَن التغييرات مع الأجهزة المعتمدة عند توفر الاتصال.", color = Color.LightGray, fontSize = 12.sp)
    }
}

private fun calculateJourneyStage(startDate: String): Int {
    val start = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull() ?: return -1
    val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(start, java.time.LocalDate.now())
    if (elapsedDays < 0) return -1
    return WorldLimits.journeyMilestones.count { elapsedDays >= it } - 1
}


private fun daysSinceRelationshipStart(startDate: String): Long? {
    val start = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, java.time.LocalDate.now())
    return days.takeIf { it >= 0 }
}
