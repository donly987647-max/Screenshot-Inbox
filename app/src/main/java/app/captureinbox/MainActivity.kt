package app.captureinbox

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneOffset

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CaptureRepository(application)
    var items by mutableStateOf<List<Capture>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var notice by mutableStateOf<String?>(null)
    var selectedId by mutableStateOf<String?>(null)
    var draft by mutableStateOf<Capture?>(null)
    var pendingPermission by mutableStateOf<Capture?>(null)
    var loadFailed by mutableStateOf(false); private set
    private val jobs = Mutex()
    init { reload() }
    private fun action(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            jobs.withLock {
                busy = true; progress = label
                try { block(); items = repository.readAll(); loadFailed = false }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "처리하지 못했어요. 다시 시도해 주세요." }
                finally { busy = false; progress = "" }
            }
        }
    }
    fun reload() = action("보관함을 여는 중") {
        try { items = repository.readAll() } catch (e: Exception) { loadFailed = true; throw e }
    }
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        action("이미지를 가져오는 중") {
            var added = 0; var duplicate = 0; var failed = 0; var lastError: String? = null
            uris.take(20).forEachIndexed { index, uri ->
                progress = "${index + 1}/${minOf(uris.size, 20)} · 기기에서 글자를 읽는 중"
                try {
                    val result = repository.import(uri)
                    if (result.duplicate) duplicate++ else added++
                    selectedId = result.capture.id
                    items = repository.readAll()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { failed++; lastError = e.message }
            }
            notice = "${added}개 저장 · ${duplicate}개 중복" + (if (failed > 0) " · ${failed}개 실패: ${lastError.orEmpty()}" else "") + (if (uris.size > 20) " · 한 번에 20개까지 선택해 주세요." else "")
        }
    }
    fun save(c: Capture, message: String = "저장했어요.") = action("저장하는 중") { repository.save(c); selectedId = null; draft = null; notice = message }
    fun toggle(c: Capture) = action("상태를 변경하는 중") { repository.save(c.copy(done = !c.done)); notice = if (c.done) "보관함으로 되돌렸어요." else "사용 완료로 표시했어요." }
    fun delete(c: Capture) = action("삭제하는 중") { repository.delete(c); selectedId = null; draft = null; notice = "캡처함에서 삭제했어요. 갤러리 원본은 그대로예요." }
    fun examples() = action("예시를 준비하는 중") { repository.seedExamples(); notice = "실제 쿠폰이나 상품이 아닌 예시 카드예요." }
    fun removeExamples() = action("예시를 정리하는 중") { repository.deleteExamples(); notice = "예시 카드를 삭제했어요." }
    fun export(uri: Uri) = action("원본을 포함해 백업하는 중") { repository.exportTo(uri); notice = "ZIP 백업을 저장했어요. 민감한 이미지가 포함될 수 있으니 안전하게 보관해 주세요." }
    fun restore(uri: Uri) = action("백업을 확인하는 중") { val count = repository.restoreFrom(uri); notice = "${count}개를 추가했어요. 기존 자료는 유지되며 알림은 다시 설정해 주세요." }
}

class MainActivity : ComponentActivity() {
    private val model: InboxViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CaptureTheme { InboxApp(model) } }
        if (savedInstanceState == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        intent.getStringExtra("capture_id")?.let { model.selectedId = it; intent.removeExtra("capture_id") }
        if (intent.type?.startsWith("image/") != true) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toList().orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { model.importImages(uris); intent.action = Intent.ACTION_MAIN; intent.removeExtra(Intent.EXTRA_STREAM) }
    }
}

