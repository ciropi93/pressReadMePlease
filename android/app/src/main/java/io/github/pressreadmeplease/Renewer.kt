package io.github.pressreadmeplease

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume

class FlowException(message: String) : Exception(message)

/**
 * Porting in Kotlin del flusso Playwright di `src/mlol.py` + `src/pressreader.py`:
 * MLOL login -> Edicola -> Corriere della Sera -> "Sfoglia online" -> login PressReader
 * -> verifica dei giorni di accesso -> logout.
 *
 * Tutto gira dentro una WebView pilotata via JavaScript (la sfida ALTCHA di MLOL
 * viene risolta dal browser stesso, come in Chromium).
 */
class Renewer(private val web: WebView, private val log: (String) -> Unit) {

    companion object {
        const val EXPECTED_FREE_ACCESS_DAYS = 2
        private const val VIEWPORT_WIDTH = 1280

        private const val PRELUDE = """
            const vis=function(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length));};
            const byText=function(sel,re){
              var r=new RegExp(re,'i');
              return Array.prototype.slice.call(document.querySelectorAll(sel)).find(function(e){
                return vis(e)&&r.test(((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').trim());
              });
            };
        """
    }

    // Contatori aggiornati dal WebViewClient (thread principale).
    private var pageLoads = 0
    private var accessDenied = false

