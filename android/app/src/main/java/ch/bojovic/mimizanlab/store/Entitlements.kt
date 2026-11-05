package ch.bojovic.mimizanlab.store

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app may export. The camera, the finder, the developer, the
 * plate, the roll and the JPEG at half the edge are free everywhere; the
 * JPEG at full size, the 16-bit TIFF and the linear negative are the one
 * purchase in the Play Store app. The `github` flavor owns everything
 * ([Owned]); the `play` flavor asks Play Billing. The flavor's
 * `createEntitlements` picks the implementation.
 */
interface Entitlements {
    /** True when the full-size JPEG and the two TIFFs may be written. */
    val fullResolution: StateFlow<Boolean>

    /** The store's localised price once the product is known; null until then and on the free build. */
    val price: StateFlow<String?>

    /** A purchase or restore is under way. */
    val busy: StateFlow<Boolean>

    /** What the last purchase or restore said; the screen shows it once and clears it. */
    val message: MutableStateFlow<String?>

    /** False when there is nothing to buy (the `github` build): the settings hide the section. */
    val storeBound: Boolean

    /** The store's purchase sheet, from the activity that owns the screen. */
    fun purchase(activity: Activity)

    /** Ask the store for a purchase made earlier, on this or another device. */
    fun restore()

    /** Everything owned, nothing to buy. */
    object Owned : Entitlements {
        override val fullResolution = MutableStateFlow(true)
        override val price = MutableStateFlow<String?>(null)
        override val busy = MutableStateFlow(false)
        override val message = MutableStateFlow<String?>(null)
        override val storeBound = false
        override fun purchase(activity: Activity) {}
        override fun restore() {}
    }

    companion object {
        /** The same product on the Play Store and the App Store. */
        const val PRODUCT_ID = "ch.bojovic.mimizanlab.fullres"
    }
}
