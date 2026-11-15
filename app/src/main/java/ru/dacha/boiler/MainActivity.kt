package ru.dacha.boiler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
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
import java.security.SecureRandom
import java.util.Locale

/** A single readable form. Only settings for selected channels are shown. */
class MainActivity : Activity() {
    private lateinit var config: Settings
    private lateinit var status: TextView
    private lateinit var binding: TextView
    private lateinit var image: ImageView
    private lateinit var column: LinearLayout
    private lateinit var emailGroup: LinearLayout
    private lateinit var telegramGroup: LinearLayout
    private lateinit var heartbeatGroup: LinearLayout
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var email: CheckBox
    private lateinit var telegramPhotos: CheckBox
    private lateinit var control: CheckBox
    private lateinit var heartbeat: CheckBox
    private lateinit var ssl: CheckBox
    private lateinit var front: CheckBox
    private lateinit var flash: CheckBox
    private lateinit var rotation: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private var shownPhoto = ""
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 3000) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(label: String, parent: LinearLayout = column, size: Float = 16f): TextView = TextView(this).apply {
        text = label; textSize = size; setTextColor(Color.rgb(25, 25, 25)); setPadding(0, dp(8), 0, dp(6)); parent.addView(this)
    }
    private fun heading(label: String, parent: LinearLayout = column) = text(label, parent, 21f).apply {
        setTypeface(null, Typeface.BOLD); setPadding(0, dp(22), 0, dp(8))
    }
    private fun button(label: String, parent: LinearLayout = column, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 17f; isAllCaps = false; minHeight = dp(56)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2)); setOnClickListener { action() }
    }
    private fun field(key: String, label: String, value: String, parent: LinearLayout = column, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        text(label, parent)
        return EditText(this).apply {
            tag = key; setText(value); inputType = type; setSingleLine(true); textSize = 18f; minHeight = dp(52)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); fields[key] = this
        }
    }
    private fun check(label: String, value: Boolean, parent: LinearLayout = column) = CheckBox(this).apply {
        text = label; textSize = 18f; isChecked = value; minHeight = dp(56); parent.addView(this)
    }
    private fun group() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; column.addView(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Settings(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(28))
            isFocusable = true; isFocusableInTouchMode = true
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); fitsSystemWindows = true; addView(column) })
        heading("Котёл под присмотром")
        status = text("")
        text("Настройки меняются при остановленном наблюдении. После настройки нажмите «Включить наблюдение».")
        button("Включить наблюдение") { enable() }
        button("Остановить наблюдение") { stopMonitoring() }
        button("Снять и отправить сейчас") {
            if (!config.enabled && !save()) return@button
            if (!config.hasPhotoRoute) { toast("Выберите отправку фото на почту или в Telegram"); return@button }
            if (permission()) startMonitor(manual = true)
        }
        button("Пробный снимок без отправки") {
            if (!config.enabled && MonitorService.instance?.isBusy != true) saveCamera()
            if (permission()) startMonitor(manual = true, preview = true)
        }
        image = ImageView(this).apply {
            contentDescription = "Последний снимок котла"; adjustViewBounds = true; visibility = View.GONE
            column.addView(this, LinearLayout.LayoutParams(-1, dp(200)))
        }
        heading("Расписание фото")
        field("times", "Каждый день в указанное время", config.times)
        button("Добавить время съёмки") {
            pickTime { time ->
                val current = fields.getValue("times").text.toString().trim()
                val combined = if (current.isEmpty()) time else "$current, $time"
                try {
                    fields.getValue("times").setText(Schedule.parse(combined).joinToString(", ") { (h, m) -> String.format(Locale.ROOT, "%02d:%02d", h, m) })
                } catch (_: Exception) { toast("Исправьте расписание. Например: 09:00, 18:30") }
            }
        }
        text("Можно несколько времён: 09:00, 18:30. Используется время телефона.")
        heading("Куда отправлять и как управлять")
        email = check("Фото на электронную почту", config.emailPhotos)
        telegramPhotos = check("Фото в Telegram", config.telegramPhotos)
        control = check("Управление через Telegram", config.remoteControl)
        heartbeat = check("Сообщать в Telegram, что телефон работает", config.heartbeat)
        text("Почту и Telegram можно включить одновременно. Пауза командой /pause сохраняет связь; кнопка «Остановить наблюдение» выключает всё.")
        emailGroup = group()
        heading("Электронная почта", emailGroup)
        field("host", "SMTP-сервер", config.host, emailGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        field("port", "Порт SMTP", config.port.toString(), emailGroup, InputType.TYPE_CLASS_NUMBER)
        ssl = check("TLS сразу при подключении (обычно порт 465)", config.ssl, emailGroup)
        text("Без галочки — обязательный STARTTLS, обычно порт 587.", emailGroup)
        field("user", "Логин SMTP", config.user, emailGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("password", "Пароль приложения (пусто — оставить прежний)", "", emailGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field("from", "Адрес отправителя (пусто — взять из логина)", config.from, emailGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("to", "Адрес получателя", config.to, emailGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        telegramGroup = group()
        heading("Telegram", telegramGroup)
        text("На основном телефоне создайте отдельного бота через @BotFather. Telegram на этот телефон устанавливать не нужно. Один бот используется одним телефоном.", telegramGroup)
        field("token", "Токен бота (пусто — оставить сохранённый)", "", telegramGroup, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        binding = text("", telegramGroup)
        button("Привязать мой Telegram", telegramGroup) { pair() }
        button("Проверить отправку в Telegram", telegramGroup) {
            if (!config.enabled && !save()) return@button
            if (config.telegramChat <= 0) { toast("Сначала привяжите Telegram"); return@button }
            Thread {
                try {
                    TelegramApi(config.token()).message(config.telegramChat, "Проверка связи. Телефон работает: ${EventLog.stamp()}")
                    runOnUiThread { toast("Проверочное сообщение отправлено") }
                } catch (_: Exception) { runOnUiThread { toast("Не удалось отправить. Проверьте интернет и токен") } }
            }.start()
        }
        heartbeatGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; telegramGroup.addView(this) }
        field("heartbeatTime", "Сообщение «телефон работает» ежедневно в", config.heartbeatTime, heartbeatGroup)
        button("Выбрать время сообщения", heartbeatGroup) { pickTime { fields.getValue("heartbeatTime").setText(it) } }
        text("Команды: /status — состояние, /photo — фото, /times 09:00, 18:30 — расписание, /help — остальные команды. Сообщение о работе может задержаться при отсутствии связи или в режиме сна Android.", telegramGroup)
        listOf(email, telegramPhotos, control, heartbeat).forEach { it.setOnCheckedChangeListener { _, _ -> showGroups() } }
        heading("Камера")
        front = check("Использовать переднюю камеру", config.front)
        flash = check("Вспышка при съёмке", config.flash)
        text("Поворот фотографии")
        rotation = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("0°", "90°", "180°", "270°"))
            setSelection(config.rotation / 90); minimumHeight = dp(52); column.addView(this)
        }
        button("Сохранить настройки") { save() }
        heading("Подготовка телефона")
        text("Для изменения настроек остановите наблюдение и дождитесь завершения текущей операции.\n\nНа Xiaomi включите автозапуск и работу без ограничений батареи. Проверьте автоматическую дату и время.\n\nAndroid 9 и новее: после остановки сервиса или перезагрузки откройте приложение и включите наблюдение вручную. На Android 7 проверяйте автозапуск после перезагрузки. Может потребоваться первое разблокирование.\n\nСообщение «работает» подтверждает связь с телефоном. Если он выключен, предупредить об этом самостоятельно он не сможет.")
        button("Доступ к точному времени съёмки") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else toast("На этой версии отдельное разрешение не требуется")
        }
        button("Настройки батареи") { open(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("Разрешения приложения") { open(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("Журнал событий") { AlertDialog.Builder(this).setTitle("Журнал событий").setMessage(EventLog.read(this).takeLast(16000)).setPositiveButton("Закрыть", null).show() }
        text("Отправленные фото хранятся 7 дней, неотправленные — до доставки. Предел архива 100 МБ. Адреса и выбранные каналы фиксируются при съёмке. SMTP принятие и Telegram принятие не подтверждают просмотр сообщения.")
        showGroups()
        column.requestFocus()
    }
    private fun showGroups() {
        emailGroup.visibility = if (email.isChecked) View.VISIBLE else View.GONE
        telegramGroup.visibility = if (telegramPhotos.isChecked || control.isChecked || heartbeat.isChecked) View.VISIBLE else View.GONE
        heartbeatGroup.visibility = if (heartbeat.isChecked) View.VISIBLE else View.GONE
    }
    private fun pickTime(selected: (String) -> Unit) {
        TimePickerDialog(this, { _, h, m -> selected(String.format(Locale.ROOT, "%02d:%02d", h, m)) }, 9, 0, true).show()
    }
    private fun saveCamera() { config.prefs.edit().putBoolean("front", front.isChecked).putBoolean("flash", flash.isChecked).putInt("rotation", rotation.selectedItemPosition * 90).apply() }
    private fun save(): Boolean {
        if (config.enabled || config.pairing || MonitorService.instance?.isBusy == true) {
            toast("Остановите наблюдение или привязку и дождитесь завершения операции"); return false
        }
        return try {
            fun value(key: String) = fields.getValue(key).text.toString().trim()
            Schedule.parse(value("times"))
            require(email.isChecked || telegramPhotos.isChecked || control.isChecked || heartbeat.isChecked) { "Выберите хотя бы одну функцию" }
            val editor = config.prefs.edit()
            val password = fields.getValue("password").text.toString()
            val sameSmtp = config.user == value("user") && config.host == value("host")
            val from = value("from").ifBlank { value("user") }
            val port = value("port").toIntOrNull() ?: 465
            if (email.isChecked) {
                require(port in 1..65535 && value("port").toIntOrNull() != null) { "Порт SMTP должен быть от 1 до 65535" }
                require(Regex("[A-Za-z0-9.-]+").matches(value("host"))) { "Укажите имя SMTP-сервера без https://" }
                require(value("user").isNotBlank()) { "Укажите логин SMTP" }
                Mailer.validateAddress(from); Mailer.validateAddress(value("to"))
                require(password.isNotEmpty() || (sameSmtp && config.password().isNotEmpty())) { "Введите пароль приложения для этого сервера и логина" }
            }
            val useTelegram = telegramPhotos.isChecked || control.isChecked || heartbeat.isChecked
            if (useTelegram) {
                val token = value("token").ifBlank { config.token() }
                require(TelegramApi.validToken(token)) { "Введите токен от @BotFather" }
                require(config.telegramChat > 0 && config.telegramUser > 0 && token.substringBefore(':') == config.prefs.getString("telegramBot", "")) { "Сначала нажмите «Привязать мой Telegram»" }
                if (value("token").isNotEmpty()) editor.putString("telegramSecret", config.encrypt(token))
            }
            if (heartbeat.isChecked) require(Schedule.parse(value("heartbeatTime")).size == 1) { "Для сообщения укажите одно время HH:mm" }
            listOf("times", "host", "user", "to", "heartbeatTime").forEach { editor.putString(it, value(it)) }
            editor.putString("from", from).putInt("port", port).putBoolean("ssl", ssl.isChecked)
                .putBoolean("front", front.isChecked).putBoolean("flash", flash.isChecked).putInt("rotation", rotation.selectedItemPosition * 90)
                .putBoolean("emailPhotos", email.isChecked).putBoolean("telegramPhotos", telegramPhotos.isChecked)
                .putBoolean("remoteControl", control.isChecked).putBoolean("heartbeat", heartbeat.isChecked)
                .putLong("heartbeatNext", 0)
            if (password.isNotEmpty()) editor.putString("secret", config.encrypt(password)) else if (!sameSmtp) editor.remove("secret")
            check(editor.commit()) { "Не удалось записать настройки" }
            fields.getValue("password").setText(""); fields.getValue("token").setText("")
            saveCamera(); toast("Настройки сохранены"); true
        } catch (e: Exception) { toast(if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "Проверьте настройки" else "Не удалось сохранить секрет. Введите пароль и токен заново"); false }
    }
    private fun pair() {
        if (config.enabled || MonitorService.instance?.isBusy == true) { toast("Сначала остановите наблюдение"); return }
        if (!permission()) return
        try {
            val token = fields.getValue("token").text.toString().trim().ifBlank { config.token() }
            require(TelegramApi.validToken(token)) { "Введите токен от @BotFather" }
            val code = (10_000_000 + SecureRandom().nextInt(90_000_000)).toString()
            val editor = config.prefs.edit().putString("telegramSecret", config.encrypt(token)).putString("pairCode", code)
                .putLong("pairUntil", System.currentTimeMillis() + 10 * 60_000)
            if (token.substringBefore(':') != config.prefs.getString("telegramBot", ""))
                editor.remove("telegramChat").remove("telegramUser").putLong("telegramOffset", 0)
            editor.putString("telegramBot", token.substringBefore(':'))
            check(editor.commit())
            fields.getValue("token").setText("")
            startMonitor(pair = true)
            AlertDialog.Builder(this).setTitle("Привязка Telegram")
                .setMessage("На основном телефоне откройте чат с вашим ботом и отправьте:\n\n/pair $code\n\nКод действует 10 минут. После ответа бота сохраните настройки и включите наблюдение.")
                .setPositiveButton("Понятно", null).setNegativeButton("Отменить привязку") { _, _ -> stopMonitoring() }.show()
        } catch (_: Exception) { toast("Не удалось начать привязку. Проверьте токен бота") }
    }
    private fun stopMonitoring() {
        if (config.enabled) syncRemoteSettings()
        config.enabled = false; config.pendingCapture = 0
        config.prefs.edit().remove("pairCode").remove("pairUntil").remove("remotePhoto").commit()
        Scheduler.cancel(this)
        if (MonitorService.instance?.isBusy != true) stopService(Intent(this, MonitorService::class.java))
        EventLog.add(this, "Наблюдение остановлено пользователем"); refresh()
    }
    private fun enable() {
        if (!config.enabled && !save()) return
        if (!permission()) return
        config.enabled = true
        config.prefs.edit().putBoolean("paused", false).commit()
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
    private fun startMonitor(manual: Boolean = false, preview: Boolean = false, pair: Boolean = false) {
        try {
            val intent = Intent(this, MonitorService::class.java).putExtra("manual", manual).putExtra("preview", preview).putExtra("pair", pair)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { toast("Не удалось запустить сервис. Проверьте разрешения") }
    }
    private fun open(intent: Intent) { try { startActivity(intent) } catch (_: Exception) { toast("Этот раздел недоступен в данной прошивке") } }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onResume() {
        super.onResume()
        if (config.enabled) {
            if (!config.paused && config.hasPhotoRoute && config.next in 1..System.currentTimeMillis() && config.pendingCapture == 0L) config.pendingCapture = config.next
            Scheduler.plan(this)
        }
        handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    private fun syncRemoteSettings() {
        for ((key, value) in listOf("times" to config.times, "to" to config.to, "heartbeatTime" to config.heartbeatTime)) {
            val field = fields.getValue(key)
            if (field.text.toString() != value) field.setText(value)
        }
        front.isChecked = config.front; flash.isChecked = config.flash
        rotation.setSelection(config.rotation / 90); heartbeat.isChecked = config.heartbeat
    }
    private fun refresh() {
        if (config.enabled) syncRemoteSettings()
        val editable = !config.enabled && !config.pairing
        fields.values.forEach { it.isEnabled = editable }
        listOf(email, telegramPhotos, control, heartbeat, ssl, front, flash).forEach { it.isEnabled = editable }
        rotation.isEnabled = editable
        status.text = "${if (!config.enabled) "Наблюдение выключено" else if (config.paused) "Съёмка на паузе. Связь включена" else "Наблюдение включено"}\n" +
            "Сервис: ${if (MonitorService.instance != null) "работает" else "остановлен"}\n" +
            "Следующее фото: ${if (config.enabled && !config.paused && config.hasPhotoRoute && config.next > 0) EventLog.stamp(config.next) else "—"}\n" +
            "Последнее фото: ${config.text("lastPhoto")}\nПочта: ${config.text("lastSent")}\nTelegram: ${config.text("lastTelegramPhoto")}\n" +
            "В очереди: ${Outbox(this).count()}\nОшибка: ${config.text("problem")}" +
            if (config.enabled && !Scheduler.exact(this)) "\nРазрешите точное время съёмки внизу страницы" else ""
        binding.text = (if (config.pairing) "Ожидается команда привязки" else if (config.telegramChat > 0) "Telegram привязан к вашему аккаунту" else "Telegram ещё не привязан") +
            "\nПоследнее сообщение о работе: ${config.text("lastHeartbeat")}\nСвязь: ${config.text("telegramProblem")}"
        val file = Outbox(this).latest() ?: return
        val tag = file.absolutePath + file.lastModified()
        if (shownPhoto == tag) return
        try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return
            val degrees = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            image.setImageBitmap(if (degrees == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true))
            image.visibility = View.VISIBLE; shownPhoto = tag
        } catch (_: Exception) { }
    }
}
