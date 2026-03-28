package com.apidebug.inspector

import android.app.Application
import com.apidebug.inspector.capture.VpnCaptureController
import com.apidebug.inspector.core.RootCommandExecutor
import com.apidebug.inspector.core.RootRedirectManager
import com.apidebug.inspector.data.InspectorDatabaseHelper
import com.apidebug.inspector.data.RuleRepository
import com.apidebug.inspector.data.SettingsRepository
import com.apidebug.inspector.data.TrafficRepository
import com.apidebug.inspector.export.SessionExporter
import com.apidebug.inspector.nativebridge.Tun2SocksBridge
import com.apidebug.inspector.network.VpnSocketProtector
import com.apidebug.inspector.network.RequestEngine
import com.apidebug.inspector.proxy.LocalDebugProxyServer
import com.apidebug.inspector.proxy.LocalProxyState
import com.apidebug.inspector.tls.DeveloperCertificateAuthority
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class InspectorApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val database = InspectorDatabaseHelper(application)
    private val rootCommandExecutor = RootCommandExecutor()
    private val tun2SocksBridge = Tun2SocksBridge()
    val vpnSocketProtector = VpnSocketProtector()

    val settingsRepository = SettingsRepository(application)
    val trafficRepository = TrafficRepository(application, database)
    val ruleRepository = RuleRepository(database, gson)
    val requestEngine = RequestEngine(trafficRepository, ruleRepository, vpnSocketProtector)
    val sessionExporter = SessionExporter(application, gson, trafficRepository, ruleRepository)
    val developerCertificateAuthority = DeveloperCertificateAuthority(application)
    val localDebugProxyServer = LocalDebugProxyServer(
        requestEngine = requestEngine,
        settingsRepository = settingsRepository,
        certificateAuthority = developerCertificateAuthority
    )
    val vpnCaptureController = VpnCaptureController(
        appContext = application,
        settingsRepository = settingsRepository,
        proxyPort = LocalProxyState.DEFAULT_PROXY_PORT,
        tun2SocksBridge = tun2SocksBridge
    )
    val rootRedirectManager = RootRedirectManager(
        context = application,
        executor = rootCommandExecutor,
        proxyPort = LocalProxyState.DEFAULT_PROXY_PORT
    )
}
