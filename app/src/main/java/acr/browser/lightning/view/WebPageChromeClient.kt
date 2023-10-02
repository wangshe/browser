package acr.browser.lightning.view

import acr.browser.lightning.R
import acr.browser.lightning.controller.UIController
import acr.browser.lightning.di.HiltEntryPoint
import acr.browser.lightning.dialog.BrowserDialog
import acr.browser.lightning.dialog.DialogItem
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.favicon.FaviconModel
import acr.browser.lightning.settings.preferences.UserPreferences
import acr.browser.lightning.view.webrtc.WebRtcPermissionsModel
import acr.browser.lightning.view.webrtc.WebRtcPermissionsView
import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.webkit.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.anthonycr.grant.PermissionsManager
import com.anthonycr.grant.PermissionsResultAction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.EntryPointAccessors
import io.reactivex.Scheduler
import timber.log.Timber

class WebPageChromeClient(
    private val activity: Activity,
    private val webPageTab: WebPageTab
) : WebChromeClient(), WebRtcPermissionsView {

    private val geoLocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val uiController: UIController = activity as UIController

    private val hiltEntryPoint = EntryPointAccessors.fromApplication(activity.applicationContext, HiltEntryPoint::class.java)
    val faviconModel: FaviconModel = hiltEntryPoint.faviconModel
    val userPreferences: UserPreferences = hiltEntryPoint.userPreferences
    val webRtcPermissionsModel: WebRtcPermissionsModel = hiltEntryPoint.webRtcPermissionsModel
    val diskScheduler: Scheduler = hiltEntryPoint.diskScheduler()

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (webPageTab.isShown) {
            uiController.updateProgress(newProgress)
        }

        if (newProgress > 10 && webPageTab.fetchMetaThemeColorTries > 0)
        {
            val triesLeft = webPageTab.fetchMetaThemeColorTries - 1
            webPageTab.fetchMetaThemeColorTries = 0

            // Extract meta theme-color
            // TODO: Make this optional
            view.evaluateJavascript("(function() { " +
                    "let e = document.querySelector('meta[name=\"theme-color\"]');" +
                    "if (e==null) return null;" +
                    "return e.content; })();") { themeColor ->
                try {
                    webPageTab.htmlMetaThemeColor = Color.parseColor(themeColor.trim('\'').trim('"'));
                    // We did find a valid theme-color, tell our controller about it
                    uiController.onTabChanged(webPageTab)
                }
                catch (e: Exception) {
                    if (triesLeft==0 || newProgress==100)
                    {
                        // Exhausted all our tries or the page finished loading before we did
                        // Just give up then and reset our theme color
                        webPageTab.htmlMetaThemeColor = WebPageTab.KHtmlMetaThemeColorInvalid
                        uiController.onTabChanged(webPageTab)
                    }
                    else
                    {
                        // Try it again next time around
                        webPageTab.fetchMetaThemeColorTries = triesLeft
                    }
                }
            }
        }
    }

    /**
     * Called once the favicon is ready
     */
    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        webPageTab.titleInfo.setFavicon(icon)
        uiController.onTabChangedIcon(webPageTab)
        cacheFavicon(view.url, icon)
    }

    /**
     * Naive caching of the favicon according to the domain name of the URL
     *
     * @param icon the icon to cache
     */
    private fun cacheFavicon(url: String?, icon: Bitmap?) {
        if (icon == null || url == null) {
            return
        }

        faviconModel.cacheFaviconForUrl(icon, url)
            .subscribeOn(diskScheduler)
            .subscribe()
    }

    /**
     *
     */
    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (title?.isNotEmpty() == true) {
            webPageTab.titleInfo.setTitle(title)
        } else {
            webPageTab.titleInfo.setTitle(activity.getString(R.string.untitled))
        }
        uiController.onTabChangedTitle(webPageTab)
        if (view != null && view.url != null) {
            uiController.updateHistory(title, view.url as String)
        }

    }

    /**
     * From [WebRtcPermissionsView]
     */
    override fun requestPermissions(permissions: Set<String>, onGrant: (Boolean) -> Unit) {
        val missingPermissions = permissions
            // Filter out the permissions that we don't have
            .filter { !PermissionsManager.getInstance().hasPermission(activity, it) }

        if (missingPermissions.isEmpty()) {
            // We got all permissions already, notify caller then
            onGrant(true)
        } else {
            // Ask user for the missing permissions
            PermissionsManager.getInstance().requestPermissionsIfNecessaryForResult(
                activity,
                missingPermissions.toTypedArray(),
                object : PermissionsResultAction() {
                    override fun onGranted() = onGrant(true)

                    override fun onDenied(permission: String?) = onGrant(false)
                }
            )
        }
    }

    /**
     * From [WebRtcPermissionsView]
     */
    override fun requestResources(source: String,
                                  resources: Array<String>,
                                  onGrant: (Boolean) -> Unit) {
        // Ask user to grant resource access
        activity.runOnUiThread {
            val resourcesString = resources.joinToString(separator = "\n")
            BrowserDialog.showPositiveNegativeDialog(
                aContext = activity,
                title = R.string.title_permission_request,
                message = R.string.message_permission_request,
                messageArguments = arrayOf(source, resourcesString),
                positiveButton = DialogItem(title = R.string.action_allow) { onGrant(true) },
                negativeButton = DialogItem(title = R.string.action_dont_allow) { onGrant(false) },
                onCancel = { onGrant(false) }
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onPermissionRequest(request: PermissionRequest) {
        if (userPreferences.webRtcEnabled) {
            webRtcPermissionsModel.requestPermission(request, this)
        } else {
            //TODO: display warning message as snackbar I guess
            request.deny()
        }
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String,
                                                    callback: GeolocationPermissions.Callback) =
        PermissionsManager.getInstance().requestPermissionsIfNecessaryForResult(activity, geoLocationPermissions, object : PermissionsResultAction() {
            override fun onGranted() {
                val remember = true
                MaterialAlertDialogBuilder(activity).apply {
                    setTitle(activity.getString(R.string.location))
                    val org = if (origin.length > 50) {
                        "${origin.subSequence(0, 50)}..."
                    } else {
                        origin
                    }
                    setMessage(org + activity.getString(R.string.message_location))
                    setCancelable(true)
                    setPositiveButton(activity.getString(R.string.action_allow)) { _, _ ->
                        callback.invoke(origin, true, remember)
                    }
                    setNegativeButton(activity.getString(R.string.action_dont_allow)) { _, _ ->
                        callback.invoke(origin, false, remember)
                    }
                }.resizeAndShow()
            }

            override fun onDenied(permission: String) =//TODO show message and/or turn off setting
                Unit
        })

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                                resultMsg: Message): Boolean {
        // TODO: redo that
        uiController.onCreateWindow(resultMsg)
        //TODO: surely that can't be right,
        return true
        //return false
    }

    override fun onCloseWindow(window: WebView) = uiController.onCloseWindow(webPageTab)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>) = uiController.openFileChooser(uploadMsg)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String) =
        uiController.openFileChooser(uploadMsg)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String, capture: String) =
        uiController.openFileChooser(uploadMsg)

    override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                                   fileChooserParams: FileChooserParams): Boolean {
        uiController.showFileChooser(filePathCallback)
        return true
    }

    /**
     * Obtain an image that is displayed as a placeholder on a video until the video has initialized
     * and can begin loading.
     *
     * @return a Bitmap that can be used as a place holder for videos.
     */
    override fun getDefaultVideoPoster(): Bitmap? {
        Timber.d("getDefaultVideoPoster")
        // TODO: In theory we could even load site specific icons here or just tint that drawable using the site theme color
        val bitmap = AppCompatResources.getDrawable(activity, R.drawable.ic_filmstrip)?.toBitmap(1024,1024)
        if (bitmap==null) {
            Timber.d("Failed to load video poster")
        }
        return bitmap
    }

    /**
     * Inflate a view to send to a [WebPageTab] when it needs to display a video and has to
     * show a loading dialog. Inflates a progress view and returns it.
     *
     * @return A view that should be used to display the state
     * of a video's loading progress.
     */
    override fun getVideoLoadingProgressView(): View {
        // Not sure that's ever being used anymore
        Timber.d("getVideoLoadingProgressView")
        return LayoutInflater.from(activity).inflate(R.layout.video_loading_progress, null)
    }


    override fun onHideCustomView() = uiController.onHideCustomView()

    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        uiController.onShowCustomView(view, callback)

    override fun onShowCustomView(view: View, requestedOrientation: Int,
                                  callback: CustomViewCallback) =
        uiController.onShowCustomView(view, callback, requestedOrientation)

    /**
     * Needed to display javascript console message in logcat.
     */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        // TODO: Collect those in the tab so that we could display them
        consoleMessage?.apply {
            Timber.tag("JavaScript").d("${messageLevel()} - ${message()} -- from line ${lineNumber()} of ${sourceId()}")
        }
        return true
    }

}
