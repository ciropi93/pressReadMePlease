package io.github.pressreadmeplease

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var renewer: Renewer
    private lateinit var web: WebView

    private lateinit var mlolSite: EditText
    private lateinit var mlolUser: EditText
    private lateinit var mlolPass: EditText
    private lateinit var spidBox: CheckBox
    private lateinit var prUser: EditText
    private lateinit var prPass: EditText
    private lateinit var runButton: Button
    private lateinit var lastRun: TextView
    private lateinit var logView: TextView

    private var job: Job? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(header("MLOL"))
        mlolSite = field("https://tuabiblioteca.medialibrary.it", InputType.TYPE_TEXT_VARIATION_URI)
        mlolUser = field("Username MLOL", InputType.TYPE_CLASS_TEXT)
        mlolPass = field("Password MLOL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        spidBox = CheckBox(this).apply {
            text = "Accesso con SPID (login manuale)"
            setOnCheckedChangeListener { _, checked -> updateSpidVisibility(checked) }
        }
        listOf(mlolSite, spidBox, mlolUser, mlolPass).forEach { root.addView(it) }

        root.addView(header("PressReader"))
        prUser = field("Email PressReader", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        prPass = field("Password PressReader", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(prUser, prPass).forEach { root.addView(it) }

        val saveButton = Button(this).apply {
            text = "Salva configurazione"
            setOnClickListener { readConfig()?.let { store.save(it); toast("Configurazione salvata") } }
        }
        root.addView(saveButton)

        runButton = Button(this).apply {
            text = "Rinnova ora"
            setOnClickListener { onRunClicked() }
        }
        root.addView(runButton)

        lastRun = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(lastRun)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        web = WebView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(560))
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        root.addView(web)

        setContentView(ScrollView(this).apply { addView(root) })

        renewer = Renewer(web, ::log)

        val c = store.load()
        mlolSite.setText(c.mlolSite)
        mlolUser.setText(c.mlolUser)
        mlolPass.setText(c.mlolPass)
        prUser.setText(c.prUser)
        prPass.setText(c.prPass)
        spidBox.isChecked = c.spid
        updateSpidVisibility(c.spid)
        showLastRun()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, 0)
    }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        setSingleLine()
    }

    private fun updateSpidVisibility(spid: Boolean) {
        val v = if (spid) View.GONE else View.VISIBLE
        mlolUser.visibility = v
        mlolPass.visibility = v
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun readConfig(): Config? {
        var site = mlolSite.text.toString().trim()
        if (site.isNotEmpty() && !site.startsWith("http")) site = "https://$site"
        val c = Config(
            mlolSite = site,
            mlolUser = mlolUser.text.toString().trim(),
            mlolPass = mlolPass.text.toString(),
            prUser = prUser.text.toString().trim(),
            prPass = prPass.text.toString(),
            spid = spidBox.isChecked,
        )
        if (!c.isComplete()) {
            toast("Compila tutti i campi")
            return null
        }
        if (!c.mlolSite.contains("medialibrary.it")) {
            toast("Il sito MLOL deve essere un indirizzo medialibrary.it")
            return null
        }
        return c
    }

    private fun onRunClicked() {
        if (job?.isActive == true) {
            job?.cancel()
            return
        }
        val config = readConfig() ?: return
        store.save(config)

        logView.text = ""
        web.visibility = View.VISIBLE
        runButton.text = "Annulla"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        job = lifecycleScope.launch {
            try {
                val msg = renewer.run(config)
                store.lastSuccess = System.currentTimeMillis()
                log("OK - $msg")
            } catch (e: CancellationException) {
                log("Operazione annullata")
                throw e
            } catch (e: FlowException) {
                log("ERRORE - ${e.message}")
            } catch (e: Exception) {
                log("ERRORE inatteso - $e")
            } finally {
                runButton.text = "Rinnova ora"
                web.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showLastRun()
            }
        }
    }

    private fun showLastRun() {
        val t = store.lastSuccess
        lastRun.text = if (t == 0L) {
            "Ultimo rinnovo riuscito: mai"
        } else {
            "Ultimo rinnovo riuscito: " + DateFormat.format("dd/MM/yyyy HH:mm", Date(t))
        }
    }

    private fun log(msg: String) {
        logView.append(DateFormat.format("HH:mm:ss", Date()).toString() + "  " + msg + "\n")
    }
}
