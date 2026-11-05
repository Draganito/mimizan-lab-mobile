package ch.bojovic.mimizanlab.store

import android.content.Context

/** The Play Store build asks Play Billing. */
fun createEntitlements(context: Context): Entitlements = PlayEntitlements(context)
