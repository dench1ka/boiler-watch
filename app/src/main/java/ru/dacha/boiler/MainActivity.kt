package ru.dacha.boiler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Three shallow destinations; settings views stay alive so navigation preserves drafts. */
class MainActivity : Activity() {
    private lateinit var config: Settings
    private lateinit var statusTitle: TextView
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var details: TextView
    private lateinit var emptyPhoto: TextView
    private lateinit var settingsNotice: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var editStopButton: Button
    private lateinit var saveButton: Button
    private val pages = linkedMapOf<String, ScrollView>()
    private val tabs = linkedMapOf<String, Button>()
    private data class Section(val label: String, val header: Button, val content: LinearLayout)
    private val sections = linkedMapOf<String, Section>()
    private val editingButtons = mutableListOf<Button>()
    private var currentPage = "home"
    private var restoredState: Bundle? = null
    private var nextControlId = 1000
    private val accent = Color.rgb(23, 109, 93)
    private val ink = Color.rgb(25, 35, 32)
    private lateinit var image: ImageView
    private lateinit var column: LinearLayout
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var ssl: CheckBox
    private lateinit var front: CheckBox
    private lateinit var flash: CheckBox
    private lateinit var rotation: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private var shownPhoto = ""
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 3000) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(label: String, parent: LinearLayout = column, size: Float = 16f): TextView = TextView(this).apply {
        text = label; textSize = size; setTextColor(ink); setPadding(0, dp(8), 0, dp(6)); parent.addView(this)
    }
    private fun heading(label: String, parent: LinearLayout = column) = text(label, parent, 21f).apply {
        setTypeface(null, Typeface.BOLD); setPadding(0, dp(22), 0, dp(8))
    }
    private fun button(label: String, parent: LinearLayout = column, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 17f; isAllCaps = false; minHeight = dp(56)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(230, 238, 234)); setTextColor(ink)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2)); setOnClickListener { action() }
    }
    private fun primary(button: Button) = button.apply {
        backgroundTintList = ColorStateList.valueOf(accent); setTextColor(Color.WHITE)
    }
    private fun field(key: String, label: String, value: String, parent: LinearLayout = column, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val labelView = text(label, parent)
        return EditText(this).apply {
            id = nextControlId++; tag = key; setText(value); inputType = type
            labelView.labelFor = id
            isSaveEnabled = key != "password"
            setSingleLine(true); textSize = 18f; minHeight = dp(52)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); fields[key] = this
        }
    }
    private fun check(label: String, value: Boolean, parent: LinearLayout = column) = CheckBox(this).apply {
        id = nextControlId++; text = label; textSize = 18f; isChecked = value; minHeight = dp(56); parent.addView(this)
    }
    private fun card(parent: LinearLayout = column) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.rgb(217, 227, 221))
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun page(key: String, parent: FrameLayout): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20))
            isFocusable = true; isFocusableInTouchMode = true
        }
        pages[key] = ScrollView(this).apply {
            id = 3000 + pages.size; tag = "page_$key"; isFillViewport = true; addView(body)
            parent.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        return body
    }
    private fun section(parent: LinearLayout, key: String, label: String): LinearLayout {
        val box = card(parent)
        val header = button(label, box) {
            val open = sections.getValue(key).content.visibility != View.VISIBLE
            sections.keys.forEach { setSectionOpen(it, it == key && open) }
        }.apply { tag = "section_$key"; setTypeface(null, Typeface.BOLD); gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val content = LinearLayout(this).apply { tag = "section_content_$key"; orientation = LinearLayout.VERTICAL; box.addView(this) }
        sections[key] = Section(label, header, content)
        setSectionOpen(key, restoredState?.getBoolean("section_$key", false) ?: false)
        return content
    }
    private fun setSectionOpen(key: String, open: Boolean) {
        val section = sections.getValue(key)
        section.content.visibility = if (open) View.VISIBLE else View.GONE
        section.header.text = "${section.label}  ${if (open) "−" else "+"}"
        section.header.contentDescription = "${section.label}. ${if (open) "Свернуть раздел" else "Развернуть раздел"}"
    }
    private fun showPage(key: String) {
        val target = if (key in pages) key else "home"
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        currentPage = target
        pages.forEach { (id, view) -> view.visibility = if (id == target) View.VISIBLE else View.GONE }
        tabs.forEach { (id, button) ->
            button.isSelected = id == target
            button.backgroundTintList = ColorStateList.valueOf(if (id == target) accent else Color.WHITE)
            button.setTextColor(if (id == target) Color.WHITE else ink)
            button.setTypeface(null, if (id == target) Typeface.BOLD else Typeface.NORMAL)
        }
        saveButton.visibility = if (target == "settings") View.VISIBLE else View.GONE
        pages.getValue(target).getChildAt(0).requestFocus()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Settings(this); restoredState = savedInstanceState
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Color.rgb(243, 246, 244))
        }
        setContentView(root)
        text("Котёл под присмотром", root, 22f).apply {
            setTypeface(null, Typeface.BOLD); setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        val content = FrameLayout(this).also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        column = page("home", content); buildHome()
        column = page("settings", content); buildSettings()
        column = page("help", content); buildHelp()
        saveButton = primary(button("Сохранить настройки", root) { if (save()) { refresh(); showPage("home") } })
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(6)); setBackgroundColor(Color.WHITE)
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        for ((key, label) in listOf("home" to "Главная", "settings" to "Настройки", "help" to "Помощь")) {
            tabs[key] = Button(this).apply {
                tag = "tab_$key"; text = label; textSize = 15f; isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = dp(56); setPadding(dp(4), 0, dp(4), 0)
                navigation.addView(this, LinearLayout.LayoutParams(0, -2, 1f)); setOnClickListener { showPage(key) }
            }
        }
        refresh(); showPage(savedInstanceState?.getString("page") ?: "home")
        restoredState = null
    }
    private fun buildHome() {
        val state = card()
        statusTitle = text("", state, 20f).apply { setTypeface(null, Typeface.BOLD) }
        status = text("", state)
        warning = text("", state).apply { setTextColor(Color.rgb(151, 46, 28)) }
        startButton = primary(button("Включить наблюдение") { enable() })
        stopButton = button("Остановить наблюдение") { stopMonitoring() }
        button("Снять и отправить сейчас") {
            if (!config.enabled && !save()) return@button
            if (permission()) startMonitor(manual = true)
        }
        heading("Последний снимок")
        emptyPhoto = text("Снимка пока нет. Проверьте камеру в разделе «Настройки → Камера».")
        image = ImageView(this).apply {
            contentDescription = "Последний снимок котла"; adjustViewBounds = true; visibility = View.GONE
            column.addView(this, LinearLayout.LayoutParams(-1, dp(200)))
        }
        text("Расписание и почта — в «Настройках». Инструкции и журнал — в «Помощи».")
    }
    private fun buildSettings() {
        val page = column
        settingsNotice = text("")
        editStopButton = button("Остановить и настроить") { stopMonitoring() }
        column = section(page, "schedule", "Расписание")
        field("times", "Каждый день в указанное время", config.times)
        editingButtons += button("Добавить время съёмки") {
            pickTime { time ->
                val current = fields.getValue("times").text.toString().trim()
                val combined = if (current.isEmpty()) time else "$current, $time"
                try {
                    fields.getValue("times").setText(Schedule.parse(combined).joinToString(", ") { (h, m) -> String.format(Locale.ROOT, "%02d:%02d", h, m) })
                } catch (_: Exception) { toast("Исправьте расписание. Например: 09:00, 18:30") }
            }
        }
        text("Можно несколько времён: 09:00, 18:30. Используется время телефона.")
        column = section(page, "delivery", "Электронная почта")
        field("host", "SMTP-сервер", config.host, column, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        field("port", "Порт SMTP", config.port.toString(), column, InputType.TYPE_CLASS_NUMBER)
        ssl = check("TLS сразу при подключении (обычно порт 465)", config.ssl, column)
        text("Без галочки — обязательный STARTTLS, обычно порт 587.", column)
        field("user", "Логин SMTP", config.user, column, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("password", "Пароль приложения (пусто — оставить прежний)", "", column, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field("from", "Адрес отправителя (пусто — взять из логина)", config.from, column, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("to", "Адрес получателя", config.to, column, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        column = section(page, "camera", "Камера")
        text("Снимки в максимальном разрешении выбранной камеры, качество JPEG 100. На почту отправляется исходный файл.")
        front = check("Использовать переднюю камеру", config.front)
        flash = check("Вспышка при съёмке", config.flash)
        text("Поворот фотографии")
        rotation = Spinner(this).apply {
            id = nextControlId++
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("0°", "90°", "180°", "270°"))
            setSelection(config.rotation / 90); minimumHeight = dp(52); column.addView(this)
        }
        button("Пробный снимок без отправки") {
            if (!config.enabled && MonitorService.instance?.isBusy != true) saveCamera()
            if (permission()) { startMonitor(manual = true, preview = true); showPage("home") }
        }
        text("Снимок появится на главной. Его можно сделать до настройки почты.")
        column = page
    }
    private fun buildHelp() {
        heading("Подготовка телефона")
        text("На Xiaomi включите автозапуск и работу без ограничений батареи. Проверьте автоматическую дату и время.\n\nAndroid 9 и новее: после остановки сервиса или перезагрузки откройте приложение и включите наблюдение вручную. На Android 7 проверяйте автозапуск после перезагрузки. Может потребоваться первое разблокирование.")
        button("Доступ к точному времени съёмки") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else toast("На этой версии отдельное разрешение не требуется")
        }
        button("Настройки батареи") { open(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("Разрешения приложения") { open(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        heading("Доставка и очередь")
        text("Сначала отправляется актуальное фото, затем накопленные. Старые фото помечаются «Из очереди» с датой съёмки.\n\nОтправленные фото хранятся 7 дней, неотправленные — до доставки. Предел архива 100 МБ. Адрес получателя фиксируется при съёмке.")
        heading("Подробное состояние")
        details = text("")
        button("Журнал событий") { AlertDialog.Builder(this).setTitle("Журнал событий").setMessage(EventLog.read(this).takeLast(16000)).setPositiveButton("Закрыть", null).show() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", currentPage)
        sections.forEach { (key, section) -> outState.putBoolean("section_$key", section.content.visibility == View.VISIBLE) }
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentPage != "home") showPage("home") else super.onBackPressed()
    }
    private fun pickTime(selected: (String) -> Unit) {
        TimePickerDialog(this, { _, h, m -> selected(String.format(Locale.ROOT, "%02d:%02d", h, m)) }, 9, 0, true).show()
    }
    private fun saveCamera() { config.prefs.edit().putBoolean("front", front.isChecked).putBoolean("flash", flash.isChecked).putInt("rotation", rotation.selectedItemPosition * 90).apply() }
    private fun save(): Boolean {
        if (config.enabled || MonitorService.instance?.isBusy == true) {
            toast("Остановите наблюдение и дождитесь завершения операции"); return false
        }
        var errorSection = "schedule"
        return try {
            fun value(key: String) = fields.getValue(key).text.toString().trim()
            Schedule.parse(value("times"))
            errorSection = "delivery"
            val editor = config.prefs.edit()
            val password = fields.getValue("password").text.toString()
            val sameSmtp = config.user == value("user") && config.host == value("host")
            val from = value("from").ifBlank { value("user") }
            val port = value("port").toIntOrNull() ?: 465
            require(port in 1..65535 && value("port").toIntOrNull() != null) { "Порт SMTP должен быть от 1 до 65535" }
            require(Regex("[A-Za-z0-9.-]+").matches(value("host"))) { "Укажите имя SMTP-сервера без https://" }
            require(value("user").isNotBlank()) { "Укажите логин SMTP" }
            Mailer.validateAddress(from); Mailer.validateAddress(value("to"))
            require(password.isNotEmpty() || (sameSmtp && config.password().isNotEmpty())) { "Введите пароль приложения для этого сервера и логина" }
            listOf("times", "host", "user", "to").forEach { editor.putString(it, value(it)) }
            editor.putString("from", from).putInt("port", port).putBoolean("ssl", ssl.isChecked)
                .putBoolean("front", front.isChecked).putBoolean("flash", flash.isChecked).putInt("rotation", rotation.selectedItemPosition * 90)
            if (password.isNotEmpty()) editor.putString("secret", config.encrypt(password)) else if (!sameSmtp) editor.remove("secret")
            check(editor.commit()) { "Не удалось записать настройки" }
            fields.getValue("password").setText("")
            saveCamera(); toast("Настройки сохранены"); true
        } catch (e: Exception) {
            showPage("settings")
            sections.keys.forEach { setSectionOpen(it, it == errorSection) }
            pages.getValue("settings").post {
                pages.getValue("settings").scrollTo(0, (sections.getValue(errorSection).header.parent as View).top)
            }
            toast(if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "Проверьте настройки" else "Не удалось сохранить секрет. Введите пароль заново")
            false
        }
    }
    private fun stopMonitoring() {
        config.enabled = false; config.pendingCapture = 0
        Scheduler.cancel(this)
        if (MonitorService.instance?.isBusy != true) stopService(Intent(this, MonitorService::class.java))
        EventLog.add(this, "Наблюдение остановлено пользователем"); refresh()
    }
    private fun enable() {
        if (!config.enabled && !save()) return
        if (!permission()) return
        config.enabled = true
        Scheduler.plan(this, reset = true); startMonitor(); refresh()
    }
    private fun permission(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10); return false
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        return true
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10) toast(if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Камера разрешена. Нажмите нужную кнопку ещё раз" else "Для работы нужен доступ к камере")
    }
    private fun startMonitor(manual: Boolean = false, preview: Boolean = false) {
        try {
            val intent = Intent(this, MonitorService::class.java).putExtra("manual", manual).putExtra("preview", preview)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { toast("Не удалось запустить сервис. Проверьте разрешения") }
    }
    private fun open(intent: Intent) { try { startActivity(intent) } catch (_: Exception) { toast("Этот раздел недоступен в данной прошивке") } }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onResume() {
        super.onResume()
        if (config.enabled) {
            if (config.next in 1..System.currentTimeMillis() && config.pendingCapture == 0L) config.pendingCapture = config.next
            Scheduler.plan(this)
        }
        handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    private fun refresh() {
        val editable = !config.enabled && MonitorService.instance?.isBusy != true
        fields.values.forEach { it.isEnabled = editable }
        listOf(ssl, front, flash).forEach { it.isEnabled = editable }
        rotation.isEnabled = editable
        editingButtons.forEach { it.isEnabled = editable }
        saveButton.isEnabled = editable; saveButton.alpha = if (editable) 1f else 0.45f
        settingsNotice.text = when {
            config.enabled -> "Для изменения настроек остановите наблюдение. После сохранения включите его снова."
            !editable -> "Дождитесь завершения текущей операции."
            else -> "Выберите раздел, измените настройки и сохраните."
        }
        editStopButton.visibility = if (config.enabled) View.VISIBLE else View.GONE
        editStopButton.text = "Остановить и настроить"
        startButton.visibility = if (!config.enabled || MonitorService.instance == null) View.VISIBLE else View.GONE
        startButton.text = if (config.enabled) "Возобновить наблюдение" else "Включить наблюдение"
        stopButton.visibility = if (config.enabled) View.VISIBLE else View.GONE
        statusTitle.text = when {
            !config.enabled -> "Наблюдение выключено"
            MonitorService.instance == null -> "Нужно возобновить наблюдение"
            else -> "Наблюдение включено"
        }
        statusTitle.setTextColor(if (config.enabled && MonitorService.instance != null) accent else ink)
        status.text = "Ежедневно: ${config.times}\n" +
            "Следующее фото: ${if (config.enabled && config.next > 0) EventLog.stamp(config.next) else "—"}\n" +
            "Последнее фото: ${config.text("lastPhoto")}\n" +
            "Последнее письмо: ${config.text("lastSent")}\n" +
            "В очереди: ${Outbox(this).count()}"
        val problems = mutableListOf<String>()
        if (config.text("problem") !in listOf("—", "Нет", "")) problems.add(config.text("problem"))
        if (config.enabled && !Scheduler.exact(this)) problems.add("Разрешите точное время съёмки в разделе «Помощь»")
        warning.text = problems.joinToString("\n"); warning.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        details.text = "Сервис: ${if (MonitorService.instance != null) "работает" else "остановлен"}\n" +
            "Почта: ${config.text("lastSent")}\nОшибка: ${config.text("problem")}"
        val file = Outbox(this).latest() ?: run {
            image.setImageDrawable(null); image.visibility = View.GONE; emptyPhoto.visibility = View.VISIBLE; shownPhoto = ""; return
        }
        val tag = file.absolutePath + file.lastModified()
        if (shownPhoto == tag) return
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val degrees = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            image.setImageBitmap(if (degrees == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true))
            image.visibility = View.VISIBLE; emptyPhoto.visibility = View.GONE; shownPhoto = tag
        } catch (_: Exception) { }
    }
}