@Composable
private fun CaptureTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF9FD9C6), onPrimary = Color(0xFF113C30), primaryContainer = Color(0xFF204F41),
        background = Color(0xFF101916), surface = Color(0xFF17231E), surfaceVariant = Color(0xFF24362D),
        onSurface = Color(0xFFE8EFEA), onSurfaceVariant = Color(0xFFACBDB3), outlineVariant = Color(0xFF31483B)
    ) else lightColorScheme(
        primary = Color(0xFF267A66), onPrimary = Color.White, primaryContainer = Color(0xFFDCEFE6),
        background = Color(0xFFF6F8F4), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEBF0E9),
        onSurface = Color(0xFF1C3026), onSurfaceVariant = Color(0xFF67766C), outlineVariant = Color(0xFFDDE5DD)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private fun Category.icon(): ImageVector = when (this) {
    Category.COUPON -> Icons.Outlined.LocalOffer
    Category.EVENT -> Icons.Outlined.Event
    Category.PRODUCT -> Icons.Outlined.ShoppingBag
    Category.PLACE -> Icons.Outlined.Place
    Category.NOTE -> Icons.Outlined.Description
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxApp(model: InboxViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var completed by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { model.importImages(it) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::export) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::restore) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.pendingPermission?.let { model.save(if (granted) it else it.copy(remindDays = null), if (granted) "알림을 설정하고 저장했어요." else "알림이 허용되지 않아 알림 없이 저장했어요.") }
        model.pendingPermission = null
    }
    fun pick() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(model.notice) { model.notice?.let { message -> snackbar.showSnackbar(message); if (model.notice == message) model.notice = null } }
    val today = LocalDate.now()
    val upcoming = model.items.filter { it.isUpcoming(today) }
    val filtered = model.items.filter { c ->
        (if (tab == 1) c.isUpcoming(today) else c.done == completed) &&
            (category == null || c.category.name == category) &&
            (search.isBlank() || listOfNotNull(c.title, c.text, c.price, c.location, c.date).any { it.contains(search.trim(), ignoreCase = true) })
    }.let { list -> if (tab == 1) list.sortedBy { it.date } else list.sortedByDescending { it.createdAt } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("보관함" to Icons.Outlined.PhotoLibrary, "다가오는" to Icons.Outlined.CalendarToday, "설정" to Icons.Outlined.Settings).forEachIndexed { index, pair ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(pair.second, null) }, label = { Text(pair.first) })
                }
            }
        },
        floatingActionButton = {
            if (tab != 2 && !model.loadFailed) ExtendedFloatingActionButton(onClick = { if (!model.busy) pick() }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("스크린샷 추가") })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(model.progress, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium) }
            if (tab == 2) {
                SettingsScreen(model, onExport = { exporter.launch("CaptureInbox-${LocalDate.now()}.zip") }, onImport = { importer.launch(arrayOf("application/zip", "application/octet-stream")) })
            } else {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(if (tab == 0) "캡처함" else "다가오는 순간", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${if (tab == 0) model.items.size else upcoming.size}개", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (tab == 0) "저장한 순간을, 다시 쓸 순간으로." else "날짜를 확인하고 필요한 순간에 꺼내 쓰세요.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    if (model.loadFailed) {
                        Text("보관함을 읽지 못했어요. 기존 자료를 보호하기 위해 새 저장을 중단했습니다.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::reload) { Text("다시 시도") }
                    }
                    OutlinedTextField(search, { search = it.take(200) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true,
                        placeholder = { Text("쿠폰, 장소, 캡처 속 글자 검색", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } })
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        item { FilterChip(selected = category == null && !completed, onClick = { category = null; completed = false }, label = { Text("전체") }) }
                        items(Category.entries) { item -> FilterChip(selected = category == item.name, onClick = { category = if (category == item.name) null else item.name }, label = { Text(item.label) }) }
                        if (tab == 0) item { FilterChip(selected = completed, onClick = { completed = !completed }, label = { Text("사용 완료") }) }
                    }
                }
                if (filtered.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary) }
                        Text(if (model.items.isEmpty()) "캡처한 다음엔, 캡처함에." else "여기에 표시할 캡처가 없어요", Modifier.padding(top = 24.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(if (model.items.isEmpty()) "쿠폰, 약속, 사고 싶은 것.\n스크린샷 한 장에서 필요한 내용을 꺼내요." else "다른 분류나 검색어를 확인해 보세요.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        if (model.items.isEmpty() && !model.loadFailed) {
                            Button(onClick = { pick() }, enabled = !model.busy, modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp)) { Text("첫 스크린샷 가져오기") }
                            TextButton(onClick = model::examples, enabled = !model.busy) { Text("예시로 둘러보기") }
                        }
                        Spacer(Modifier.height(72.dp))
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(filtered, key = { it.id }) { c -> CaptureCard(c, model.repository, onClick = { model.selectedId = c.id }) }
                    }
                }
            }
        }
    }
    val current = model.draft ?: model.items.firstOrNull { it.id == model.selectedId }
    if (current != null) {
        CaptureEditor(current, model.repository, model.busy,
            onDismiss = { model.selectedId = null; model.draft = null },
            onSave = { c ->
                if (c.remindDays != null && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    model.pendingPermission = c; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else model.save(c)
            }, onDelete = { model.delete(current) }, onToggle = { model.toggle(current) }, onNotice = { model.notice = it })
    }
}

