package com.genymobile.scrcpy

import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import com.genymobile.scrcpy.AsyncProcessor.TerminationListener
import com.genymobile.scrcpy.audio.AudioCapture
import com.genymobile.scrcpy.audio.AudioCodec
import com.genymobile.scrcpy.audio.AudioDirectCapture
import com.genymobile.scrcpy.audio.AudioEncoder
import com.genymobile.scrcpy.audio.AudioPlaybackCapture
import com.genymobile.scrcpy.audio.AudioRawRecorder
import com.genymobile.scrcpy.control.Controller
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.DesktopConnection
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.Streamer
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.video.CameraCapture
import com.genymobile.scrcpy.video.NewDisplayCapture
import com.genymobile.scrcpy.video.ScreenCapture
import com.genymobile.scrcpy.video.SurfaceCapture
import com.genymobile.scrcpy.video.SurfaceEncoder
import com.genymobile.scrcpy.video.VideoSource
import com.soug.mm.base.config.PortConfig
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.io.IOException

object Server {
    @JvmField
    val SERVER_PATH: String?

    init {
        val classPaths: Array<String?> =
            System.getProperty("java.class.path").split(File.pathSeparator.toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
        // By convention, scrcpy is always executed with the absolute path of scrcpy-server.jar as the first item in the classpath
        SERVER_PATH = classPaths[0]
    }

    /**
     * 开启http server
     */
    private fun startHttpServer() {
        embeddedServer(Netty, PortConfig.HTTP_PORT) {
            routing {
                get("/") {
                    call.respondText("Hello, world!2\n", ContentType.Text.Html)
                }
            }
        }.start(wait = true)
    }

    @Throws(IOException::class, ConfigurationException::class)
    private fun scrcpy(options: Options) {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12 && options.getVideoSource() == VideoSource.CAMERA) {
            Ln.e("Camera mirroring is not supported before Android 12")
            throw ConfigurationException("Camera mirroring is not supported")
        }

        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            if (options.getNewDisplay() != null) {
                Ln.e("New virtual display is not supported before Android 10")
                throw ConfigurationException("New virtual display is not supported")
            }
            if (options.getDisplayImePolicy() != -1) {
                Ln.e("Display IME policy is not supported before Android 10")
                throw ConfigurationException("Display IME policy is not supported")
            }
        }

        var cleanUp: CleanUp? = null

        if (options.getCleanup()) {
            cleanUp = CleanUp.start(options)
        }

        val scid = options.getScid()
        val tunnelForward = options.isTunnelForward()
        val control = options.getControl()
        val video = options.getVideo()
        val audio = options.getAudio()
        val sendDummyByte = options.getSendDummyByte()

        Workarounds.apply()

        val asyncProcessors: MutableList<AsyncProcessor> = ArrayList<AsyncProcessor>()

