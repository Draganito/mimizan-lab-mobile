package ch.bojovic.mimizanlab.store

import android.content.Context

/** The GitHub build: no store, everything owned. */
@Suppress("UNUSED_PARAMETER")
fun createEntitlements(context: Context): Entitlements = Entitlements.Owned