    init {
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Layout "desktop", come il viewport 1920x1080 dello script originale.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            userAgentString = userAgentString
                .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace("Version/4.0 ", "")
                .replace(" Mobile", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(viewportJs(), null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoads++
                view.evaluateJavascript(viewportJs(), null)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.url.toString().contains("Authentication/SignIn") &&
                    errorResponse.statusCode == 403
                ) {
                    accessDenied = true
                }
            }
        }
    }

    private fun viewportJs() =
        "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m&&document.head){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
            "if(m)m.content='width=$VIEWPORT_WIDTH';})()"

    // ---------------------------------------------------------------- flusso

    suspend fun run(config: Config): String {
        accessDenied = false
        // In modalità SPID i cookie si conservano: se la sessione MLOL è ancora valida si salta il login.
        if (!config.spid) resetBrowser()

        val origin = visitMlol(config)
        login(config, origin)
        dismissFavModal()
        openCorriere(origin)
        openPressReader()
        val days = pressReaderFlow(config)
        if (!config.spid) resetBrowser() else CookieManager.getInstance().flush()

        return if (days != null) {
            "Rinnovo completato: $days giorni di accesso."
        } else {
            "Rinnovo completato (accesso sponsorizzato/autenticato confermato)."
        }
    }

    private fun resetBrowser() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
    }

    private suspend fun visitMlol(config: Config): String {
        log("MLOL: apertura ${config.mlolSite}")
        open(config.mlolSite)
        return (eval("return location.origin;") as? String)
            ?: throw FlowException("Impossibile aprire il sito MLOL")
    }

    private suspend fun login(config: Config, origin: String) {
        if (config.spid) {
            if (waitFor("a[href*='logout']", 3000)) {
                log("MLOL: sessione già attiva, salto il login")
                return
            }
            log("MLOL: accedi con SPID nella finestra qui sotto (attendo fino a 5 minuti)")
            if (!waitUntil(300000, 1000) { exists("a[href*='logout']") }) {
                throw FlowException("Login SPID non completato entro 5 minuti")
            }
            log("MLOL: login SPID rilevato")
            return
        }

        log("MLOL: login")
        if (!waitFor("#Username", 3000)) {
            val clicked = waitUntil(5000) { clickText("button, a, input[type=submit]", "^\\s*accedi") }
            if (!clicked || !waitFor("#Username", 5000)) {
                log("MLOL: pulsante Accedi non trovato, provo l'URL di login")
                open("$origin/login/login")
            }
        }
        require("#Username", "Form di login MLOL")

        fill("#Username", config.mlolUser)
        fill("#Password", config.mlolPass)

        val before = pageLoads
        eval(
            """
            var p=document.querySelector('#Password'); var f=p&&p.form; if(!f) return false;
            var b=f.querySelector('[type=submit]');
            if(b){b.click();} else if(f.requestSubmit){f.requestSubmit();} else {f.submit();}
            return true;
            """,
        )
        waitUntil(15000) { pageLoads > before }

        val confirmed = waitUntil(20000) {
            if (text(".page-title").lowercase().contains("avviso")) {
                throw FlowException("Login MLOL fallito: controlla le credenziali")
            }
            exists("a[href*='logout']")
        }
        if (!confirmed) log("MLOL: login non confermato esplicitamente, proseguo")
    }

    private suspend fun dismissFavModal() {
        if (waitFor("#FavModal", 3000)) {
            log("MLOL: chiudo la modale")
            click(".modal-footer button[data-dismiss='modal']")
            delay(500)
        }
    }

    private suspend fun openCorriere(origin: String) {
        log("MLOL: cerco Corriere della Sera in Edicola")
        open("$origin/search?idtype=600")

        var href: String? = null
        waitUntil(15000) {
            href = eval(
                """
                var a=Array.prototype.slice.call(document.querySelectorAll("a[href*='/media/details/']"))
                  .find(function(e){return (e.innerText||'').trim().toLowerCase()==='corriere della sera';});
                return a?a.getAttribute('href'):null;
                """,
            ) as? String
            href != null
        }
        val target = href ?: throw FlowException("Corriere della Sera non trovato nell'elenco Edicola")
        val base = eval("return location.href;") as? String ?: origin
        open(URI(base).resolve(target).toString())

        dismissHtmxModal("#modal-onboardingontainer")
        dismissHtmxModal("#modal-communicationcontainer")
    }

    private suspend fun dismissHtmxModal(container: String) {
        if (!waitFor("$container .modal-body", 3000)) return
        log("MLOL: chiudo modale $container")
        for (sel in listOf("[data-dismiss='modal']", "[data-bs-dismiss='modal']", ".btn-close", "button.close")) {
            if (click("$container $sel")) break
        }
        eval(
            """
            var c=document.querySelector(${q(container)});
            if(c){c.innerHTML='';c.remove();}
            document.querySelectorAll('.modal-backdrop').forEach(function(b){b.remove();});
            document.body.classList.remove('modal-open');
            document.body.style.removeProperty('overflow');
            document.body.style.removeProperty('padding-right');
            return true;
            """,
        )
    }

    private suspend fun openPressReader() {
        log("MLOL: apro PressReader (Sfoglia online)")
        val found = waitUntil(15000) { exists("a") && eval("return !!byText('a','sfoglia online');") == true }
        if (!found) throw FlowException("Link 'Sfoglia online' non trovato")

        val before = pageLoads
        // Il link ha target=_blank: lo togliamo per restare nella stessa WebView.
        eval("var e=byText('a','sfoglia online'); e.removeAttribute('target'); e.click(); return true;")
        waitUntil(30000) { pageLoads > before }

        val onPressReader = waitUntil(30000) {
            (eval("return document.title;") as? String)?.lowercase()?.contains("pressreader") == true
        }
        if (!onPressReader) throw FlowException("Passaggio a PressReader fallito")
    }

    /** @return giorni di accesso rimasti, o null se confermato in altro modo (sponsorizzato/autenticato). */
    private suspend fun pressReaderFlow(config: Config): Int? {
        log("PressReader: cookie e login")
        waitUntil(5000) { clickText("button, a, [role=button]", "deny|rifiut|nega") }
        if (waitFor("label[data-bind*='selectTitle']", 10000)) {
            click("label[data-bind*='selectTitle']")
        }

        val signIn = waitUntil(20000) { clickText("button, a[role=button], [role=button]", "sign in|accedi") }
        if (!signIn) throw FlowException("Pulsante 'Sign in' di PressReader non trovato")

        require("input[type='email']", "Form di login PressReader", 20000)
        fill("input[type='email']", config.prUser)
        fill("input[type='password']", config.prPass)

        // Deseleziona "stay signed in" se attivo, come nello script originale.
        eval(
            """
            var c=document.querySelector('.checkbox'); if(!c) return false;
            var i=(c.tagName==='INPUT')?c:c.querySelector('input');
            var on=(i&&i.checked)||c.classList.contains('checked');
            if(on){c.click();}
            return true;
            """,
        )
        require("div.pop-group > a[role='link']", "Pulsante di conferma login PressReader")
        click("div.pop-group > a[role='link']")

        log("PressReader: verifica giorni di accesso")
        val selector = "span[data-bind='text: freeAccessTime']"
        val hasDays = waitUntil(30000) {
            if (accessDenied) throw FlowException("Accesso negato da PressReader (403)")
            if (exists(".infomsg") && text(".infomsg").contains("invalid", ignoreCase = true)) {
                throw FlowException("Login PressReader fallito: controlla le credenziali")
            }
            text(selector).isNotBlank()
        }

        if (hasDays) {
            val raw = text(selector).trim()
            log("PressReader: $raw")
            val head = raw.split(Regex("day|giorn", RegexOption.IGNORE_CASE))[0]
            val days = head.filter { it.isDigit() }.toIntOrNull()
                ?: throw FlowException("Impossibile leggere i giorni da '$raw'")
            if (days != EXPECTED_FREE_ACCESS_DAYS) {
                throw FlowException("Rinnovo non confermato: giorni = $days (attesi $EXPECTED_FREE_ACCESS_DAYS)")
            }
            logoutPressReader()
            return days
        }

        val sponsored = waitUntil(5000) {
            eval("return /brought to you by/i.test(document.body.innerText);") == true
        }
        if (sponsored || waitUntil(5000) { visible(".userphoto-title") }) {
            logoutPressReader()
            return null
        }
        throw FlowException("Elemento dei giorni di accesso gratuito non trovato")
    }

    private suspend fun logoutPressReader() {
        if (click(".userphoto-title")) {
            delay(500)
            waitUntil(3000) { click(".pri-logout") }
        }
    }

    // --------------------------------------------------------- helper browser

    private suspend fun open(url: String) {
        val before = pageLoads
        web.loadUrl(url)
        if (!waitUntil(30000) { pageLoads > before }) log("Pagina lenta a caricare: $url")
        passAltcha()
    }

    private suspend fun passAltcha() {
        delay(800)
        if (!exists("#altcha-gate")) return
        log("MLOL: verifica ALTCHA in corso...")
        if (exists("#altcha-nocookie:not([hidden])")) {
            throw FlowException("ALTCHA richiede i cookie, verifica non avviabile")
        }
        if (!waitUntil(60000) { !exists("#altcha-gate") }) {
            throw FlowException("ALTCHA non superato entro 60s")
        }
        waitUntil(15000) { eval("return document.readyState;") == "complete" }
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private suspend fun eval(body: String): Any? = suspendCancellableCoroutine { cont ->
        val script = "(function(){$PRELUDE try{$body}catch(e){return null;}})()"
        web.evaluateJavascript(script) { raw ->
            val value: Any? = try {
                if (raw == null || raw == "null") null else JSONTokener(raw).nextValue()
            } catch (e: Exception) {
                null
            }
            if (cont.isActive) cont.resume(value)
        }
    }

    private suspend fun exists(sel: String) =
        eval("return !!document.querySelector(${q(sel)});") == true

    private suspend fun visible(sel: String) =
        eval("return vis(document.querySelector(${q(sel)}));") == true

    private suspend fun text(sel: String): String =
        eval("var e=document.querySelector(${q(sel)}); return e?(e.innerText||e.textContent||''):null;") as? String
            ?: ""

    private suspend fun click(sel: String) =
        eval("var e=document.querySelector(${q(sel)}); if(!e) return false; e.click(); return true;") == true

    private suspend fun clickText(sel: String, regex: String) =
        eval("var e=byText(${q(sel)},${q(regex)}); if(!e) return false; e.click(); return true;") == true

    private suspend fun fill(sel: String, value: String) = eval(
        """
        var e=document.querySelector(${q(sel)}); if(!e) return false;
        e.focus();
        var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
        d.set.call(e,${q(value)});
        e.dispatchEvent(new Event('input',{bubbles:true}));
        e.dispatchEvent(new Event('change',{bubbles:true}));
        return true;
        """,
    ) == true

    private suspend fun waitUntil(timeoutMs: Long, everyMs: Long = 500, cond: suspend () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (cond()) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            delay(everyMs)
        }
    }

    private suspend fun waitFor(sel: String, timeoutMs: Long = 15000) = waitUntil(timeoutMs) { exists(sel) }

    private suspend fun require(sel: String, what: String, timeoutMs: Long = 15000) {
        if (!waitFor(sel, timeoutMs)) throw FlowException("$what non trovato ($sel)")
    }
}
