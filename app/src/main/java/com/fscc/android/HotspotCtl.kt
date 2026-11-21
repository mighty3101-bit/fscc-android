package com.fscc.android

import android.os.Looper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object HotspotCtl {
    @JvmStatic
    fun main(args: Array<String>) {
        val cmd = args.firstOrNull() ?: "start"
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("systemMain").invoke(null)
            val ctx = atClass.getMethod("getSystemContext").invoke(at)
            val getSysSvc = Class.forName("android.content.Context")
                .getMethod("getSystemService", String::class.java)
            val tm = getSysSvc.invoke(ctx, "tethering")
            if (tm == null) {
                System.err.println("TetheringManager null")
                System.exit(2)
            }
            val tmClass = tm!!.javaClass
            if (cmd == "stop") {
                tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, 0)
                Thread.sleep(500)
                System.exit(0)
            } else {
                val reqClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
                val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
                runCatching {
                    builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                        .invoke(builder, true)
                }
                runCatching {
                    builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                        .invoke(builder, false)
                }
                val req = builderClass.getMethod("build").invoke(builder)

                val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                val latch = CountDownLatch(1)
                val errMsg = AtomicReference<String?>(null)
                val cb = Proxy.newProxyInstance(tmClass.classLoader, arrayOf(cbClass)) { _, method, mArgs ->
                    when (method.name) {
                        "onTetheringStarted" -> latch.countDown()
                        "onTetheringFailed" -> {
                            val code = mArgs?.firstOrNull()?.toString() ?: "?"
                            errMsg.set("onTetheringFailed($code)")
                            latch.countDown()
                        }
                    }
                    null
                }
                val exec = Executor { r -> r.run() }
                tmClass.getMethod(
                    "startTethering",
                    reqClass,
                    Executor::class.java,
                    cbClass
                ).invoke(tm, req, exec, cb)

                latch.await(8, TimeUnit.SECONDS)
                val e = errMsg.get()
                if (e != null) {
                    System.err.println(e)
                    System.exit(3)
                }
                System.exit(0)
            }
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            System.exit(1)
        }
    }
}
