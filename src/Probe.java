package com.eni;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Google One Offer Lab — probe.
 *
 * Runs inside the Google One process (injected with frida). Two operations:
 *
 *   run(ctx)                                  read-only: list every offer and pricing phase
 *   activatePromo(act, productId, cycles)     open Play's checkout for the cheapest
 *                                             offer whose first phase lasts `cycles`
 *
 * Nothing here confirms a purchase. launchBillingFlow only opens Play's sheet; a human
 * taps the final button. Logs go to logcat tag ENIPROBE.
 *
 * Build: see scripts/build-dex.sh (billing AAR + this class -> single classes.dex).
 */
public final class Probe {

    private static final String T = "ENIPROBE";

    /**
     * Candidate product IDs. Keep the batch at 18 or fewer: Play's
     * queryProductDetailsAsync caps the product list per call.
     */
    private static final String[] IDS = {
            // AI Pro / AI Plus (5 TB, 2 TB)
            "g1.2tb.ai.subs_variant",
            "g1.play.2tb.ai",
            "g1.2tb.ai",
            "g1.2tb",
            // AI Ultra
            "g1.20tb",
            "g1.20tb.v2",
            "g1.play.20tb",
            "g1.30tb",
            "g1.ai.pro",
            "g1.play.ai.pro",
            "g1.ai.ultra",
            // intro-price / acquisition variants (3-month promos)
            "g1.ai.ultra.3months_ip_90p.acq",
            "g1.ai.ultra.3months_ip_75p.acq",
            "g1.20tb.3months_ip_90p",
            "g1.20tb.3months_ip_80p",
            "g1.20tb.3months_ip_75p.acq",
            "g1.30tb.3months_ip_75p.acq",
            "g1.30tb.3months_ip_90p.acq"
    };

    // ---------------------------------------------------------------- read-only

