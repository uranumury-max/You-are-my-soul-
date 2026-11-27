package com.karam.youaremysoul

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.input.pointer.pointerInput
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
private val CardTone = Color(0xAA28172F)

@Composable
fun SoulWorldApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalWorldStore(context) }
    val authClient = remember { SupabaseAuthClient(context) }
    val deviceRegistrar = remember { SupabaseDeviceRegistrar(context) }
    val worldRepository = remember { SupabaseWorldRepository() }
    val mediaStorage = remember { SupabaseWorldMediaStorage() }
    val pairingRepository = remember { SupabasePairingRepository() }
    val ownerMasterRepository = remember { OwnerMasterAccessRepository() }
    val worldKeyExchangeRepository = remember { SupabaseWorldKeyExchangeRepository(context) }
    val snapshotBootstrapper = remember { WorldSnapshotBootstrapper(context) }
    val deviceKeyVault = remember { DeviceKeyVault() }
    val worldKeyVault = remember { WorldKeyVault(context) }
    val mediaImporter = remember { WorldMediaImporter(context, mediaStorage, worldKeyVault) }
    val serverClock = remember { SupabaseServerClock() }
    val syncCoordinator = remember { WorldSyncCoordinator(context) }
    val roomSyncStore = remember { RoomSyncStore(context) }
    val mutationSync = remember { WorldMutationSync(roomSyncStore, worldKeyVault) }
    val syncMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val cloudBootstrapMutex = remember { Mutex() }
    var pendingJoinRequests by remember { mutableStateOf(emptyList<PendingWorldJoinRequest>()) }
    var pendingRequestsLoadFailed by remember { mutableStateOf(false) }
    var activeWorldMembers by remember { mutableStateOf(emptyList<ActiveWorldMember>()) }
    var activeMembersLoadFailed by remember { mutableStateOf(false) }
    var ownerMasterDialogVisible by remember { mutableStateOf(false) }
    var ownerMasterCodeInput by remember { mutableStateOf("") }
    var ownerMasterStatus by remember { mutableStateOf("") }
    var ownerMasterBusy by remember { mutableStateOf(false) }
    var ownerMasterState by remember { mutableStateOf(OwnerMasterState()) }
    var sceneGrantNumberText by remember { mutableStateOf("1") }
    var sceneGrantTargetId by remember { mutableStateOf("") }
    var sceneGrantMenuExpanded by remember { mutableStateOf(false) }
    var keyRotationBusy by remember { mutableStateOf(false) }
    var keyRotationStatus by remember { mutableStateOf("") }
    var cloudStatus by remember { mutableStateOf("") }
    var mediaStatus by remember { mutableStateOf("") }
    var mediaBusy by remember { mutableStateOf(false) }
    var mediaPreview by remember { mutableStateOf<Bitmap?>(null) }
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
            val durableBaseline = withContext(Dispatchers.IO) {
                store.loadSyncBaseline(roomSyncStore)
            }
            lastPersistedWorld = durableBaseline ?: resolved
            if (durableBaseline == null && resolved != null) {
                withContext(Dispatchers.IO) {
                    store.saveSyncBaselineIfMissing(resolved, roomSyncStore)
                }
            }
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
    val tabs = listOf("العالم", "الربط", "الذكريات", "رسائلنا", "لحظاتنا", "الأنيميشن", "الإعدادات")
    LaunchedEffect(tab) { if (tab != "الذكريات") mediaPreview = null }
    val symbols = listOf("✧", "⌁", "♡", "✉", "✦", "❖", "⚙")
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
                activeWorldMembers = pairingRepository.activeMembers(session, current.cloudWorldId)
                pendingRequestsLoadFailed = false
                activeMembersLoadFailed = false
            } catch (_: Exception) {
                pendingJoinRequests = emptyList()
                activeWorldMembers = emptyList()
                pendingRequestsLoadFailed = true
                activeMembersLoadFailed = true
            }
        } else {
            pendingJoinRequests = emptyList()
            activeWorldMembers = emptyList()
            pendingRequestsLoadFailed = false
            activeMembersLoadFailed = false
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
                if (worldKeyVault.hasWorldKey(id, 1)) worldKeyVault.setActiveKeyVersion(id, worldKeyVault.activeKeyVersion(id))
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

    LaunchedEffect(cloudAuthenticated, world?.cloudWorldId, networkRevision, tab) {
        val current = world
        if (!cloudAuthenticated || current == null || current.cloudWorldId.isBlank()) {
            ownerMasterState = OwnerMasterState()
            return@LaunchedEffect
        }
        try {
            val session = authClient.ensureAuthenticatedSession()
            ownerMasterState = ownerMasterRepository.loadState(
                session,
                java.util.UUID.fromString(current.cloudWorldId)
            )
        } catch (_: Exception) {
            ownerMasterState = OwnerMasterState()
        }
    }

    suspend fun refreshOwnerMasterState(current: WorldSnapshot) {
        val session = authClient.ensureAuthenticatedSession()
        ownerMasterState = ownerMasterRepository.loadState(
            session,
            java.util.UUID.fromString(current.cloudWorldId)
        )
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
                    if (revision == saveRevision.get() && previousPersisted == null &&
                        next.cloudWorldId.isNotBlank()
                    ) {
                        withContext(Dispatchers.IO) {
                            store.saveSyncBaselineIfMissing(next, roomSyncStore)
                        }
                        lastPersistedWorld = next
                    }
                    if (revision == saveRevision.get() &&
                        cloudAuthenticated && worldKeyReady &&
                        previousPersisted != null && previousPersisted.cloudWorldId == next.cloudWorldId &&
                        next.cloudWorldId.isNotBlank()
                    ) {
                        try {
                            val session = authClient.ensureAuthenticatedSession()
                            val events = mutationSync.buildEvents(
                                requireNotNull(previousPersisted), next, session
                            )
                            withContext(Dispatchers.IO) {
                                store.saveWithOutbox(next, next, events, roomSyncStore)
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
                    worldKeyExchangeRepository.synchronizeOwnActiveKey(session, cloudId, worldKeyVault, deviceKeyVault)
                    val baseline = withContext(Dispatchers.IO) {
                        store.loadSyncBaseline(roomSyncStore)
                    } ?: lastPersistedWorld
                    val latest = world
                    if (baseline != null && latest != null &&
                        baseline.cloudWorldId == cloudId && latest.cloudWorldId == cloudId &&
                        baseline != latest
                    ) {
                        val events = mutationSync.buildEvents(baseline, latest, session)
                        withContext(Dispatchers.IO) {
                            store.saveWithOutbox(latest, latest, events, roomSyncStore)
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
            .background(Brush.verticalGradient(listOf(Color(0xFF080610), Night, Color(0xFF21102F), Color(0xFF100B1B))))
    ) {
        NebulaGlow()
        StarField()
        FloatingHearts()
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
                        Text("عالمنا الخاص", color = Rose, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text("✦  YOU ARE MY SOUL  ·  OUR LITTLE UNIVERSE  ✦", color = Gold.copy(alpha = .82f), fontSize = 8.sp, letterSpacing = 1.05.sp)
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
                                activeMembers = activeWorldMembers,
                                membersLoadFailed = activeMembersLoadFailed,
                                hasOwnRequest = ownJoinRequestId.isNotBlank(),
                                onRefreshOwnRequest = {
                                    scope.launch {
                                        try {
                                            val session = authClient.ensureAuthenticatedSession()
                                            val status = pairingRepository.requestStatus(session, ownJoinRequestId)
                                            requestStatus = when (status?.status) {
                                                "pending" -> "طلبك ما زال بانتظار موافقة صاحب العالم."
                                                "approved" -> {
                                                    val activeVersion = worldKeyExchangeRepository.currentWorldKeyVersion(session, status.worldId)
                                                    val envelope = worldKeyExchangeRepository.fetchOwnEnvelope(
                                                        session, status.worldId, activeVersion
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
                                                             worldKeyVault.setActiveKeyVersion(cloudId, envelope.keyVersion)
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
                                                activeWorldMembers = pairingRepository.activeMembers(session, current.cloudWorldId)
                                                pendingRequestsLoadFailed = false
                                                activeMembersLoadFailed = false
                                            } catch (_: Exception) {
                                                pendingRequestsLoadFailed = true
                                                activeMembersLoadFailed = true
                                                requestStatus = "تعذر تحديث قائمة الطلبات والأعضاء حالياً."
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
                                                    activeWorldMembers = pairingRepository.activeMembers(session, current.cloudWorldId)
                                                    pendingRequestsLoadFailed = false
                                                    activeMembersLoadFailed = false
                                                    if (approve) {
                                                         val activeVersion = worldKeyExchangeRepository.currentWorldKeyVersion(session, current.cloudWorldId)
                                                         val cloudWorldId = java.util.UUID.fromString(current.cloudWorldId)
                                                         check(worldKeyReady && worldKeyVault.hasWorldKey(cloudWorldId, activeVersion)) { "World key is not ready for secure member delivery" }
                                                         worldKeyVault.setActiveKeyVersion(cloudWorldId, activeVersion)
                                                         snapshotBootstrapper.publishOwnerSnapshot(
                                                             session = session,
                                                             world = current,
                                                             worldKeyVault = worldKeyVault
                                                         )
                                                         val deliveredDevices = worldKeyExchangeRepository.distributeOwnerWorldKey(
                                                             session = session,
                                                             worldId = current.cloudWorldId,
                                                             keyVersion = activeVersion,
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
                                onRevokeMember = { memberUserId ->
                                    scope.launch {
                                        try {
                                            val current = world
                                            if (!cloudAuthenticated || current == null || current.cloudWorldId.isBlank() || !current.localIsOwner) {
                                                requestStatus = "لا يمكن إدارة العضوية قبل تأكيد مالك العالم والاتصال."
                                            } else {
                                                val session = authClient.ensureAuthenticatedSession()
                                                val revoked = pairingRepository.revokeMember(session, current.cloudWorldId, memberUserId)
                                                if (revoked) {
                                                    activeWorldMembers = pairingRepository.activeMembers(session, current.cloudWorldId)
                                                    activeMembersLoadFailed = false
                                                    requestStatus = "أُلغيت العضوية. سيُمنع الجهاز من الوصول السحابي الجديد؛ وقد تبقى نسخة سبق تنزيلها على ذلك الجهاز."
                                                } else {
                                                    requestStatus = "لم يؤكد الخادم إلغاء العضوية؛ حدّث القائمة للتحقق."
                                                }
                                            }
                                        } catch (_: Exception) {
                                            activeMembersLoadFailed = true
                                            requestStatus = "تعذر إلغاء العضوية الآن؛ لم نعرض العملية على أنها نجحت."
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
                                keyRotationBusy = keyRotationBusy,
                                keyRotationStatus = keyRotationStatus,
                                onRotateKey = {
                                    if (!keyRotationBusy && world?.localIsOwner == true && cloudAuthenticated) {
                                        keyRotationBusy = true
                                        keyRotationStatus = "جارٍ تجهيز إصدار تشفير جديد..."
                                        scope.launch {
                                            try {
                                                syncMutex.withLock {
                                                    val current = requireNotNull(world)
                                                    val session = authClient.ensureAuthenticatedSession()
                                                    val newVersion = worldKeyExchangeRepository.rotateOwnerWorldKey(
                                                        session, current.cloudWorldId, worldKeyVault, deviceKeyVault
                                                    )
                                                    snapshotBootstrapper.publishOwnerSnapshot(
                                                        session, current, worldKeyVault
                                                    )
                                                    val (_, applied) = syncCoordinator.syncAndApply(
                                                        session, java.util.UUID.fromString(current.cloudWorldId), worldKeyVault
                                                    )
                                                    if (applied != null) {
                                                        world = applied
                                                        lastPersistedWorld = applied
                                                        savedCloudWorldId = applied.cloudWorldId
                                                        savedWorldName = applied.worldName
                                                    }
                                                    keyRotationStatus = "تم تفعيل إصدار التشفير $newVersion. ستستلم الأجهزة الأخرى المفتاح عند مزامنتها."
                                                    cloudStatus = "تم تدوير مفتاح العالم وتحديث اللقطة المشفّرة."
                                                    networkRevision += 1
                                                }
                                            } catch (_: Exception) {
                                                keyRotationStatus = "لم يكتمل التدوير أو تأكيده. حدّث الاتصال وأعد المحاولة؛ لن يُفعّل الخادم إصداراً قبل تسليم أغلفته."
                                            } finally {
                                                keyRotationBusy = false
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
                                memories = world?.memories.orEmpty(),
                                media = world?.media.orEmpty(),
                                text = memoryText,
                                mediaStatus = mediaStatus,
                                mediaEnabled = world != null && cloudAuthenticated && worldKeyReady && !mediaBusy,
                                onText = { memoryText = it },
                                onAdd = {
                                    val value = memoryText.trim()
                                    if (value.isNotEmpty()) {
                                        val current = world ?: WorldSnapshot("عالمنا الخاص", DeviceIdentity(context).code,
                                            listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)), 0, emptyList(), cloudWorldId = java.util.UUID.randomUUID().toString())
                                        persist(current.copy(memories = (current.memories + value).takeLast(100)))
                                        memoryText = ""
                                    }
                                },
                                onDelete = { index ->
                                    world?.let { current -> persist(current.copy(memories = current.memories.filterIndexed { i, _ -> i != index })) }
                                },
                                onUploadImage = { uri ->
                                    val current = world
                                    if (current == null || !cloudAuthenticated || !worldKeyReady) {
                                        mediaStatus = "أنشئوا العالم وأكملوا الاتصال الآمن قبل رفع الصور."
                                    } else if (current.media.size >= WorldLimits.MAX_MEDIA_ITEMS) {
                                        mediaStatus = "اكتمل الألبوم؛ احذفوا صورة قبل إضافة صورة جديدة."
                                    } else if (mediaBusy) {
                                        mediaStatus = "هناك عملية ألبوم قيد التنفيذ؛ انتظروا اكتمالها."
                                    } else {
                                        mediaBusy = true
                                        mediaStatus = "جارٍ تشفير الصورة ورفعها..."
                                        scope.launch {
                                            try {
                                                val session = authClient.ensureAuthenticatedSession()
                                                val item = withContext(Dispatchers.IO) {
                                                    mediaImporter.import(uri, session, java.util.UUID.fromString(current.cloudWorldId))
                                                }
                                                val latest = world
                                                if (latest != null && latest.cloudWorldId == current.cloudWorldId &&
                                                    latest.media.size < WorldLimits.MAX_MEDIA_ITEMS
                                                ) {
                                                    persist(latest.copy(media = (latest.media + item).takeLast(WorldLimits.MAX_MEDIA_ITEMS)))
                                                    mediaStatus = "أُضيفت الصورة إلى الألبوم المشترك بأمان."
                                                } else {
                                                    mediaStorage.delete(
                                                        session,
                                                        java.util.UUID.fromString(current.cloudWorldId),
                                                        java.util.UUID.fromString(item.id)
                                                    )
                                                    mediaStatus = "اكتمل الألبوم أو تغيّر العالم أثناء الرفع؛ أُزيل الملف الجديد ولم نتركه يتيماً."
                                                }
                                            } catch (error: IllegalArgumentException) {
                                                mediaStatus = when (error.message) {
                                                    "media_type_unknown", "media_type_not_supported" -> "نوع الصورة غير مدعوم. اختروا JPEG أو PNG أو WebP."
                                                    "media_too_large" -> "تجاوزت الصورة الحد الأقصى البالغ 20 ميغابايت."
                                                    "media_empty" -> "الملف المحدد فارغ."
                                                    else -> "تعذرت قراءة الصورة المحددة."
                                                }
                                            } catch (error: Exception) {
                                                mediaStatus = when ((error as? SupabaseAuthException)?.safeCode) {
                                                    "media_authorization_failed" -> "تعذر السماح بالرفع؛ تحققوا من عضوية الجهاز ومن عدم بلوغ الألبوم حد 100 صورة."
                                                    "media_rate_limited" -> "طلبات التخزين كثيرة حالياً؛ حاولوا لاحقاً."
                                                    "media_service_unavailable", "media_network_unavailable" -> "تعذر الاتصال بالتخزين؛ لم تُضف بيانات الصورة إلى الألبوم."
                                                    else -> "تعذر رفع الصورة بأمان؛ لم تُضف إلى الألبوم."
                                                }
                                            } finally {
                                                mediaBusy = false
                                            }
                                        }
                                    }
                                },
                                onOpenMedia = { item ->
                                    val current = world
                                    if (current != null && cloudAuthenticated && worldKeyReady) {
                                        mediaStatus = "جارٍ تنزيل الصورة والتحقق من تشفيرها..."
                                        scope.launch {
                                            try {
                                                val session = authClient.ensureAuthenticatedSession()
                                                val bytes = withContext(Dispatchers.IO) {
                                                    mediaStorage.download(
                                                        session,
                                                        java.util.UUID.fromString(current.cloudWorldId),
                                                        java.util.UUID.fromString(item.id),
                                                        worldKeyVault,
                                                        item.keyVersion
                                                    )
                                                }
                                                try {
                                                    val decoded = withContext(Dispatchers.IO) { decodeMediaPreview(bytes) }
                                                    mediaPreview = decoded
                                                    mediaStatus = if (decoded == null) "تعذر عرض الصورة بعد فك تشفيرها." else "عُرضت الصورة بعد التحقق من سلامتها."
                                                } finally {
                                                    bytes.fill(0)
                                                }
                                            } catch (_: Exception) {
                                                mediaStatus = "تعذر تنزيل الصورة أو التحقق من سلامتها."
                                            }
                                        }
                                    }
                                },
                                onClosePreview = { mediaPreview = null },
                                onDeleteMedia = { item ->
                                    val current = world
                                    if (current != null && cloudAuthenticated && worldKeyReady && !mediaBusy) {
                                        mediaBusy = true
                                        mediaStatus = "جارٍ حذف الصورة من التخزين المشترك..."
                                        scope.launch {
                                            try {
                                                val session = authClient.ensureAuthenticatedSession()
                                                mediaStorage.delete(
                                                    session,
                                                    java.util.UUID.fromString(current.cloudWorldId),
                                                    java.util.UUID.fromString(item.id)
                                                )
                                                val latest = world
                                                if (latest != null && latest.cloudWorldId == current.cloudWorldId) {
                                                    persist(latest.copy(media = latest.media.filterNot { it.id == item.id }))
                                                    mediaPreview = null
                                                    mediaStatus = "حُذفت الصورة من التخزين المشترك."
                                                }
                                            } catch (_: Exception) {
                                                mediaStatus = "تعذر حذف الصورة من التخزين؛ بقيت بياناتها في الألبوم."
                                            } finally {
                                                mediaBusy = false
                                            }
                                        }
                                    }
                                },
                                preview = mediaPreview
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
                                        persist(current.copy(messages = (current.messages + message).takeLast(100)))
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
                                answer = world?.let { current ->
                                    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                                    val own = current.dailyAnswers.firstOrNull {
                                        it.date == today && it.authorCode == DeviceIdentity(context).code
                                    }?.answer
                                    own ?: if (current.dailyAnswers.none { it.date == today } &&
                                        current.dailyAnswerDate == today
                                    ) current.dailyAnswer else ""
                                }.orEmpty(),
                                dailyAnswers = world?.dailyAnswers.orEmpty(),
                                currentAuthorCode = DeviceIdentity(context).code,
                                memberNames = world?.members?.associate { it.localCode to it.displayName }.orEmpty(),
                                onAnswer = { value ->
                                    val current = world ?: WorldSnapshot(
                                        "عالمنا الخاص", DeviceIdentity(context).code,
                                        listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)),
                                        cloudWorldId = java.util.UUID.randomUUID().toString()
                                    )
                                    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                                    val authorCode = DeviceIdentity(context).code
                                    val entry = DailyAnswerEntry(today, authorCode, value, System.currentTimeMillis())
                                    val answers = current.dailyAnswers.filterNot {
                                        it.date == today && it.authorCode == authorCode
                                    } + entry
                                    persist(current.copy(
                                        dailyAnswerDate = today,
                                        dailyAnswer = value,
                                        dailyAnswers = answers.takeLast(100)
                                    ))
                                },
                                drawings = world?.drawings.orEmpty(),
                                onAddStroke = { stroke ->
                                    val current = world ?: WorldSnapshot(
                                        "عالمنا الخاص", DeviceIdentity(context).code,
                                        listOf(WorldMember(DeviceIdentity(context).code, "صاحب العالم", true)),
                                        cloudWorldId = java.util.UUID.randomUUID().toString()
                                    )
                                    persist(current.copy(drawings = DrawingHistory.append(current.drawings, stroke)))
                                },
                                onClearDrawing = {
                                    world?.let { current -> persist(current.copy(drawings = emptyList())) }
                                },
                                onUndoDrawing = {
                                    world?.let { current ->
                                        if (current.drawings.isNotEmpty()) {
                                            persist(current.copy(drawings = DrawingHistory.undo(current.drawings)))
                                        }
                                    }
                                },
                                drawingVersions = world?.drawingVersions.orEmpty(),
                                onSaveDrawingVersion = { title ->
                                    world?.let { current ->
                                        if (current.drawings.isNotEmpty()) {
                                            val version = DrawingVersion(
                                                id = java.util.UUID.randomUUID().toString(),
                                                title = title.trim().take(40),
                                                createdAtEpochMs = System.currentTimeMillis(),
                                                strokes = current.drawings.toList()
                                            )
                                            if (WorldEventValidation.drawingVersion(version)) {
                                                persist(current.copy(
                                                    drawingVersions = DrawingVersionHistory.append(current.drawingVersions, version)
                                                ))
                                            }
                                        }
                                    }
                                },
                                onRestoreDrawingVersion = { versionId ->
                                    world?.let { current ->
                                        val version = current.drawingVersions.firstOrNull { it.id == versionId }
                                        if (version != null) persist(current.copy(drawings = DrawingVersionHistory.restore(version)))
                                    }
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
                                    persist(current.copy(capsules = (current.capsules + capsule).takeLast(30)))
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
                                stage = calculateJourneyStage(world?.relationshipStartDate.orEmpty()),
                                relationshipStartDate = world?.relationshipStartDate.orEmpty()
                            )
                            "الأنيميشن" -> AnimationGallerySection(relationshipStartDate = world?.relationshipStartDate.orEmpty(), names = world?.members?.map { it.displayName }?.filter { it.isNotBlank() } ?: emptyList(), ownerMasterUnlocked = ownerMasterState.unlocked, grantedScenes = ownerMasterState.grantedScenes)
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
                    DeveloperCredit(onSecretTap = {
                        ownerMasterDialogVisible = true
                        ownerMasterCodeInput = ""
                        ownerMasterStatus = ""
                        val current = world
                        if (current != null && cloudAuthenticated && current.cloudWorldId.isNotBlank()) {
                            scope.launch {
                                try {
                                    val session = authClient.ensureAuthenticatedSession()
                                    if (current.localIsOwner) activeWorldMembers = pairingRepository.activeMembers(session, current.cloudWorldId)
                                    ownerMasterState = ownerMasterRepository.loadState(session, java.util.UUID.fromString(current.cloudWorldId))
                                } catch (_: Exception) {
                                    ownerMasterStatus = "تعذر تحميل صلاحية المالك؛ تحقق من إعداد الخدمة والاقتران."
                                }
                            }
                        } else {
                            ownerMasterStatus = "تحتاج هذه الميزة إلى عالم مقترن واتصال سحابي مُعد."
                        }
                    })
                    Spacer(Modifier.height(5.dp))
                }
            }

            NavigationBar(
                containerColor = Color(0xDD160F22),
                contentColor = Rose,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Text(
                                symbols[index],
                                fontSize = if (tab == item) 23.sp else 18.sp,
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
    if (ownerMasterDialogVisible) {
        OwnerMasterDialog(
            isOwner = world?.localIsOwner == true,
            unlocked = ownerMasterState.unlocked,
            code = ownerMasterCodeInput,
            onCodeChange = { ownerMasterCodeInput = it.take(72) },
            status = ownerMasterStatus,
            busy = ownerMasterBusy,
            members = activeWorldMembers,
            selectedTargetId = sceneGrantTargetId,
            onTargetSelected = { sceneGrantTargetId = it },
            targetMenuExpanded = sceneGrantMenuExpanded,
            onTargetMenuExpandedChange = { sceneGrantMenuExpanded = it },
            sceneNumber = sceneGrantNumberText,
            onSceneNumberChange = { sceneGrantNumberText = it.filter(Char::isDigit).take(2) },
            onConfigure = {
                val current = world
                if (current == null || !current.localIsOwner || !cloudAuthenticated) {
                    ownerMasterStatus = "إعداد الرمز متاح لصاحب العالم بعد تفعيل الاتصال السحابي."
                } else {
                    val code = ownerMasterCodeInput
                    scope.launch {
                        ownerMasterBusy = true
                        try {
                            val session = authClient.ensureAuthenticatedSession()
                            val created = ownerMasterRepository.configure(
                                session,
                                java.util.UUID.fromString(current.cloudWorldId),
                                code
                            )
                            if (created) {
                                refreshOwnerMasterState(current)
                                ownerMasterStatus = "تم إعداد الرمز الخاص وتفعيل صلاحية المالك على هذا الحساب."
                            } else {
                                ownerMasterStatus = "سبق إعداد الرمز لهذا العالم. استخدم التحقق بالرمز الحالي."
                            }
                        } catch (error: SupabaseAuthException) {
                            ownerMasterStatus = when (error.safeCode) {
                                "owner_master_authorization_failed" -> "لا يملك هذا الحساب صلاحية إعداد الرمز."
                                "owner_master_rate_limited" -> "تجاوزت محاولات التحقق المسموحة؛ انتظر ثم أعد المحاولة."
                                else -> "تعذر إعداد الرمز (${error.safeCode})."
                            }
                        } catch (_: Exception) {
                            ownerMasterStatus = "تعذر إعداد الرمز؛ لم نحفظه على الجهاز."
                        } finally {
                            ownerMasterBusy = false
                            ownerMasterCodeInput = ""
                        }
                    }
                }
            },
            onVerify = {
                val current = world
                if (current == null || !cloudAuthenticated) {
                    ownerMasterStatus = "يتطلب التحقق اتصالاً سحابياً وعالماً مقترناً."
                } else {
                    val code = ownerMasterCodeInput
                    scope.launch {
                        ownerMasterBusy = true
                        try {
                            val session = authClient.ensureAuthenticatedSession()
                            val accepted = ownerMasterRepository.verify(
                                session,
                                java.util.UUID.fromString(current.cloudWorldId),
                                code
                            )
                            if (accepted) {
                                refreshOwnerMasterState(current)
                                ownerMasterStatus = "تم التحقق؛ أصبحت المشاهد والميزات المخصصة للمالك متاحة."
                            } else {
                                ownerMasterStatus = "لم يُقبل الرمز. تحقق منه أو انتظر انتهاء حد المحاولات."
                            }
                        } catch (error: SupabaseAuthException) {
                            ownerMasterStatus = when (error.safeCode) {
                                "owner_master_rate_limited" -> "تجاوزت محاولات التحقق المسموحة؛ انتظر 15 دقيقة."
                                "owner_master_authorization_failed" -> "هذا الحساب ليس عضواً نشطاً في العالم."
                                else -> "تعذر التحقق (${error.safeCode})."
                            }
                        } catch (_: Exception) {
                            ownerMasterStatus = "تعذر الاتصال بخدمة التحقق."
                        } finally {
                            ownerMasterBusy = false
                            ownerMasterCodeInput = ""
                        }
                    }
                }
            },
            onGrant = {
                val current = world
                val target = runCatching { java.util.UUID.fromString(sceneGrantTargetId) }.getOrNull()
                val number = sceneGrantNumberText.toIntOrNull()
                if (current == null || target == null || number == null || number !in 1..21 || !ownerMasterState.unlocked) {
                    ownerMasterStatus = "اختر الشريك ورقم مشهد صحيحاً، ثم تحقق من الرمز الرئيسي."
                } else {
                    scope.launch {
                        ownerMasterBusy = true
                        try {
                            val session = authClient.ensureAuthenticatedSession()
                            ownerMasterRepository.grantScene(
                                session,
                                java.util.UUID.fromString(current.cloudWorldId),
                                target,
                                number
                            )
                            ownerMasterStatus = "تم منح المشهد $number للشريك؛ سيظهر لديه بعد تحديث الاتصال."
                        } catch (error: SupabaseAuthException) {
                            ownerMasterStatus = "تعذر منح المشهد (${error.safeCode})."
                        } catch (_: Exception) {
                            ownerMasterStatus = "تعذر منح المشهد؛ لم يتغير محتوى العالم."
                        } finally {
                            ownerMasterBusy = false
                        }
                    }
                }
            },
            onDismiss = {
                ownerMasterDialogVisible = false
                ownerMasterCodeInput = ""
                sceneGrantMenuExpanded = false
            }
        )
    }


}

@Composable
private fun OwnerMasterDialog(
    isOwner: Boolean,
    unlocked: Boolean,
    code: String,
    onCodeChange: (String) -> Unit,
    status: String,
    busy: Boolean,
    members: List<ActiveWorldMember>,
    selectedTargetId: String,
    onTargetSelected: (String) -> Unit,
    targetMenuExpanded: Boolean,
    onTargetMenuExpandedChange: (Boolean) -> Unit,
    sceneNumber: String,
    onSceneNumberChange: (String) -> Unit,
    onConfigure: () -> Unit,
    onVerify: () -> Unit,
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صلاحية المالك الخاصة", color = Rose) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "أدخل الرمز الذي أُعدّ لصاحب العالم. لا يُحفظ الرمز على هذا الجهاز؛ يتحقق منه الخادم عبر اتصال مشفر.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("الرمز الرئيسي") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (isOwner) {
                    Button(onClick = onConfigure, enabled = !busy && code.length >= 12) {
                        Text("إعداد الرمز لأول مرة")
                    }
                }
                Button(onClick = onVerify, enabled = !busy && code.isNotEmpty()) {
                    Text(if (unlocked) "تأكيد الصلاحية مجدداً" else "التحقق وفتح المشاهد")
                }
                if (isOwner && unlocked) {
                    Divider(color = Rose.copy(alpha = .25f))
                    Text("منح مشهد للشريك", color = Gold, fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(
                            onClick = { onTargetMenuExpandedChange(true) },
                            enabled = !busy && members.isNotEmpty()
                        ) {
                            Text(
                                members.firstOrNull { it.userId == selectedTargetId }?.displayName
                                    ?: "اختيار الشريك"
                            )
                        }
                        DropdownMenu(
                            expanded = targetMenuExpanded,
                            onDismissRequest = { onTargetMenuExpandedChange(false) }
                        ) {
                            members.forEach { member ->
                                DropdownMenuItem(
                                    text = { Text(member.displayName) },
                                    onClick = {
                                        onTargetSelected(member.userId)
                                        onTargetMenuExpandedChange(false)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = sceneNumber,
                        onValueChange = onSceneNumberChange,
                        label = { Text("رقم المشهد من 1 إلى 21") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = onGrant,
                        enabled = !busy && selectedTargetId.isNotBlank() &&
                            sceneNumber.toIntOrNull()?.let { it in 1..21 } == true
                    ) {
                        Text("منح المشهد المحدد")
                    }
                    if (members.isEmpty()) {
                        Text("لم يظهر شريك نشط بعد؛ أكمل الاقتران أولاً.", color = Color.LightGray, fontSize = 11.sp)
                    }
                }
                if (status.isNotBlank()) {
                    Text(status, color = Gold, fontSize = 12.sp)
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق", color = Rose) }
        },
        containerColor = Color(0xFF21152D)
    )
}

@Composable
private fun DeveloperCredit(onSecretTap: () -> Unit) {
    var tapCount by remember { mutableIntStateOf(0) }
    Text(
        WorldLimits.DEVELOPER_CREDIT,
        color = Gold.copy(alpha = .76f),
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp).clickable {
            tapCount += 1
            if (tapCount >= 7) {
                tapCount = 0
                onSecretTap()
            }
        },
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
private fun NebulaGlow() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x553F1B78), Color.Transparent),
                center = Offset(size.width * .18f, size.height * .23f),
                radius = size.width * .62f
            ),
            radius = size.width * .62f,
            center = Offset(size.width * .18f, size.height * .23f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x332D5FA5), Color.Transparent),
                center = Offset(size.width * .86f, size.height * .72f),
                radius = size.width * .54f
            ),
            radius = size.width * .54f,
            center = Offset(size.width * .86f, size.height * .72f)
        )
    }
}

@Composable
private fun FloatingHearts() {
    val motion = rememberInfiniteTransition(label = "ambient_heart_motion")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18000), repeatMode = RepeatMode.Restart),
        label = "ambient_heart_phase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val hearts = listOf(
            Triple(.13f, .28f, .018f), Triple(.82f, .20f, .014f),
            Triple(.73f, .49f, .021f), Triple(.20f, .73f, .012f),
            Triple(.91f, .82f, .017f), Triple(.46f, .13f, .010f)
        )
        hearts.forEachIndexed { index, heart ->
            val x = size.width * heart.first + kotlin.math.sin(phase + index) * size.width * .012f
            val y = size.height * heart.second + kotlin.math.cos(phase * .72f + index) * size.height * .012f
            val r = size.minDimension * heart.third
            val pulse = .78f + ((kotlin.math.sin(phase * 1.4f + index) + 1f) * .18f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, y + r * .72f * pulse)
                cubicTo(x - r * 1.8f, y - r * .3f, x - r * 1.15f, y - r * 1.55f, x, y - r * .62f)
                cubicTo(x + r * 1.15f, y - r * 1.55f, x + r * 1.8f, y - r * .3f, x, y + r * .72f * pulse)
                close()
            }
            drawPath(path, (if (index % 2 == 0) Rose else Gold).copy(alpha = .10f + pulse * .08f))
        }
    }
}

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
            Text("♡", color = Rose, fontSize = 46.sp, fontWeight = FontWeight.Light)
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
                Text(if (world == null) "يُحفَظ العالم محلياً بتشفير آمن، وتنتقل التغييرات إلى الأجهزة المعتمدة بعد إعداد الخدمة وتوفر الاتصال." else "رمز المنشئ: ${world.hostCode}
الذكريات المحفوظة: ${world.memories.size}
مرحلة الرحلة: ${calculateJourneyStage(world.relationshipStartDate) + 1}",
                    color = Color.White, modifier = Modifier.padding(top = 6.dp))
                if (world == null) Button(onClick = onCreate, enabled = canCreate, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (canCreate) "إنشاء عالمي" else "حُميت البيانات من الاستبدال")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("تتطلب المزامنة بين الأجهزة إعداد الخدمة السحابية واعتماد الجهاز الآخر؛ وتظل بيانات هذا الجهاز محفوظة محلياً بأمان إلى حين ذلك.", color = Color.LightGray, fontSize = 12.sp)
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
    activeMembers: List<ActiveWorldMember>,
    membersLoadFailed: Boolean,
    hasOwnRequest: Boolean,
    onRefreshOwnRequest: () -> Unit,
    isOwner: Boolean,
    canManageRequests: Boolean,
    onRefreshRequests: () -> Unit,
    onDecideRequest: (String, Boolean) -> Unit,
    onRevokeMember: (String) -> Unit,
    keyRotationBusy: Boolean,
    keyRotationStatus: String,
    onRotateKey: () -> Unit,
    onRequest: () -> Unit,
    onCreate: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val code = DeviceIdentity(context).code
    var memberToRevoke by remember { mutableStateOf<ActiveWorldMember?>(null) }
    var confirmKeyRotation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("هوية هذا الجهاز", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        if (isOwner) {
            Divider(color = Rose.copy(alpha = .25f))
            Text("حماية العالم", color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("تدوير مفتاح التشفير يحدّث مفتاح المحتوى للأجهزة النشطة. لا تُحذف المفاتيح القديمة من هذا الجهاز.", color = Color.LightGray, fontSize = 11.sp)
            OutlinedButton(
                onClick = { confirmKeyRotation = true },
                enabled = canManageRequests && !keyRotationBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (keyRotationBusy) "جارٍ تدوير المفتاح..." else "تدوير مفتاح التشفير")
            }
            if (keyRotationStatus.isNotBlank()) Text(keyRotationStatus, color = Rose, fontSize = 12.sp)
        }
        if (isOwner) {
            Divider(color = Rose.copy(alpha = .25f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("الأعضاء المرتبطون", color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefreshRequests, enabled = canManageRequests) {
                    Text("تحديث", color = if (canManageRequests) Rose else Color.Gray)
                }
            }
            when {
                !canManageRequests -> Text("تظهر العضويات بعد إعداد الاتصال السحابي.", color = Color.LightGray, fontSize = 11.sp)
                membersLoadFailed -> Text("تعذر تحميل الأعضاء؛ اضغط تحديث للمحاولة مجدداً.", color = Rose, fontSize = 12.sp)
                activeMembers.isEmpty() -> Text("لا يوجد عضو آخر مرتبط حالياً.", color = Color.LightGray, fontSize = 12.sp)
                else -> activeMembers.forEach { member ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardTone)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(member.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("عضو معتمد • ${member.userId.takeLast(6)}", color = Rose, fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { memberToRevoke = member }) {
                                Text("إلغاء العضوية")
                            }
                        }
                    }
                }
            }
        }
        if (confirmKeyRotation) {
            AlertDialog(
                onDismissRequest = { confirmKeyRotation = false },
                title = { Text("تدوير مفتاح تشفير العالم؟") },
                text = {
                    Text("سيُنشأ إصدار جديد وتُرسل أغلفته المشفّرة إلى الأجهزة النشطة. لن يُفعّل الخادم الإصدار الجديد قبل تأكيد تسليم الأغلفة، وستبقى المفاتيح القديمة محفوظة لقراءة البيانات السابقة.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmKeyRotation = false
                        onRotateKey()
                    }) { Text("بدء التدوير") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmKeyRotation = false }) { Text("إلغاء") }
                },
                containerColor = CardTone,
                titleContentColor = Gold,
                textContentColor = Color.White
            )
        }
        memberToRevoke?.let { member ->
            AlertDialog(
                onDismissRequest = { memberToRevoke = null },
                title = { Text("إلغاء عضوية ${member.displayName}؟") },
                text = {
                    Text("سيمنع الخادم هذا العضو من الوصول إلى بيانات العالم الجديدة. لا يمكن سحب المحتوى الذي سبق تنزيله على جهازه.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        memberToRevoke = null
                        onRevokeMember(member.userId)
                    }) { Text("إلغاء العضوية") }
                },
                dismissButton = {
                    TextButton(onClick = { memberToRevoke = null }) { Text("إبقاء العضوية") }
                },
                containerColor = CardTone,
                titleContentColor = Gold,
                textContentColor = Color.White
            )
        }
        Text("العالم يدعم حتى ${WorldLimits.MAX_MEMBERS} مشاركين. طلب الانضمام يحتاج موافقة صاحب العالم، ولا يضيف عضوية قبل تأكيد الخادم.", color = Color.LightGray, fontSize = 12.sp)
        Button(onClick = onCreate, enabled = canCreate) {
            Text(if (canCreate) "إنشاء عالم على هذا الهاتف" else "العالم موجود بالفعل")
        }
    }
}

@Composable
private fun MemoriesSection(
    memories: List<String>,
    media: List<WorldMediaItem>,
    text: String,
    mediaStatus: String,
    mediaEnabled: Boolean,
    onText: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onUploadImage: (Uri) -> Unit,
    onOpenMedia: (WorldMediaItem) -> Unit,
    onClosePreview: () -> Unit,
    onDeleteMedia: (WorldMediaItem) -> Unit,
    preview: Bitmap?
) {
    var mediaToDelete by remember { mutableStateOf<WorldMediaItem?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUploadImage)
    }
    DisposableEffect(preview) {
        onDispose { preview?.recycle() }
    }
    Column(Modifier.fillMaxSize()) {
        Text("ذكرياتنا", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("احفظوا الكلمات والصور في مساحة خاصة مشفّرة بين الجهازين.", color = Rose, fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { onText(it.take(400)) },
            label = { Text("اكتب ذكرى أو رسالة قصيرة") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Button(onClick = onAdd, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text("حفظ الذكرى")
        }
        Divider(color = Rose.copy(alpha = .25f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ألبومنا المشترك", color = Gold, fontWeight = FontWeight.Bold)
                Text("تُشفّر الصور قبل رفعها. الحد الأقصى 20 ميغابايت للصورة.", color = Color.LightGray, fontSize = 10.sp)
            }
            Button(
                onClick = { imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                enabled = mediaEnabled
            ) { Text("إضافة صورة") }
        }
        if (!mediaEnabled) {
            Text("يتاح الألبوم بعد إعداد الاتصال السحابي ومفتاح العالم.", color = Color.LightGray, fontSize = 10.sp)
        }
        if (mediaStatus.isNotBlank()) Text(mediaStatus, color = Rose, fontSize = 11.sp)
        if (preview != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardTone)
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "صورة من الألبوم المشترك",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        contentScale = ContentScale.Fit
                    )
                    TextButton(onClick = onClosePreview, modifier = Modifier.align(Alignment.End)) {
                        Text("إغلاق الصورة")
                    }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (media.isNotEmpty()) {
                item { Text("الصور المحفوظة", color = Gold, fontWeight = FontWeight.Bold) }
                items(media, key = { "media-" + it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenMedia(item) },
                        colors = CardDefaults.cardColors(containerColor = CardTone),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("▧", color = Gold, fontSize = 23.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "\${item.sizeBytes / 1024} كيلوبايت",
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                            Text("عرض", color = Rose, fontSize = 12.sp)
                            TextButton(
                                onClick = { mediaToDelete = item },
                                enabled = mediaEnabled
                            ) { Text("حذف", color = Rose, fontSize = 11.sp) }
                        }
                    }
                }
            } else {
                item { Text("لم تُضف صور إلى الألبوم بعد.", color = Color.LightGray, fontSize = 11.sp) }
            }
            if (memories.isNotEmpty()) {
                item { Text("الذكريات المكتوبة", color = Gold, fontWeight = FontWeight.Bold) }
                items(memories.indices.toList(), key = { "memory-" + it }) { index ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardTone)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(memories[index], color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDelete(index) }) { Text("حذف", color = Rose) }
                        }
                    }
                }
            } else {
                item { Text("لم تُحفظ ذكريات مكتوبة بعد.", color = Color.LightGray, fontSize = 11.sp) }
            }
        }
    }
    mediaToDelete?.let { selected ->
        AlertDialog(
            onDismissRequest = { mediaToDelete = null },
            title = { Text("حذف الصورة؟") },
            text = { Text("سيُحذف الملف المشفّر من التخزين المشترك، وستُزال بياناته من ألبوم الجهازين.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMedia(selected)
                    mediaToDelete = null
                }) { Text("حذف نهائياً", color = Rose) }
            },
            dismissButton = {
                TextButton(onClick = { mediaToDelete = null }) { Text("إلغاء") }
            }
        )
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
            onValueChange = { onText(it.take(1000)) },
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
    dailyAnswers: List<DailyAnswerEntry>,
    currentAuthorCode: String,
    memberNames: Map<String, String>,
    capsules: List<FutureCapsule>,
    onCreateCapsule: (String, Long) -> Unit,
    onOpenCapsule: (String) -> Unit,
    drawings: List<DrawingStroke>,
    drawingVersions: List<DrawingVersion>,
    onAddStroke: (DrawingStroke) -> Unit,
    onClearDrawing: () -> Unit,
    onUndoDrawing: () -> Unit,
    onSaveDrawingVersion: (String) -> Unit,
    onRestoreDrawingVersion: (String) -> Unit,
    stage: Int,
    relationshipStartDate: String
) {
    var selected by remember { mutableStateOf("الحديقة") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("الحديقة", "سؤال اليوم", "مفاجأة اليوم", "الرحلة", "كبسولة المستقبل", "لوحتنا", "مساحة التفاهم").forEach { item ->
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
                "سؤال اليوم" -> DailyQuestionSection(
                    answer, onAnswer, dailyAnswers, currentAuthorCode, memberNames
                )
                "مفاجأة اليوم" -> DailySurpriseSection()
                "كبسولة المستقبل" -> CapsuleSection(capsules, onCreateCapsule, onOpenCapsule)
                "لوحتنا" -> DrawingSection(
                    drawings, drawingVersions, onAddStroke, onClearDrawing, onUndoDrawing,
                    onSaveDrawingVersion, onRestoreDrawingVersion
                )
                "مساحة التفاهم" -> ReconciliationSection()
                else -> JourneySection(stage, relationshipStartDate)
            }
        }
    }
}