        val connection =
            DesktopConnection.open(scid, tunnelForward, video, audio, control, sendDummyByte)
        try {
            if (options.getSendDeviceMeta()) {
                connection.sendDeviceMeta(Device.getDeviceName())
            }

            var controller: Controller? = null

            if (control) {
                val controlChannel = connection.getControlChannel()
                controller = Controller(controlChannel, cleanUp, options)
                asyncProcessors.add(controller)
            }

            if (audio) {
                val audioCodec = options.getAudioCodec()
                val audioSource = options.getAudioSource()
                val audioCapture: AudioCapture?
                if (audioSource.isDirect()) {
                    audioCapture = AudioDirectCapture(audioSource)
                } else {
                    audioCapture = AudioPlaybackCapture(options.getAudioDup())
                }

                val audioStreamer = Streamer(
                    connection.getAudioFd(),
                    audioCodec,
                    options.getSendCodecMeta(),
                    options.getSendFrameMeta()
                )
                val audioRecorder: AsyncProcessor?
                if (audioCodec == AudioCodec.RAW) {
                    audioRecorder = AudioRawRecorder(audioCapture, audioStreamer)
                } else {
                    audioRecorder = AudioEncoder(audioCapture, audioStreamer, options)
                }
                asyncProcessors.add(audioRecorder)
            }

            if (video) {
                val videoStreamer = Streamer(
                    connection.getVideoFd(), options.getVideoCodec(), options.getSendCodecMeta(),
                    options.getSendFrameMeta()
                )
                val surfaceCapture: SurfaceCapture?
                if (options.getVideoSource() == VideoSource.DISPLAY) {
                    val newDisplay = options.getNewDisplay()
                    if (newDisplay != null) {
                        surfaceCapture = NewDisplayCapture(controller, options)
                    } else {
                        assert(options.getDisplayId() != Device.DISPLAY_ID_NONE)
                        surfaceCapture = ScreenCapture(controller, options)
                    }
                } else {
                    surfaceCapture = CameraCapture(options)
                }
                val surfaceEncoder = SurfaceEncoder(surfaceCapture, videoStreamer, options)
                asyncProcessors.add(surfaceEncoder)

                if (controller != null) {
                    controller.setSurfaceCapture(surfaceCapture)
                }
            }

            val completion = Completion(asyncProcessors.size)
            for (asyncProcessor in asyncProcessors) {
                asyncProcessor.start(TerminationListener { fatalError: Boolean ->
                    completion.addCompleted(fatalError)
                })
            }

            Looper.loop() // interrupted by the Completion implementation
        } finally {
            if (cleanUp != null) {
                cleanUp.interrupt()
            }
            for (asyncProcessor in asyncProcessors) {
                asyncProcessor.stop()
            }

            OpenGLRunner.quit() // quit the OpenGL thread, if any

            connection.shutdown()

            try {
                if (cleanUp != null) {
                    cleanUp.join()
                }
                for (asyncProcessor in asyncProcessors) {
                    asyncProcessor.join()
                }
                OpenGLRunner.join()
            } catch (e: InterruptedException) {
                // ignore
            }

            connection.close()
        }
    }

    private fun prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true
        Looper.prepare()
        synchronized(Looper::class.java) {
            try {
                @SuppressLint("DiscouragedPrivateApi") val field =
                    Looper::class.java.getDeclaredField("sMainLooper")
                field.setAccessible(true)
                field.set(null, Looper.myLooper())
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var status = 0
        try {
            internalMain(*args)
        } catch (t: Throwable) {
            Ln.e(t.message, t)
            status = 1
        } finally {
            // By default, the Java process exits when all non-daemon threads are terminated.
            // The Android SDK might start some non-daemon threads internally, preventing the scrcpy server to exit.
            // So force the process to exit explicitly.
            System.exit(status)
        }
    }

    @Throws(Exception::class)
    private fun internalMain(vararg args: String?) {
        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { t: Thread?, e: Throwable? ->
            Ln.e("Exception on thread " + t, e)
        })

        prepareMainLooper()

        val options = Options.parse(*args)

        Ln.disableSystemStreams()
        Ln.initLogLevel(options.getLogLevel())

        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")")

        if (options.getList()) {
            if (options.getCleanup()) {
                CleanUp.unlinkSelf()
            }

            if (options.getListEncoders()) {
                Ln.i(LogUtils.buildVideoEncoderListMessage())
                Ln.i(LogUtils.buildAudioEncoderListMessage())
            }
            if (options.getListDisplays()) {
                Ln.i(LogUtils.buildDisplayListMessage())
            }
            if (options.getListCameras() || options.getListCameraSizes()) {
                Workarounds.apply()
                Ln.i(LogUtils.buildCameraListMessage(options.getListCameraSizes()))
            }
            if (options.getListApps()) {
                Workarounds.apply()
                Ln.i("Processing Android apps... (this may take some time)")
                Ln.i(LogUtils.buildAppListMessage())
            }
            // Just print the requested data, do not mirror
            return
        }

        try {
            startHttpServer()
//            scrcpy(options)
        } catch (e: ConfigurationException) {
            // Do not print stack trace, a user-friendly error-message has already been logged
        }
    }

    private class Completion(private var running: Int) {
        private var fatalError = false

        @Synchronized
        fun addCompleted(fatalError: Boolean) {
            --running
            if (fatalError) {
                this.fatalError = true
            }
            if (running == 0 || this.fatalError) {
                Looper.getMainLooper().quitSafely()
            }
        }
    }
}