@Composable
private fun CaptureCard(c: Capture, repository: CaptureRepository, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(148.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (c.imageName != null) AsyncImage(repository.imageFile(c), contentDescription = "${c.title} 원본 미리보기", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                Icon(c.category.icon(), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(c.price ?: if (c.category == Category.COUPON) "KEEP IT.\nUSE IT." else c.category.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            if (c.sample) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) { Text("예시", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp) }
        }
        Column(Modifier.padding(14.dp)) {
            Text(c.category.label, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(c.title, Modifier.padding(top = 5.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(c.price ?: c.location ?: c.date ?: "필요할 때 다시 꺼내 보세요", Modifier.padding(top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.date != null || c.done) Surface(Modifier.padding(top = 12.dp), shape = RoundedCornerShape(8.dp), color = if (c.dateConfirmed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Text(c.dueLabel(), Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CaptureEditor(c: Capture, repository: CaptureRepository, busy: Boolean, onDismiss: () -> Unit, onSave: (Capture) -> Unit, onDelete: () -> Unit, onToggle: () -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var title by rememberSaveable(c.id) { mutableStateOf(c.title) }
    var body by rememberSaveable(c.id) { mutableStateOf(c.text) }
    var price by rememberSaveable(c.id) { mutableStateOf(c.price.orEmpty()) }
    var date by rememberSaveable(c.id) { mutableStateOf(c.date.orEmpty()) }
    var confirmed by rememberSaveable(c.id) { mutableStateOf(c.dateConfirmed) }
    var reminder by rememberSaveable(c.id) { mutableIntStateOf(c.remindDays ?: -1) }
    var category by rememberSaveable(c.id) { mutableStateOf(c.category.name) }
    var location by rememberSaveable(c.id) { mutableStateOf(c.location.orEmpty()) }
    var url by rememberSaveable(c.id) { mutableStateOf(c.url.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    val validDate = CaptureParser.parseDateStrict(date.trim())
    val canRemind = confirmed && validDate != null && !validDate.isBefore(LocalDate.now()) && !c.done
    fun launchExternal(intent: Intent) { try { context.startActivity(intent) } catch (_: Exception) { onNotice("이 작업을 열 수 있는 앱이 없어요.") } }
    fun save() {
        if (title.isBlank()) { error = "제목을 입력해 주세요."; return }
        if (date.isNotBlank() && validDate == null) { error = "날짜는 실제 존재하는 YYYY-MM-DD 형식이어야 해요."; return }
        if (url.isNotBlank() && CaptureParser.safeUrl(url.trim()) == null) { error = "http 또는 https로 시작하는 올바른 링크를 입력해 주세요."; return }
        onSave(c.copy(title = title.trim(), text = body, price = price.trim().ifEmpty { null }, category = Category.valueOf(category), date = date.trim().ifEmpty { null }, dateConfirmed = confirmed && validDate != null,
            remindDays = reminder.takeIf { it >= 0 && canRemind }, location = location.trim().ifEmpty { null }, url = url.trim().ifEmpty { null }, warning = if (confirmed) null else c.warning))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxHeight(.95f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("닫기") }
                Text("캡처 상세", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Button(onClick = ::save, enabled = !busy) { Text("저장") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (c.imageName != null) OriginalPreview(repository.imageFile(c))
                if (c.sample) Text("이 카드는 앱 사용 흐름을 보여 주는 예시예요. 실제 사용 가능한 쿠폰이나 예약이 아닙니다.", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                c.warning?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Category.entries.forEach { item -> FilterChip(selected = category == item.name, onClick = { category = item.name }, label = { Text(item.label) }) } }
                OutlinedTextField(date, { date = it.take(10); confirmed = false; reminder = -1 }, label = { Text("사용일 / 만료일") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), isError = date.isNotBlank() && validDate == null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it; if (!it) reminder = -1 }, enabled = validDate != null)
                    Text("원본과 날짜를 확인했어요", fontSize = 14.sp)
                }
                Text("알림", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1 to "없음", 0 to "당일", 1 to "1일 전", 3 to "3일 전").forEach { (days, label) -> FilterChip(selected = reminder == days, onClick = { reminder = days }, enabled = days == -1 || canRemind, label = { Text(label) }) }
                }
                Text("확인한 날짜의 오전 9시를 기준으로 예약해요. 이미 지난 알림 시점은 잠시 후 알립니다. 절전·알림 설정에 따라 지연되거나 차단될 수 있어요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (confirmed && validDate != null) OutlinedButton(onClick = {
                    launchExternal(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.Events.DESCRIPTION, body.take(5000))
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, validDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, validDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
                }) { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("캘린더에 추가") }
                OutlinedTextField(price, { price = it.take(100) }, label = { Text("가격 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(location, { location = it.take(500) }, label = { Text("장소 (선택)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(url, { url = it.take(2048) }, label = { Text("링크 (선택)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (CaptureParser.safeUrl(url.trim()) != null) TextButton(onClick = { launchExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))) }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("링크 열기") }
                OutlinedTextField(body, { body = it.take(100_000) }, label = { Text("인식한 글자 / 메모") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, shape = RoundedCornerShape(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(body)); onNotice("글자를 복사했어요.") }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("글자 복사") }
                    if (c.imageName != null) OutlinedButton(onClick = {
                        val file = repository.imageFile(c)
                        if (file == null || !file.exists()) onNotice("원본 이미지를 찾지 못했어요.") else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = ClipData.newRawUri("캡처", uri)
                            launchExternal(Intent.createChooser(intent, "원본 공유"))
                        }
                    }) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("원본 공유") }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { deleteDialog = true }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("삭제") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle, enabled = !busy) { Icon(Icons.Outlined.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text(if (c.done) "보관함으로" else "사용 완료") }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("이 캡처를 삭제할까요?") }, text = { Text("캡처함에 저장된 카드와 복사본을 삭제합니다. 갤러리 원본은 삭제하지 않아요.") }, confirmButton = { TextButton(onClick = { deleteDialog = false; onDelete() }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } })
}

@Composable
private fun SettingsScreen(model: InboxViewModel, onExport: () -> Unit, onImport: () -> Unit) {
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmSamples by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("내 캡처, 내 기기에.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Screenshot Inbox · 0.1.0 개발 버전", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, null)
                Text("스크린샷을 서버로 보내지 않아요", fontWeight = FontWeight.SemiBold)
                Text("선택하거나 공유한 이미지만 가져와 이 기기에서 글자를 인식합니다. 로그인, 광고, AI API 결제 없이 사용할 수 있어요.", fontSize = 14.sp)
            }
        }
        Text("보관함 백업", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text("앱을 삭제하거나 앱 데이터를 지우면 보관함이 사라집니다. 원본 이미지가 포함된 ZIP 백업을 안전한 곳에 저장하세요. 백업 파일 자체는 암호화되지 않습니다.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExport, enabled = !model.busy && !model.loadFailed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(10.dp)); Text("원본 포함 ZIP 백업") }
        OutlinedButton(onClick = { confirmRestore = true }, enabled = !model.busy && !model.loadFailed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(10.dp)); Text("백업에서 추가하기") }
        Text("첫 버전은 최대 500개 카드, 이미지 한 장 20MB, 백업 200MB까지 지원합니다. 복원은 기존 자료를 지우지 않으며, 중복 원본은 건너뜁니다. 복원한 카드의 알림은 다시 설정해야 합니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("알아두세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text("자동 분류는 글자 인식과 규칙을 사용합니다. 이미지의 의미를 모두 이해하는 AI는 아니며 날짜·가격을 잘못 읽을 수 있습니다. 중요한 기한은 원본과 직접 확인하세요. 알림은 기기의 절전 상태와 권한에 영향을 받습니다.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { model.draft = Capture(title = "새 메모", text = "") }, enabled = !model.busy && !model.loadFailed) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp)); Text("직접 입력해서 카드 만들기") }
        TextButton(onClick = model::examples, enabled = !model.busy && !model.loadFailed) { Text("예시 카드 추가") }
        if (model.items.any { it.sample }) TextButton(onClick = { confirmSamples = true }, enabled = !model.busy) { Text("예시 카드만 삭제") }
        Text("정식 출시 전: 다양한 기기의 인식 품질, 알림 전달, 접근성 검증이 필요합니다. 이 버전에는 결제 기능이 없습니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, title = { Text("백업에서 캡처를 추가합니다") }, text = { Text("기존 자료는 유지합니다. 신뢰할 수 있는 캡처함 ZIP 백업만 선택해 주세요. 복원한 카드의 알림은 다시 설정해야 합니다.") }, confirmButton = { TextButton(onClick = { confirmRestore = false; onImport() }) { Text("파일 선택") } }, dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("취소") } })
    if (confirmSamples) AlertDialog(onDismissRequest = { confirmSamples = false }, title = { Text("예시 카드를 삭제할까요?") }, text = { Text("예시로 추가된 카드만 삭제합니다. 수정한 예시 카드도 포함됩니다.") }, confirmButton = { TextButton(onClick = { confirmSamples = false; model.removeExamples() }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { confirmSamples = false }) { Text("취소") } })
}