    /** List every offer of every candidate SKU. Does not open any checkout. */
    public static void run(final Context ctx) {
        Log.i(T, "=== probe start ===");
        final BillingClient client = newClient(ctx);
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                Log.i(T, "setup code=" + r.getResponseCode() + " msg=" + r.getDebugMessage());
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    Log.i(T, "=== probe end (setup failed) ===");
                    return;
                }
                Log.i(T, "isReady=" + client.isReady()
                        + " feat.SUBS=" + client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode()
                        + " feat.PRODUCT_DETAILS=" + client.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS).getResponseCode());

                for (String id : IDS) {
                    queryOne(client, id);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.i(T, "billing disconnected");
            }
        });
    }

    private static void queryOne(BillingClient client, final String id) {
        QueryProductDetailsParams.Product p = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Arrays.asList(p))
                .build();

        client.queryProductDetailsAsync(params, new com.android.billingclient.api.ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult result, List<ProductDetails> list) {
                Log.i(T, "Q[" + id + "] code=" + result.getResponseCode()
                        + " msg=" + result.getDebugMessage()
                        + " n=" + (list == null ? -1 : list.size()));
                if (list == null) return;
                for (ProductDetails pd : list) {
                    dumpProduct(pd);
                }
            }
        });
    }

    private static void dumpProduct(ProductDetails pd) {
        Log.i(T, "  PROD " + pd.getProductId() + " | " + pd.getName());
        List<ProductDetails.SubscriptionOfferDetails> offers = pd.getSubscriptionOfferDetails();
        if (offers == null) {
            Log.i(T, "  (no offers)");
            return;
        }
        Log.i(T, "  offers=" + offers.size());
        for (ProductDetails.SubscriptionOfferDetails o : offers) {
            Log.i(T, "    OFFER base=" + o.getBasePlanId()
                    + " id=" + o.getOfferId()
                    + " tags=" + o.getOfferTags()
                    + " tokenLen=" + (o.getOfferToken() == null ? -1 : o.getOfferToken().length()));
            for (ProductDetails.PricingPhase ph : o.getPricingPhases().getPricingPhaseList()) {
                Log.i(T, "      phase price=" + ph.getFormattedPrice()
                        + " micros=" + ph.getPriceAmountMicros()
                        + " period=" + ph.getBillingPeriod()
                        + " cycles=" + ph.getBillingCycleCount()
                        + " mode=" + ph.getRecurrenceMode());
            }
        }
    }

    // ---------------------------------------------------------------- checkout

    /**
     * Opens Play's checkout for the cheapest offer whose first pricing phase lasts
     * exactly {@code cycles} billing periods (e.g. 3 for a 3-month intro price).
     *
     * Picking by cycle count rather than by offerId is deliberate: offer ids are
     * inconsistent across regions (sometimes "introprice", sometimes null).
     */
    public static void activatePromo(final Activity act, final String productId, final int cycles) {
        Log.i(T, "=== activatePromo " + productId + " cycles=" + cycles + " ===");
        final BillingClient client = newClient(act.getApplicationContext());

        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                Log.i(T, "promo setup code=" + r.getResponseCode());
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;

                QueryProductDetailsParams.Product p = QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build();
                QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                        .setProductList(Arrays.asList(p))
                        .build();

                client.queryProductDetailsAsync(params, new com.android.billingclient.api.ProductDetailsResponseListener() {
                    @Override
                    public void onProductDetailsResponse(BillingResult br, List<ProductDetails> list) {
                        Log.i(T, "promo query code=" + br.getResponseCode()
                                + " n=" + (list == null ? -1 : list.size()));
                        if (list == null || list.isEmpty()) return;

                        final ProductDetails pd = list.get(0);
                        ProductDetails.SubscriptionOfferDetails best = null;
                        long bestPrice = Long.MAX_VALUE;

                        for (ProductDetails.SubscriptionOfferDetails o : pd.getSubscriptionOfferDetails()) {
                            List<ProductDetails.PricingPhase> phases = o.getPricingPhases().getPricingPhaseList();
                            if (phases.isEmpty()) continue;
                            ProductDetails.PricingPhase first = phases.get(0);
                            Log.i(T, "  offer id=" + o.getOfferId()
                                    + " firstPrice=" + first.getFormattedPrice()
                                    + " cycles=" + first.getBillingCycleCount()
                                    + " mode=" + first.getRecurrenceMode());
                            if (first.getBillingCycleCount() == cycles && first.getPriceAmountMicros() < bestPrice) {
                                best = o;
                                bestPrice = first.getPriceAmountMicros();
                            }
                        }

                        if (best == null) {
                            Log.i(T, "NO_OFFER with cycles=" + cycles);
                            return;
                        }
                        Log.i(T, "CHOSEN id=" + best.getOfferId() + " micros=" + bestPrice);

                        final String token = best.getOfferToken();
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                BillingFlowParams.ProductDetailsParams pdp =
                                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                                .setProductDetails(pd)
                                                .setOfferToken(token)
                                                .build();
                                BillingFlowParams flow = BillingFlowParams.newBuilder()
                                        .setProductDetailsParamsList(Arrays.asList(pdp))
                                        .build();
                                BillingResult res = client.launchBillingFlow(act, flow);
                                Log.i(T, "LAUNCH code=" + res.getResponseCode() + " msg=" + res.getDebugMessage());
                            }
                        });
                    }
                });
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.i(T, "promo disconnected");
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static BillingClient newClient(Context ctx) {
        return BillingClient.newBuilder(ctx)
                .setListener(new PurchasesUpdatedListener() {
                    @Override
                    public void onPurchasesUpdated(BillingResult r, List<Purchase> purchases) {
                        Log.i(T, "PURCHASE_UPDATE code=" + r.getResponseCode()
                                + " msg=" + r.getDebugMessage()
                                + " n=" + (purchases == null ? -1 : purchases.size()));
                    }
                })
                .enablePendingPurchases()
                .build();
    }

    private Probe() {
    }
}
