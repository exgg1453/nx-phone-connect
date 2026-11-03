package com.nxteam.nxconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class HostService : Service() {
    @Volatile
    private var authenticatedClient: FramedSocket? = null
    private var serverSocket: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var pairingCode = ""
    private var lastFrameTime = 0L
    private var paddedBitmap: Bitmap? = null
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val isSendingFrame = AtomicBoolean(false)
    private val isAwaitingAcknowledgement = AtomicBoolean(false)
    private var captureHandler: Handler? = null
    private var hasPendingFrame = false
    private var isFlushScheduled = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty()
        startForegroundNotification()

        @Suppress("DEPRECATION")
        val permissionData = intent.getParcelableExtra<Intent>(EXTRA_PERMISSION_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (permissionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val thread = HandlerThread("NXPhoneConnectCapture")
        thread.start()
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(resultCode, permissionData)
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = newProjection

        startServer()
        startCapture(newProjection, handler)
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        registrationListener?.let {
            try {
                getSystemService(NsdManager::class.java).unregisterService(it)
            } catch (exception: IllegalArgumentException) {
            }
        }
        authenticatedClient?.close()
        authenticatedClient = null
        try {
            serverSocket?.close()
        } catch (exception: IOException) {
        }
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        frameExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_IDENTIFIER, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_IDENTIFIER)
            .setContentTitle("NX Phone Connect")
            .setContentText("Sharing this device")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_IDENTIFIER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_IDENTIFIER, notification)
        }
    }

    private fun startServer() {
        val newServerSocket = ServerSocket(0)
        serverSocket = newServerSocket

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = Build.MODEL
            serviceType = "_nxconnect._tcp."
            port = newServerSocket.localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        getSystemService(NsdManager::class.java).registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        Thread {
            while (true) {
                val socket = try {
                    newServerSocket.accept()
                } catch (exception: IOException) {
                    break
                }
                Thread { serve(FramedSocket(socket)) }.start()
            }
        }.start()
    }

    private fun serve(client: FramedSocket) {
        client.setReadTimeout(HANDSHAKE_TIMEOUT)
        val first = client.readMessage()
        val receivedCode = first?.second?.toString(Charsets.UTF_8)
        if (first?.first != MessageKind.HELLO || receivedCode != pairingCode) {
            client.send(MessageKind.REJECTED)
            client.close()
            return
        }
        client.setReadTimeout(0)
        authenticatedClient?.close()
        isAwaitingAcknowledgement.set(false)
        authenticatedClient = client
        client.send(MessageKind.ACCEPTED)

        while (true) {
            val message = client.readMessage() ?: break
            if (message.first == MessageKind.FRAME_ACKNOWLEDGED) {
                isAwaitingAcknowledgement.set(false)
                captureHandler?.post { flushPendingFrame() }
                continue
            }
            val service = RemoteControlAccessibilityService.instance ?: continue
            try {
                val json = JSONObject(message.second.toString(Charsets.UTF_8))
                when (message.first) {
                    MessageKind.TOUCH -> service.handleTouch(
                        json.getString("phase"),
                        json.getDouble("normalizedHorizontalPosition").toFloat(),
                        json.getDouble("normalizedVerticalPosition").toFloat()
                    )
                    MessageKind.KEY -> service.handleKey(json.getString("name"))
                    else -> {}
                }
            } catch (exception: org.json.JSONException) {
            }
        }
        if (authenticatedClient === client) {
            authenticatedClient = null
        }
        client.close()
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val longSide = max(metrics.widthPixels, metrics.heightPixels).toFloat()
        val scale = minOf(1f, MAXIMUM_FRAME_LONG_SIDE / longSide)
        val width = (metrics.widthPixels * scale).roundToInt() and 1.inv()
        val height = (metrics.heightPixels * scale).roundToInt() and 1.inv()
        return Triple(width, height, metrics.densityDpi)
    }

    private fun createReader(width: Int, height: Int, handler: Handler): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            if (source !== imageReader) {
                return@setOnImageAvailableListener
            }
            if (authenticatedClient == null) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            hasPendingFrame = true
            flushPendingFrame()
        }, handler)
        return reader
    }

    private fun startCapture(activeProjection: MediaProjection, handler: Handler) {
        val (width, height, density) = captureSize()
        captureWidth = width
        captureHeight = height
        val reader = createReader(width, height, handler)
        imageReader = reader
        virtualDisplay = activeProjection.createVirtualDisplay(
            "NXPhoneConnect",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    resizeCapture(handler)
                }
            }
        }
        displayListener = listener
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, handler)
    }

    private fun resizeCapture(handler: Handler) {
        val display = virtualDisplay ?: return
        val (width, height, density) = captureSize()
        if (width == captureWidth && height == captureHeight) {
            return
        }
        captureWidth = width
        captureHeight = height
        val oldReader = imageReader
        val reader = createReader(width, height, handler)
        imageReader = reader
        display.resize(width, height, density)
        display.surface = reader.surface
        oldReader?.close()
        paddedBitmap?.recycle()
        paddedBitmap = null
        hasPendingFrame = true
        flushPendingFrame()
    }

    private fun flushPendingFrame() {
        val client = authenticatedClient ?: return
        val reader = imageReader ?: return
        if (!hasPendingFrame || isSendingFrame.get()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFrameTime
        var wait = MINIMUM_FRAME_INTERVAL - elapsed
        if (isAwaitingAcknowledgement.get()) {
            wait = maxOf(wait, ACKNOWLEDGEMENT_TIMEOUT - elapsed)
        }
        if (wait > 0) {
            if (!isFlushScheduled) {
                isFlushScheduled = true
                captureHandler?.postDelayed({
                    isFlushScheduled = false
                    flushPendingFrame()
                }, wait)
            }
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (exception: IllegalStateException) {
            null
        }
        if (image == null) {
            if (paddedBitmap == null) {
                return
            }
        }
        hasPendingFrame = false
        lastFrameTime = now

        if (image != null) {
            try {
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val bitmap = paddedBitmap?.takeIf { it.width == paddedWidth && it.height == image.height }
                    ?: Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).also {
                        paddedBitmap?.recycle()
                        paddedBitmap = it
                    }
                bitmap.copyPixelsFromBuffer(plane.buffer)
            } finally {
                image.close()
            }
        }

        val bitmap = paddedBitmap ?: return
        val frameWidth = minOf(captureWidth, bitmap.width)
        val frameHeight = minOf(captureHeight, bitmap.height)
        val frameBitmap = if (bitmap.width == frameWidth && bitmap.height == frameHeight) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, frameWidth, frameHeight)
        }
        val stream = ByteArrayOutputStream()
        frameBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (frameBitmap !== bitmap) {
            frameBitmap.recycle()
        }
        val frameData = stream.toByteArray()
        isSendingFrame.set(true)
        isAwaitingAcknowledgement.set(true)
        frameExecutor.execute {
            client.send(MessageKind.FRAME, frameData)
            isSendingFrame.set(false)
            captureHandler?.post { flushPendingFrame() }
        }
    }

    companion object {
        const val ACTION_STOP = "com.nxteam.nxconnect.STOP"
        const val EXTRA_PAIRING_CODE = "pairingCode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PERMISSION_DATA = "permissionData"

        @Volatile
        var isRunning = false

        private const val CHANNEL_IDENTIFIER = "screen_sharing"
        private const val NOTIFICATION_IDENTIFIER = 1
        private const val MAXIMUM_FRAME_LONG_SIDE = 1280f
        private const val MINIMUM_FRAME_INTERVAL = 50L
        private const val ACKNOWLEDGEMENT_TIMEOUT = 1000L
        private const val JPEG_QUALITY = 40
        private const val HANDSHAKE_TIMEOUT = 10_000
    }
}
