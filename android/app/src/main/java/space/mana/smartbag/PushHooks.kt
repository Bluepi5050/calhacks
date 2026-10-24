package space.mana.smartbag

/**
 * Optional Firebase Cloud Messaging. The fcm source set (compiled only when app/google-services.json exists) installs
 * these at startup; without it they stay null and Bag It relies on local notifications only.
 */
object PushHooks {
    @Volatile var tokenFetcher: ((onToken: (String?) -> Unit) -> Unit)? = null
    val available: Boolean get() = tokenFetcher != null
}
