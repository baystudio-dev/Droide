package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.baystudio.droide.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class NineRouterAndroidPolicyTest {
    @Test fun packagedRulesPermitThePinnedTransportAndLoopbackOnly() {
        val app = RuntimeEnvironment.getApplication()
        // The platform's XML parser and domain matcher are hidden from the public Android SDK.
        // Construct them with the actual app resource instead of using Robolectric's policy shadow.
        val source = Class.forName("android.security.net.config.XmlConfigSource")
            .getConstructor(Context::class.java, Int::class.javaPrimitiveType, ApplicationInfo::class.java)
            .newInstance(app, R.xml.network_security_config, app.applicationInfo)
        val config = Class.forName("android.security.net.config.ApplicationConfig")
            .getConstructor(Class.forName("android.security.net.config.ConfigSource")).newInstance(source)
        val permitted = config.javaClass.getMethod("isCleartextTrafficPermitted", String::class.java)
        fun allows(host: String) = permitted.invoke(config, host) as Boolean

        val provider = NineRouterProvider.defaults.copy(baseUrl = "http://192.168.100.130:20128/v1").provider()
        val target = NetworkSecurity.validateProviderTarget(provider, provider.baseUrl + "/models")
        val outbound = target.newProviderCall(OkHttpClient(), Request.Builder().url(target.url).build()).request()
        assertTrue(allows(outbound.url.host))
        assertTrue(allows("127.0.0.1"))
        for (host in listOf(target.host, "8.8.8.8", "example.com", "other.invalid", "nine-router-lan.invalid.example.com")) {
            assertFalse(host, allows(host))
        }
    }
}