@Composable
private fun ReconciliationSection() {
    var step by remember { mutableStateOf(0) }
    val steps = listOf(
        "توقّفوا قليلاً قبل متابعة النقاش. خذوا وقتاً قصيراً لتهدئة الإيقاع، واتفقوا على أن الهدف هو الفهم لا الانتصار.",
        "ليتحدث الشخص الأول دون مقاطعة، مستخدماً عبارات تبدأ بـ «شعرتُ» أو «احتجتُ». ثم يستمع الآخر حتى النهاية.",
        "يعيد المستمع صياغة ما فهمه بكلماته، ثم يسأل: «هل فهمتك كما قصدت؟». صحّحوا الفهم بهدوء قبل تقديم الحلول.",
        "اختاروا خطوة صغيرة وواضحة يمكن تنفيذها الآن، واتفقوا على وقت مناسب للعودة إلى الموضوع إذا احتاج إلى متابعة."
    )
    val headings = listOf("هدوء قبل الحوار", "استماع بلا مقاطعة", "تأكيد الفهم", "خطوة مشتركة")
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("مساحة التفاهم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("خطوات بسيطة تساعدكما على استعادة الحوار باحترام.", color = Rose, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(steps.size) { index ->
                Box(
                    Modifier.weight(1f).height(5.dp)
                        .background(
                            if (index <= step) Gold else Color.White.copy(alpha = .13f),
                            RoundedCornerShape(8.dp)
                        )
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardTone),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("الخطوة ${step + 1} من ${steps.size}", color = Rose, fontSize = 11.sp)
                Text(headings[step], color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(steps[step], color = Color.White, fontSize = 15.sp, lineHeight = 25.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { step = (step - 1).coerceAtLeast(0) }, enabled = step > 0) {
                        Text("السابق")
                    }
                    Button(onClick = { step = if (step == steps.lastIndex) 0 else step + 1 }) {
                        Text(if (step == steps.lastIndex) "إعادة الخطوات" else "التالي")
                    }
                }
            }
        }
        Text(
            "لا يلزم الوصول إلى اتفاق فوري. يمكن التوقف والعودة إلى الحوار لاحقاً عندما يكون الطرفان مستعدين.",
            color = Color.LightGray,
            fontSize = 11.sp,
            lineHeight = 18.sp
        )
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
    val skyPulse by sway.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900), repeatMode = RepeatMode.Reverse),
        label = "garden_star_twinkle"
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
                    val moon = Offset(size.width * .82f, size.height * .17f)
                    drawCircle(Gold.copy(alpha = .12f * skyPulse), radius = 29f, center = moon)
                    drawCircle(Color(0xFFFFE8B5), radius = 13f, center = moon)
                    drawCircle(CardTone, radius = 12f, center = Offset(moon.x + 7f, moon.y - 4f))
                    val stars = listOf(
                        Offset(.12f, .16f), Offset(.28f, .27f), Offset(.47f, .13f),
                        Offset(.62f, .30f), Offset(.91f, .35f), Offset(.73f, .08f)
                    )
                    stars.forEachIndexed { index, point ->
                        val radius = if (index % 2 == 0) 2.1f else 1.5f
                        drawCircle(
                            Gold.copy(alpha = (.25f + .65f * skyPulse) * (if (index % 3 == 0) .7f else 1f)),
                            radius = radius,
                            center = Offset(point.x * size.width, point.y * size.height)
                        )
                    }
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
private fun DailySurpriseSection() {
    val surprises = DailySurpriseDeck.entries
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val surprise = surprises[DailySurpriseSelector.indexForEpochDay(today.toEpochDay(), surprises.size)]
    val transition = rememberInfiniteTransition(label = "daily_surprise_glow")
    val glow by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), repeatMode = RepeatMode.Reverse),
        label = "daily_surprise_alpha"
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("مفاجأة اليوم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(today.toString(), color = Rose.copy(alpha = .75f), fontSize = 11.sp)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardTone),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("✧", color = Gold.copy(alpha = glow), fontSize = 46.sp)
                Text(surprise.first, color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(surprise.second, color = Color.White, fontSize = 15.sp)
                Text("مفاجأة صغيرة، وذكرى تبقى.", color = Rose, fontSize = 11.sp)
            }
        }
        Text(
            "تتغير المفاجأة يومياً وفق التاريخ الموحد بتوقيت UTC، لتظهر الفكرة نفسها على الجهازين.",
            color = Color.LightGray,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DailyQuestionSection(
    answer: String,
    onAnswer: (String) -> Unit,
    dailyAnswers: List<DailyAnswerEntry>,
    currentAuthorCode: String,
    memberNames: Map<String, String>
) {
    val questions = DailyQuestionDeck.entries
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val todayText = today.toString()
    val question = questions[DailyQuestionSelector.indexForEpochDay(today.toEpochDay(), questions.size)]
    val responses = dailyAnswers.filter { it.date == todayText }.sortedBy { it.createdAtEpochMs }
    var draft by remember(today, answer) { mutableStateOf(answer) }
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("سؤال اليوم", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(todayText, color = Rose.copy(alpha = .75f), fontSize = 11.sp)
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
                ) { Text(if (answer.isBlank()) "حفظ إجابتي" else "تحديث إجابتي") }
            }
        }
        val revealTogether = DailyAnswerReveal.isReady(responses.map { it.authorCode })
        val ownAnswerSaved = responses.any { it.authorCode == currentAuthorCode } || answer.isNotBlank()
        Text("إجاباتكما", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (responses.isEmpty()) {
            Text("لم تُحفظ إجابة لهذا اليوم بعد. يمكن لكل طرف إضافة إجابته الخاصة.", color = Color.LightGray, fontSize = 12.sp)
        } else if (!revealTogether) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✦", color = Gold, fontSize = 30.sp)
                    Text(if (ownAnswerSaved) "إجابتك محفوظة بأمان" else "وصلت إجابة واحدة", color = Rose, fontWeight = FontWeight.SemiBold)
                    Text("ستُكشف الإجابتان معاً عندما يجيب الطرف الآخر. لن تظهر إجابة أي طرف قبل اكتمال الإجابتين.", color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(visible = revealTogether, enter = fadeIn(animationSpec = tween(700)) + scaleIn(animationSpec = tween(700), initialScale = 0.94f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("انكشفت الإجابتان معاً ✧", color = Rose, fontWeight = FontWeight.SemiBold)
                responses.forEach { entry ->
                    val author = memberNames[entry.authorCode] ?: if (entry.authorCode == currentAuthorCode) "إجابتك" else "إجابة الطرف الآخر"
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardTone), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(author, color = Rose, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(entry.answer, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        Text(
            "يحتفظ كل طرف بإجابته المستقلة، وتظهر الإجابتان معاً بعد المزامنة المشفّرة.",
            color = Color.LightGray, fontSize = 11.sp
        )
    }
}

@Composable
private fun DrawingSection(
    strokes: List<DrawingStroke>,
    versions: List<DrawingVersion>,
    onAddStroke: (DrawingStroke) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onSaveVersion: (String) -> Unit,
    onRestoreVersion: (String) -> Unit
) {
    var activeStroke by remember { mutableStateOf<DrawingStroke?>(null) }
    var showScene by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showSaveVersion by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }
    var versionTitle by remember { mutableStateOf("") }
    var restoreVersionPending by remember { mutableStateOf<DrawingVersion?>(null) }
    var selectedColorArgb by remember { mutableLongStateOf(Color(0xFFFF9CCB).value.toLong()) }
    val drawingPalette = listOf(
        "وردي" to Color(0xFFFF9CCB),
        "ذهبي" to Color(0xFFFFD68A),
        "سماوي" to Color(0xFF8FE7FF),
        "بنفسجي" to Color(0xFFC7A6FF),
        "أبيض" to Color(0xFFFFFFFF)
    )
    if (showScene) {
        DrawingSceneSection(strokes, onBack = { showScene = false })
    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val canvasModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(Color(0xFF170F23), RoundedCornerShape(20.dp))
            .pointerInput(strokes, onAddStroke, selectedColorArgb) {
                fun normalizedPoint(position: Offset) = WorldPoint(
                    x = (position.x / size.width).coerceIn(0f, 1f),
                    y = (position.y / size.height).coerceIn(0f, 1f)
                )
                detectDragGestures(
                    onDragStart = { position ->
                        activeStroke = DrawingStroke(
                            id = java.util.UUID.randomUUID().toString(),
                            colorArgb = selectedColorArgb,
                            widthFraction = 0.006f,
                            points = listOf(normalizedPoint(position))
                        )
                    },
                    onDrag = { change, _ ->
                            activeStroke?.let { stroke ->
                            if (stroke.points.size < 150) {
                                activeStroke = stroke.copy(points = stroke.points + normalizedPoint(change.position))
                            }
                        }
                    },
                    onDragEnd = {
                        activeStroke?.let(onAddStroke)
                        activeStroke = null
                    },
                    onDragCancel = { activeStroke = null }
                )
            }
        Text("لوحتنا المشتركة", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("ارسموا معاً؛ تُحفَظ الخطوط وتصل إلى الجهاز الآخر مشفّرة.", color = Rose, fontSize = 12.sp)
        Text("يحتفظ العالم بآخر 8 نسخ آمنة من الرسم؛ تُستبدل النسخة الأقدم عند إضافة نسخة جديدة.", color = Color.LightGray, fontSize = 11.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            drawingPalette.forEach { (label, color) ->
                FilterChip(
                    selected = selectedColorArgb == color.value.toLong(),
                    onClick = { selectedColorArgb = color.value.toLong() },
                    label = { Text(label) }
                )
            }
        }
        Canvas(canvasModifier) {
            (strokes + listOfNotNull(activeStroke)).forEach { stroke ->
                val color = Color(stroke.colorArgb.toULong())
                val points = stroke.points
                if (points.size == 1) {
                    drawCircle(
                        color = color,
                        radius = stroke.widthFraction * size.width / 2f,
                        center = Offset(points[0].x * size.width, points[0].y * size.height)
                    )
                } else {
                    points.zipWithNext().forEach { (from, to) ->
                        drawLine(
                            color = color,
                            start = Offset(from.x * size.width, from.y * size.height),
                            end = Offset(to.x * size.width, to.y * size.height),
                            strokeWidth = stroke.widthFraction * size.width,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${strokes.size} خطاً محفوظاً", color = Color.LightGray, fontSize = 11.sp)
            OutlinedButton(onClick = onUndo, enabled = strokes.isNotEmpty()) {
                Text("تراجع عن آخر خط")
            }
            OutlinedButton(
                onClick = { versionTitle = ""; showSaveVersion = true },
                enabled = strokes.isNotEmpty()
            ) { Text("حفظ نسخة") }
            OutlinedButton(
                onClick = { showVersionHistory = !showVersionHistory },
                enabled = versions.isNotEmpty()
            ) { Text(if (showVersionHistory) "إخفاء السجل" else "سجل النسخ (${versions.size})") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = strokes.isNotEmpty()) {
                Text("مسح اللوحة")
            }
        }
        if (showVersionHistory && versions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                colors = CardDefaults.cardColors(containerColor = CardTone),
                shape = RoundedCornerShape(16.dp)
            ) {
                LazyColumn(contentPadding = PaddingValues(8.dp)) {
                    items(versions.reversed(), key = { it.id }) { version ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(version.title, color = Gold, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${version.strokes.size} خطاً • " +
                                        java.time.Instant.ofEpochMilli(version.createdAtEpochMs)
                                            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                            TextButton(onClick = { restoreVersionPending = version }) {
                                Text("استعادة")
                            }
                        }
                    }
                }
            }
        }
        if (showSaveVersion) {
            AlertDialog(
                onDismissRequest = { showSaveVersion = false },
                title = { Text("حفظ نسخة من اللوحة") },
                text = {
                    OutlinedTextField(
                        value = versionTitle,
                        onValueChange = { versionTitle = it.take(40) },
                        label = { Text("اسم النسخة") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveVersion(versionTitle.trim().ifBlank { "نسخة ${versions.size + 1}" })
                        showSaveVersion = false
                    }) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveVersion = false }) { Text("إلغاء") }
                },
                containerColor = CardTone,
                titleContentColor = Gold,
                textContentColor = Color.White
            )
        }
        restoreVersionPending?.let { version ->
            AlertDialog(
                onDismissRequest = { restoreVersionPending = null },
                title = { Text("استعادة نسخة اللوحة؟") },
                text = { Text("سيحل الرسم المحفوظ محل الرسم الحالي، وستبقى النسخة في سجل الاستعادة.") },
                confirmButton = {
                    TextButton(onClick = {
                        onRestoreVersion(version.id)
                        restoreVersionPending = null
                    }) { Text("استعادة") }
                },
                dismissButton = {
                    TextButton(onClick = { restoreVersionPending = null }) { Text("إلغاء") }
                },
                containerColor = CardTone,
                titleContentColor = Gold,
                textContentColor = Color.White
            )
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("مسح اللوحة؟") },
                text = { Text("سيُحذف الرسم الحالي من العالم المشترك؛ يمكنكم استعادة نسخة محفوظة من سجل اللوحة.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        onClear()
                    }) { Text("مسح") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("إلغاء") }
                },
                containerColor = CardTone,
                titleContentColor = Gold,
                textContentColor = Color.White
            )
        }
        Button(
            onClick = { showScene = true },
            enabled = strokes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("تحويل اللوحة إلى مشهد متحرك") }
    }
}

@Composable
private fun DrawingSceneSection(strokes: List<DrawingStroke>, onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "drawing_scene_motion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200), repeatMode = RepeatMode.Reverse),
        label = "drawing_scene_phase"
    )
    val glow by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), repeatMode = RepeatMode.Reverse),
        label = "drawing_scene_glow"
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مشهد من رسمكما", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("تتحرك الخطوط والنجوم معاً لتمنح رسمكما حياة جديدة.", color = Rose, fontSize = 12.sp)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF211431), Color(0xFF100B1B), Color(0xFF25152E))),
                    RoundedCornerShape(22.dp)
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for (index in 0 until 28) {
                    val x = ((index * 37) % 100) / 100f * size.width
                    val y = (((index * 61f) + phase * 24f) % 100f) / 100f * size.height
                    val radius = 1.2f + (index % 4) * 0.7f
                    drawCircle(
                        color = Gold.copy(alpha = (0.25f + glow * 0.35f).coerceIn(0f, 0.8f)),
                        radius = radius,
                        center = Offset(x, y)
                    )
                }
                strokes.forEach { stroke ->
                    val points = stroke.points
                    val color = Color(stroke.colorArgb.toULong())
                    if (points.size == 1) {
                        drawCircle(
                            color = color.copy(alpha = glow),
                            radius = stroke.widthFraction * size.width * 1.8f,
                            center = Offset(points[0].x * size.width, points[0].y * size.height)
                        )
                    } else {
                        points.zipWithNext().forEach { (from, to) ->
                            val start = Offset(from.x * size.width, from.y * size.height)
                            val end = Offset(to.x * size.width, to.y * size.height)
                            val width = stroke.widthFraction * size.width
                            drawLine(
                                color = color.copy(alpha = 0.16f * glow),
                                start = start,
                                end = end,
                                strokeWidth = width * 3.2f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            drawLine(
                                color = color.copy(alpha = 0.88f),
                                start = start,
                                end = end,
                                strokeWidth = width * (0.94f + phase * 0.08f),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("العودة إلى اللوحة") }
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
    val unlockAt = parsedDate?.atStartOfDay(java.time.ZoneOffset.UTC)
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
            onValueChange = { draft = it.take(800); feedback = "" },
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
            label = { Text("موعد الفتح YYYY-MM-DD بتوقيت UTC") }
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
                            java.time.Instant.ofEpochMilli(capsule.unlockAtEpochMs)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
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
private fun JourneySection(stage: Int, relationshipStartDate: String) {
    val milestones = WorldLimits.journeyMilestones
    val titles = listOf("الشرارة الأولى", "مدارنا", "نجمة مشتركة", "مجرة الذكريات", "عالم لا ينتهي")
    val elapsedDays = daysSinceRelationshipStart(relationshipStartDate)
    val pulse = rememberInfiniteTransition(label = "relationship_counter_pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Reverse),
        label = "relationship_counter_glow"
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("رحلتنا معاً", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(
            if (stage < 0) "أدخلوا تاريخ بداية رحلتكم من الإعدادات لتُفتح المراحل تلقائياً."
            else "تُفتح المراحل تلقائياً عند اكتمال الأيام المحددة.",
            color = Rose, fontSize = 12.sp
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardTone),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("عداد رحلتكما", color = Rose, fontSize = 13.sp)
                if (elapsedDays == null) {
                    Text("ابدؤوا بتحديد تاريخ البداية", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("يمكن ضبطه من صفحة الإعدادات.", color = Color.LightGray, fontSize = 11.sp)
                } else {
                    Text(
                        elapsedDays.toString(),
                        color = Gold.copy(alpha = glow),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (elapsedDays == 1L) "يوم واحد معاً"
                        else if (elapsedDays == 2L) "يومان معاً"
                        else "يوماً معاً في عالمكما",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("منذ $relationshipStartDate", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val feedbackPrefs = remember(context) {
        context.getSharedPreferences("local_feedback_preferences", android.content.Context.MODE_PRIVATE)
    }
    var soundEnabled by remember { mutableStateOf(feedbackPrefs.getBoolean("sound_enabled", true)) }
    var vibrationEnabled by remember { mutableStateOf(feedbackPrefs.getBoolean("vibration_enabled", true)) }
    var feedbackMessage by remember { mutableStateOf("") }
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
                    parsed.isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC)) -> dateMessage = "لا يمكن اختيار تاريخ في المستقبل."
                    else -> {
                        onRelationshipDate(parsed.toString())
                        dateMessage = "تم حفظ تاريخ البداية."
                    }
                }
            },
            enabled = world != null && startDate.isNotBlank()
        ) { Text("حفظ تاريخ البداية") }
        if (dateMessage.isNotBlank()) Text(dateMessage, color = Rose, fontSize = 12.sp)
        Divider(color = Rose.copy(alpha = .25f))
        Text("التنبيهات على هذا الجهاز", color = Gold, fontWeight = FontWeight.Bold)
        Text("هذه الخيارات محلية لهذا الهاتف، ولا تغيّر إعدادات الطرف الآخر.", color = Color.LightGray, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("صوت التنبيه", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("نغمة قصيرة عند اختبار التنبيه", color = Color.LightGray, fontSize = 11.sp)
            }
            Switch(
                checked = soundEnabled,
                onCheckedChange = {
                    soundEnabled = it
                    feedbackPrefs.edit().putBoolean("sound_enabled", it).apply()
                    feedbackMessage = ""
                }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("الاهتزاز", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("اهتزاز خفيف عند اختبار التنبيه", color = Color.LightGray, fontSize = 11.sp)
            }
            Switch(
                checked = vibrationEnabled,
                onCheckedChange = {
                    vibrationEnabled = it
                    feedbackPrefs.edit().putBoolean("vibration_enabled", it).apply()
                    feedbackMessage = ""
                }
            )
        }
        OutlinedButton(
            onClick = {
                var played = false
                if (soundEnabled) {
                    runCatching {
                        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
                        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 300)
                        played = true
                    }
                }
                if (vibrationEnabled) {
                    runCatching {
                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                            as android.os.Vibrator
                        if (vibrator.hasVibrator()) {
                            vibrator.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                            played = true
                        }
                    }
                }
                feedbackMessage = if (played) "تم تشغيل التنبيه التجريبي." else "فعّل الصوت أو الاهتزاز أولاً، وتأكد من إعدادات الهاتف."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("اختبار التنبيه") }
        if (feedbackMessage.isNotBlank()) Text(feedbackMessage, color = Rose, fontSize = 12.sp)
        Text("تُحفظ بيانات العالم مشفّرة على هذا الجهاز، وتُزامَن التغييرات مع الأجهزة المعتمدة عند توفر الاتصال.", color = Color.LightGray, fontSize = 12.sp)
    }
}


private fun decodeMediaPreview(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1200) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun calculateJourneyStage(startDate: String): Int {
    val start = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull() ?: return -1
    val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(start, java.time.LocalDate.now(java.time.ZoneOffset.UTC))
    if (elapsedDays < 0) return -1
    return WorldLimits.journeyMilestones.count { elapsedDays >= it } - 1
}


private fun daysSinceRelationshipStart(startDate: String): Long? {
    val start = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, java.time.LocalDate.now(java.time.ZoneOffset.UTC))
    return days.takeIf { it >= 0 }
}
