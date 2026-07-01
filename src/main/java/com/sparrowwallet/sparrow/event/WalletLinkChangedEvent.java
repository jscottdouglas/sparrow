package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.Wallet;

/**
 * Posted when a public/private (transparent/MWEB) wallet association is created or removed, so open
 * wallet views can refresh their unified balance and peg buttons.
 */
public class WalletLinkChangedEvent {
    private final Wallet wallet;

    public WalletLinkChangedEvent(Wallet wallet) {
        this.wallet = wallet;
    }

    public Wallet getWallet() {
        return wallet;
    }
}
