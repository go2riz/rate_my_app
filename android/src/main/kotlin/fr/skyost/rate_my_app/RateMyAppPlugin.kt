package fr.skyost.rate_my_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.NonNull
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Rate my app plugin using Play In-App Review (no play-core).
 */
class RateMyAppPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private var activity: Activity? = null
    private var context: Context? = null
    private lateinit var channel: MethodChannel

    private var reviewInfo: ReviewInfo? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "rate_my_app")
        channel.setMethodCallHandler(this)
        context = binding.applicationContext
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "launchNativeReviewDialog" -> requestReview(result)
            "isNativeDialogSupported" -> {
                val ctx = context
                if (ctx == null) {
                    result.success(false)
                    return
                }
                // Basic constraints + Play Store presence
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || !isPlayStoreInstalled()) {
                    result.success(false)
                } else {
                    cacheReviewInfo(result)
                }
            }
            "launchStore" -> result.success(goToPlayStore(call.argument<String>("appId")))
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    /** Pre-fetch ReviewInfo to know if native dialog is possible. */
    private fun cacheReviewInfo(result: Result) {
        val ctx = context ?: run {
            result.error("context_is_null", "Android context not available.", null)
            return
        }
        val manager = ReviewManagerFactory.create(ctx)
        manager.requestReviewFlow()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    reviewInfo = task.result
                    result.success(true)
                } else {
                    // Not fatal—just means we can’t show native dialog now.
                    result.success(false)
                }
            }
    }

    /** Tries to show native review; falls back to false if not possible. */
    private fun requestReview(result: Result) {
        val ctx = context ?: run {
            result.error("context_is_null", "Android context not available.", null)
            return
        }
        val act = activity ?: run {
            result.error("activity_is_null", "Android activity not available.", null)
            return
        }

        val manager = ReviewManagerFactory.create(ctx)
        val cached = reviewInfo
        if (cached != null) {
            launchReviewFlow(result, manager, cached, act)
            return
        }

        manager.requestReviewFlow()
            .addOnCompleteListener { task ->
                when {
                    task.isSuccessful -> launchReviewFlow(result, manager, task.result, act)
                    task.exception != null -> result.error(
                        task.exception!!.javaClass.name,
                        task.exception!!.localizedMessage,
                        null
                    )
                    else -> result.success(false)
                }
            }
    }

    private fun launchReviewFlow(
        result: Result,
        manager: ReviewManager,
        info: ReviewInfo,
        act: Activity
    ) {
        manager.launchReviewFlow(act, info)
            .addOnCompleteListener { task ->
                reviewInfo = null
                // Note: API doesn’t guarantee a dialog shows; success=true only means the flow completed.
                result.success(task.isSuccessful)
            }
    }

    /** Checks if Play Store app exists (robust to null activity). */
    private fun isPlayStoreInstalled(): Boolean {
        val ctx = activity ?: return false
        return try {
            ctx.packageManager.getPackageInfo("com.android.vending", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Opens Play Store (app or web).
     * Returns: 0 = app opened, 1 = web opened, 2 = failed.
     */
    private fun goToPlayStore(applicationId: String?): Int {
        val act = activity ?: return 2
        val id = applicationId ?: act.applicationContext.packageName

        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
        if (market.resolveActivity(act.packageManager) != null) {
            act.startActivity(market)
            return 0
        }

        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
        if (web.resolveActivity(act.packageManager) != null) {
            act.startActivity(web)
            return 1
        }

        return 2
    }
}
