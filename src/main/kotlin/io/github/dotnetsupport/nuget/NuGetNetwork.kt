package io.github.dotnetsupport.nuget

import com.intellij.util.io.HttpRequests
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxySettings
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * What the journal says about the network of the IDE: the feeds are read by the HTTP client of the IDE, which has its own proxy,
 * certificates and credentials, none of them shared with the `dotnet` CLI. So the CLI succeeding is no proof the IDE can reach a feed,
 * and a failure is described with the setting of the IDE that usually fixes it.
 */
object NuGetNetwork {
    /** `direct` or the proxy the IDE would use for [url], and whether credentials are stored for [source]. */
    fun routeOf(url: String, source: String): String {
        val route = runCatching {
            val proxies = JdkProxyProvider.getInstance().proxySelector.select(URI(url))
            when {
                proxies.isNullOrEmpty() || proxies.all { it.type() == Proxy.Type.DIRECT } -> "direct"
                else -> proxies.filter { it.type() != Proxy.Type.DIRECT }.joinToString(", ") { "via ${it.type().name.lowercase()} proxy ${it.address()}" }
            }
        }.getOrElse { "route unknown: ${it.javaClass.simpleName}" }
        val user = runCatching { NuGetCredentialStore.get(source)?.userName }.getOrNull()
        return route + if (user != null) ", as user $user" else ", no credentials stored for the source"
    }

    /** The proxy settings of the IDE (Settings | Appearance & Behavior | System Settings | HTTP Proxy) and the JVM properties that matter. */
    fun ideSummary(): String {
        val proxy = runCatching {
            when (val configuration = ProxySettings.getInstance().getProxyConfiguration()) {
                is ProxyConfiguration.DirectProxy -> "No proxy"
                is ProxyConfiguration.AutoDetectProxy -> "Auto-detect proxy settings"
                is ProxyConfiguration.ProxyAutoConfiguration -> "PAC ${configuration.pacUrl}"
                is ProxyConfiguration.StaticProxyConfiguration ->
                    "${configuration.protocol} ${configuration.host}:${configuration.port}" + configuration.exceptions.takeIf { it.isNotBlank() }?.let { ", exceptions $it" }.orEmpty()
                else -> configuration.toString()
            }
        }.getOrElse { "unknown: ${it.javaClass.simpleName}" }
        val properties = listOf("java.net.useSystemProxies", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.nonProxyHosts", "java.net.preferIPv4Stack")
            .mapNotNull { name -> System.getProperty(name)?.let { "$name=$it" } }
        val environment = listOf("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy")
            .mapNotNull { name -> System.getenv(name)?.let { "$name=${maskPassword(it)}" } }
        return "the feeds are read by the HTTP client of the IDE: proxy settings of the IDE = $proxy" +
            (if (properties.isEmpty()) "" else "; JVM ${properties.joinToString(", ")}") +
            (if (environment.isEmpty()) "; no proxy variables in the environment of the IDE" else "; environment ${environment.joinToString(", ")}") +
            ". The dotnet CLI has its own proxy, certificates and nuget.config credentials, so it can reach a feed the IDE cannot"
    }

    /** The exception and its causes by class and message, then the setting of the IDE that usually fixes failures of that kind. */
    fun describeFailure(e: Throwable): String {
        val chain = generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.toList()
        val text = chain.joinToString(", caused by ") { "${it.javaClass.simpleName}: ${it.message?.takeIf(String::isNotBlank) ?: "no message"}" }
        return text + hint(chain)?.let { " -- $it" }.orEmpty()
    }

    private fun hint(chain: List<Throwable>): String? {
        val status = chain.filterIsInstance<HttpRequests.HttpStatusException>().firstOrNull()?.statusCode
        return when {
            status == 401 || status == 403 ->
                "the feed wants credentials: NuGet window → Sources → Edit the source (user and password); the CLI takes them from nuget.config or a credential provider, the IDE does not"
            status == 404 -> "no such resource on the feed: not a V3 feed, or the package is not in it"
            status != null && status >= 500 -> "the feed itself failed; try again later"
            chain.any { it is UnknownHostException } ->
                "the IDE cannot resolve the host name; the CLI may go through a proxy that resolves it: Settings | Appearance & Behavior | System Settings | HTTP Proxy (Auto-detect, or the proxy of the shell), then Check connection with the URL of the feed"
            chain.any { it is SSLException || it is CertificateException } || chain.any { it.message?.contains("PKIX", ignoreCase = true) == true } ->
                "the certificate of the server (or of the corporate proxy) is not trusted by the IDE: Settings | Tools | Server Certificates, add the root certificate or accept non-trusted certificates"
            chain.any { it is SocketTimeoutException } -> "no answer in time: a proxy or a firewall between the IDE and the feed; check Settings | HTTP Proxy"
            chain.any { it is ConnectException } -> "the connection is refused or blocked: a proxy or a firewall between the IDE and the feed; check Settings | HTTP Proxy"
            else -> null
        }
    }

    private fun maskPassword(url: String): String = url.replace(Regex("://([^:/@]+):[^@/]+@"), "://$1:***@")
}
