// The one thing the store app sells: the print at full size. Everything
// else — the camera, the finder, the developer, the plate, the roll, the
// 1:1 view, the JPEG at half the edge — is free. The purchase is a
// non-consumable through StoreKit 2, shared with the Mac through the
// same App Store record (universal purchase; the Mac does not ask yet).
// The last known answer is kept in UserDefaults so an export does not
// wait on the network at launch; `Transaction.currentEntitlements` is
// the truth and corrects it on every start.
import Foundation
import Observation
import os
import StoreKit

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "store")

@MainActor
@Observable
final class Entitlements {
    /// The product in App Store Connect: non-consumable, Family Sharing on.
    static let productID = "ch.bojovic.mimizanlab.fullres"

    /// The full-size JPEG and the two TIFFs are unlocked.
    private(set) var fullResolution: Bool
    /// The store's product, once loaded; nil offline or before the load.
    private(set) var product: Product?
    /// A purchase or restore is under way.
    private(set) var busy = false
    /// What happened last, for the screen.
    var message: String?

    private let store = UserDefaults.standard
    private var updates: Task<Void, Never>?

    init() {
        fullResolution = store.bool(forKey: Entitlements.key)
        updates = Task { [weak self] in
            // Purchases made elsewhere (the Mac, Family Sharing, a refund) arrive here.
            for await result in Transaction.updates {
                guard let self else { return }
                switch result {
                case let .verified(t):
                    await t.finish()
                    await self.refresh()
                case let .unverified(t, error):
                    // Never granted, but finished, or the store delivers it again on every launch.
                    log.warning("unverified transaction \(t.id): \(error.localizedDescription)")
                    await t.finish()
                }
            }
        }
        Task {
            await refresh()
            await loadProduct()
        }
    }

    /// The price as the store shows it in the user's currency; nil until loaded.
    var price: String? { product?.displayPrice }

    /// Ask the store what this account owns.
    func refresh() async {
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case let .verified(t) = result, t.productID == Entitlements.productID, t.revocationDate == nil {
                owned = true
            }
        }
        if owned != fullResolution {
            fullResolution = owned
            store.set(owned, forKey: Entitlements.key)
            log.info("full resolution: \(owned ? "owned" : "not owned")")
        }
    }

    func loadProduct() async {
        do {
            product = try await Product.products(for: [Entitlements.productID]).first
            if product == nil { log.warning("product \(Entitlements.productID) not found in the store") }
        } catch {
            log.warning("products: \(error.localizedDescription)")
        }
    }

    func purchase() async {
        guard !busy else { return }
        if product == nil { await loadProduct() }
        guard let product else {
            message = "The store did not answer. Try again in a moment."
            return
        }
        busy = true
        defer { busy = false }
        do {
            switch try await product.purchase() {
            case let .success(verification):
                switch verification {
                case let .verified(t):
                    await t.finish()
                    await refresh()
                    message = "Full resolution unlocked. Thank you."
                case let .unverified(t, error):
                    log.warning("unverified purchase \(t.id): \(error.localizedDescription)")
                    await t.finish()
                    message = "The purchase could not be verified."
                }
            case .userCancelled:
                break
            case .pending:
                message = "The purchase is waiting for approval."
            @unknown default:
                break
            }
        } catch {
            log.error("purchase: \(error.localizedDescription)")
            message = error.localizedDescription
        }
    }

    /// Apple's required "Restore Purchases": sync with the account, then look again.
    func restore() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await AppStore.sync()
            await refresh()
            message = fullResolution ? "Full resolution restored." : "No purchase found on this account."
        } catch {
            log.error("restore: \(error.localizedDescription)")
            message = error.localizedDescription
        }
    }

    private static let key = "entitlement_fullres"
}
