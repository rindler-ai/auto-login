// RelayService — the always-on relay. It holds the hub WebSocket in a foreground
// service (not the Activity) so it survives the app being closed or swiped away,
// restarts if the OS kills it (START_STICKY), and comes back after reboot (see
// BootReceiver). This is what lets the user set up once and never reopen the app.
//
// Release goes through AutoApprover: a ping that passes verification (server signature,
// live-device auth, account scope, site match, replay guard, HPKE seal) is served
// unconditionally — no human tap, no setting. That verification IS the authorization;
// see AutoApprover for why each layer makes an unattended release safe. This is what
// makes the "bind once, close the app, serve the agent forever" guarantee real.

package ai.rindler.autologin

import ai.rindler.autologin.sms.SmsCodeExpectationSink
import ai.rindler.mobile.EgressSession
import ai.rindler.mobile.Mobile
import ai.rindler.mobile.Session
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RelayService : Service() {

    private var session: Session? = null
    private var egress: EgressSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (session == null) startRelay()
        reconcileEgress()
        // Reflect whether the relay ACTUALLY started: startRelay leaves session null
        // when the device isn't paired (stopSelf) or Mobile.start throws. Setting this
        // unconditionally would make Home show "Active" while nothing is relaying.
        isRunning = session != null
        // SMS auto-read needs nothing armed here: it is a manifest-declared SmsReceiver
        // that the OS delivers to (gated by the opt-in flag + RECEIVE_SMS). The relay only
        // needs to stay up so the process is alive to forward a code when a text arrives.
        return START_STICKY
    }

    private fun startRelay() {
        val store = KeystoreSecretSource(applicationContext)
        val token = store.deviceToken()
        val key = store.deviceKeyB64()
        // serverPubkey authenticates every incoming ping before the Go core
        // seals a credential to the worker key it names. A device without one (paired
        // before this change) can verify nothing and would decline every ping, so do
        // not hold a relay open for it — stop, exactly as for an unpaired device. The
        // user re-pairs; PairScreen stores all three together.
        val serverPubkey = store.serverPubkeyB64()
        if (token == null || key == null || serverPubkey.isNullOrEmpty()) {
            stopSelf() // not paired (or paired before this change) — nothing we can relay
            return
        }
        // Reconnect to the SAME hub the user paired against (stored at pairing time),
        // falling back to the build-time default when unset (a branded build ships a
        // real hub; a self-host build ships a placeholder the pairing screen replaces).
        val hub = store.hubUrl() ?: BuildConfig.HUB_URL
        session = try {
            // The sink arms SmsExpectation from the authenticated sms_otp_code ping, so
            // the SMS reader only ever inspects a text while a login awaits a code.
            Mobile.start(
                hub, token, key, serverPubkey, store,
                AutoApprover, SmsCodeExpectationSink(applicationContext),
            )
        } catch (e: Exception) {
            null
        }
    }

    // Device-egress proxy: run the tunnel egress iff the toggle is ON and a token
    // is linked. Reconciled on every onStartCommand, so toggling (which calls
    // ensureRunning) starts/stops egress WITHOUT disturbing the hub relay. isEgressActive
    // reflects the live state for Home. Errors leave egress null (Home shows off).
    private fun reconcileEgress() {
        val store = KeystoreSecretSource(applicationContext)
        val creds = store.egressCredentials()
        val shouldRun = store.isEgressEnabled() && creds != null
        when {
            shouldRun && egress == null -> {
                egress = try {
                    Mobile.startEgress(creds!!.gateway, creds.token, Build.MODEL ?: "device")
                } catch (e: Exception) {
                    null
                }
            }
            !shouldRun && egress != null -> {
                egress?.stop()
                egress = null
            }
        }
        // isEgressActive means "a session object exists"; egressConnected() reads the
        // go-core's LIVE handshake state so the UI can show a truthful on/connecting.
        liveEgress = egress
        isEgressActive = egress != null
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Auto-Login relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Serves your saved logins to the hub, hands-free"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Auto-Login is active")
            .setContentText("Ready to hand your logins to the hub on demand — no need to open the app")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        egress?.stop()
        egress = null
        liveEgress = null
        isRunning = false
        isEgressActive = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "custody_relay"
        private const val NOTIF_ID = 1

        /** Whether the relay is live (drives the Home status). Best-effort. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Whether the device-egress tunnel is live (drives the egress status). */
        @Volatile
        var isEgressActive: Boolean = false
            private set

        // The live egress session, so the UI can poll its true handshake state.
        @Volatile
        private var liveEgress: EgressSession? = null

        /** Whether the device egress tunnel is actually CONNECTED right now (the
         *  gateway handshake succeeded), not merely started. Drives a truthful
         *  Settings status. false when connecting, reconnecting, or off. */
        fun egressConnected(): Boolean = liveEgress?.connected() ?: false

        /**
         * Start the relay if the device is paired AND holds the server's ping-signing
         * key. Without that key startRelay would immediately stopSelf, so this
         * skips the pointless foreground-notification flash. Safe to call repeatedly.
         */
        fun ensureRunning(ctx: Context) {
            val store = KeystoreSecretSource(ctx.applicationContext)
            if (store.deviceToken() == null || store.serverPubkeyB64().isNullOrEmpty()) return
            val i = Intent(ctx, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RelayService::class.java))
        }
    }
}
