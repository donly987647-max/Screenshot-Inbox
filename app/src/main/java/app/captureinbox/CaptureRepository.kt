package app.captureinbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportResult(val capture: Capture, val duplicate: Boolean)

class CaptureRepository(context: Context) {
    private val context = context.applicationContext
    private val folder = File(this.context.filesDir, "captures").apply { mkdirs() }
    private val metadata = AtomicFile(File(this.context.filesDir, "capture-library.json"))

    companion object {
        private val mutex = Mutex()
        private const val MAX_IMAGE = 20L * 1024 * 1024
        private const val MAX_BACKUP = 200L * 1024 * 1024
        private const val MAX_ITEMS = 500
        private val imageNamePattern = Regex("[a-f0-9-]{36}\\.img")
    }

    fun imageFile(capture: Capture): File? = capture.imageName?.let { name ->
        if (!imageNamePattern.matches(name)) null else File(folder, name)
    }

    suspend fun readAll(): List<Capture> = withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }

    private fun readLocked(): List<Capture> {
        if (!metadata.baseFile.exists() && !File(metadata.baseFile.path + ".bak").exists()) return emptyList()
        return try { decode(String(metadata.readFully(), Charsets.UTF_8)) }
        catch (e: Exception) { throw IOException("보관함을 읽지 못했어요. 기존 자료는 변경하지 않았습니다.", e) }
    }

    private fun encode(items: List<Capture>): String {
        val array = JSONArray()
        items.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("text", c.text); put("category", c.category.name)
                put("createdAt", c.createdAt); put("imageName", c.imageName ?: JSONObject.NULL)
                put("date", c.date ?: JSONObject.NULL); put("dateConfirmed", c.dateConfirmed)
                put("remindDays", c.remindDays ?: JSONObject.NULL); put("done", c.done)
                put("price", c.price ?: JSONObject.NULL); put("url", c.url ?: JSONObject.NULL)
                put("location", c.location ?: JSONObject.NULL); put("digest", c.digest ?: JSONObject.NULL)
                put("sample", c.sample); put("warning", c.warning ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("schemaVersion", 1).put("captures", array).toString()
    }

    private fun decode(text: String): List<Capture> {
        require(text.length <= 8 * 1024 * 1024) { "목록 파일이 너무 큽니다." }
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "지원하지 않는 백업 버전입니다." }
        val array = root.getJSONArray("captures")
        require(array.length() <= MAX_ITEMS) { "보관함은 현재 최대 500개까지 지원합니다." }
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            fun nullable(key: String) = if (o.isNull(key)) null else o.getString(key)
            val id = o.getString("id")
            require(id.length in 1..80 && ids.add(id)) { "중복되거나 잘못된 카드 ID입니다." }
            val image = nullable("imageName")
            require(image == null || imageNamePattern.matches(image)) { "잘못된 이미지 경로입니다." }
            val date = nullable("date")
            require(date == null || CaptureParser.parseDateStrict(date) != null) { "잘못된 날짜입니다." }
            val remind = if (o.isNull("remindDays")) null else o.getInt("remindDays")
            require(remind == null || remind in setOf(0, 1, 3)) { "잘못된 알림 설정입니다." }
            val title = o.getString("title")
            val body = o.getString("text")
            require(title.length in 1..160 && body.length <= 100_000) { "카드 내용이 너무 길거나 비어 있습니다." }
            val confirmed = date != null && o.optBoolean("dateConfirmed")
            Capture(id, title, body, Category.valueOf(o.getString("category")), o.getLong("createdAt"),
                image, date, confirmed, remind.takeIf { confirmed }, o.optBoolean("done"),
                nullable("price")?.take(100), CaptureParser.safeUrl(nullable("url")), nullable("location")?.take(500),
                nullable("digest")?.takeIf { Regex("[a-f0-9]{64}").matches(it) }, o.optBoolean("sample"), nullable("warning")?.take(1000))
        }
    }

    private fun writeLocked(items: List<Capture>) {
        require(items.size <= MAX_ITEMS) { "최대 500개입니다. 완료한 카드를 백업 후 정리해 주세요." }
        val bytes = encode(items).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "목록 용량 제한을 초과했어요. 긴 메모를 정리해 주세요." }
        val output = metadata.startWrite()
        try { output.write(bytes); metadata.finishWrite(output) }
        catch (e: Exception) { metadata.failWrite(output); throw IOException("저장하지 못했어요. 저장 공간을 확인해 주세요.", e) }
    }

    suspend fun save(capture: Capture) = withContext(Dispatchers.IO) {
        require(capture.title.isNotBlank() && capture.title.length <= 160 && capture.text.length <= 100_000) { "제목 또는 내용의 길이를 확인해 주세요." }
        require(capture.date == null || CaptureParser.parseDateStrict(capture.date) != null) { "날짜는 YYYY-MM-DD 형식으로 입력해 주세요." }
        require(capture.remindDays == null || capture.remindDays in setOf(0, 1, 3))
        require(capture.imageName == null || imageNamePattern.matches(capture.imageName))
        val clean = capture.copy(
            dateConfirmed = capture.date != null && capture.dateConfirmed,
            remindDays = capture.remindDays.takeIf { capture.date != null && capture.dateConfirmed && !capture.done },
            url = CaptureParser.safeUrl(capture.url)
        )
        mutex.withLock {
            val items = readLocked().toMutableList()
            val index = items.indexOfFirst { it.id == clean.id }
            if (index < 0) items.add(0, clean) else items[index] = clean
            writeLocked(items)
        }
        ReminderScheduler.refresh(context, clean)
    }

    suspend fun delete(capture: Capture) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = readLocked()
            writeLocked(items.filterNot { it.id == capture.id })
            imageFile(capture)?.delete()
        }
        ReminderScheduler.cancel(context, capture.id)
    }

    private fun decodeBitmap(file: File): Bitmap {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, probe)
        require(probe.outWidth > 0 && probe.outHeight > 0 && probe.outWidth.toLong() * probe.outHeight <= 80_000_000L) { "이미지 크기 또는 형식을 지원하지 않아요." }
        var sample = 1
        while (maxOf(probe.outWidth, probe.outHeight) / sample > 2560) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("이미지를 읽지 못했어요.")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val file = File(folder, "${UUID.randomUUID()}.img")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        total += size
                        require(total <= MAX_IMAGE) { "이미지는 한 장당 20MB까지 가져올 수 있어요." }
                        digest.update(buffer, 0, size); output.write(buffer, 0, size)
                    }
                }
            } ?: throw IOException("선택한 이미지에 접근하지 못했어요.")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = mutex.withLock { readLocked().firstOrNull { it.digest == hash } }
            if (existing != null) { file.delete(); return@withContext ImportResult(existing, true) }
            val bitmap = decodeBitmap(file)
            var ocrWarning: String? = null
            val text = try {
                val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                try { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text }
                finally { recognizer.close() }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { ocrWarning = "글자 인식에 실패했어요. 원본은 저장했으니 직접 수정할 수 있어요."; "" }
              finally { bitmap.recycle() }
            val result = CaptureParser.parse(text)
            val capture = Capture(title = result.title, text = result.text, category = result.category,
                imageName = file.name, date = result.date, price = result.price, url = result.url,
                location = result.location, digest = hash, warning = ocrWarning ?: result.warning)
            mutex.withLock {
                val items = readLocked()
                val duplicate = items.firstOrNull { it.digest == hash }
                if (duplicate != null) { file.delete(); return@withLock ImportResult(duplicate, true) }
                writeLocked(listOf(capture) + items)
                ImportResult(capture, false)
            }
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = readLocked()
            val files = items.mapNotNull { c -> imageFile(c)?.also { require(it.isFile) { "누락된 원본 이미지가 있어 백업을 중단했어요." } } }.distinctBy { it.name }
            val metadataBytes = encode(items).toByteArray(Charsets.UTF_8)
            require(files.sumOf { it.length() } + metadataBytes.size <= MAX_BACKUP) { "백업은 현재 200MB까지 지원해요." }
            val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("백업 파일을 만들지 못했어요.")
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("library.json")); zip.write(metadataBytes); zip.closeEntry()
                files.forEach { file -> zip.putNextEntry(ZipEntry("images/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
    }

    suspend fun restoreFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val addedFiles = mutableListOf<File>()
        try {
            val names = mutableSetOf<String>(); var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("백업 파일을 열지 못했어요.")
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name) && names.size <= MAX_ITEMS + 1) { "중복되거나 지원하지 않는 ZIP 항목입니다." }
                    val isMetadata = entry.name == "library.json"
                    require(isMetadata || (entry.name.startsWith("images/") && imageNamePattern.matches(entry.name.removePrefix("images/")))) { "안전하지 않은 백업 경로입니다." }
                    val destination = File(staging, if (isMetadata) "library.json" else entry.name.removePrefix("images/"))
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192); var entrySize = 0L
                        while (true) {
                            val size = zip.read(buffer); if (size < 0) break
                            total += size; entrySize += size
                            require(total <= MAX_BACKUP && entrySize <= if (isMetadata) 8L * 1024 * 1024 else MAX_IMAGE) { "백업 크기 제한을 초과했어요." }
                            output.write(buffer, 0, size)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val metadataFile = File(staging, "library.json")
            require(metadataFile.isFile) { "캡처함 백업 파일이 아니에요." }
            val incoming = decode(metadataFile.readText())
            incoming.forEach { c -> c.imageName?.let { require(File(staging, it).isFile) { "원본 이미지가 누락된 백업이에요." } } }
            mutex.withLock {
                val current = readLocked()
                val hashes = current.mapNotNull { it.digest }.toMutableSet()
                val keys = current.map { "${it.title}\u0000${it.text}\u0000${it.date}" }.toMutableSet()
                val fresh = incoming.filter { c ->
                    if (c.digest != null) hashes.add(c.digest) else keys.add("${c.title}\u0000${c.text}\u0000${c.date}")
                }.map { c ->
                    val id = UUID.randomUUID().toString()
                    val newImage = c.imageName?.let { old ->
                        val destination = File(folder, "$id.img")
                        addedFiles += destination
                        File(staging, old).copyTo(destination)
                        destination.name
                    }
                    c.copy(id = id, imageName = newImage, remindDays = null)
                }
                writeLocked((fresh + current).sortedByDescending { it.createdAt })
                addedFiles.clear()
                fresh.size
            }
        } finally { addedFiles.forEach(File::delete); staging.deleteRecursively() }
    }

    suspend fun seedExamples() {
        if (readAll().any { it.sample }) return
        val date = LocalDate.now().plusDays(3).toString()
        listOf(
            Capture(title = "모닝브루 아메리카노", text = "모닝브루 커피 쿠폰\n유효기간: $date\n※ 실제 사용 가능한 쿠폰이 아닌 예시입니다.", category = Category.COUPON, date = date, sample = true, warning = "예시 카드예요. 날짜 확인 흐름을 시험해 보세요."),
            Capture(title = "주말 사진 전시", text = "사진 전시회\n일시: ${LocalDate.now().plusDays(7)}\n장소: 가상 갤러리\n※ 예시입니다.", category = Category.EVENT, date = LocalDate.now().plusDays(7).toString(), location = "가상 갤러리", sample = true),
            Capture(title = "가벼운 데일리 백팩", text = "가벼운 데일리 백팩\n판매가 59,000원\n※ 예시입니다.", category = Category.PRODUCT, price = "59,000원", sample = true),
            Capture(title = "저장해 둔 작은 카페", text = "다음에 가보고 싶은 카페\n※ 예시입니다.", category = Category.PLACE, sample = true)
        ).forEach { save(it) }
    }

    suspend fun deleteExamples() { readAll().filter { it.sample }.forEach { delete(it) } }
}
