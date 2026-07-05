package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Associates a transparent (public) wallet with an MWEB (private) wallet so the two can present a
 * unified public/private experience (combined balance, one-click peg buttons). The pairing is stored
 * per-wallet in app {@link Config} as a wallet-id to wallet-id mapping - no key material crosses
 * between wallets, and each wallet still signs only its own side of a peg.
 *
 * The linked MWEB wallet may be the same-seed child of the public wallet (created via Enable MWEB), or
 * a completely separate wallet with its own seed that the user chooses to associate. For separate-seed
 * links both wallets must be open for the combined balance and peg-out to work.
 */
public class WalletLink {
    public static String getWalletId(Wallet wallet) {
        Storage storage = AppServices.get().getOpenWallets().get(wallet);
        return storage == null ? null : storage.getWalletId(wallet);
    }

    /**
     * Whether this wallet has a configured public/private association, regardless of whether the
     * counterpart wallet is currently open.
     */
    public static boolean isLinked(Wallet wallet) {
        return getLinkedCounterpartId(wallet) != null;
    }

    /**
     * The configured id of this wallet's linked counterpart, or null if there is no association.
     */
    public static String getLinkedCounterpartId(Wallet wallet) {
        String walletId = getWalletId(wallet);
        if(walletId == null) {
            return null;
        }
        if(wallet.getScriptType() == ScriptType.MWEB) {
            return Config.get().getPublicWalletIdForMwebWalletId(walletId);
        }
        return Config.get().getLinkedMwebWalletId(walletId);
    }

    /**
     * The linked counterpart wallet if it is currently open, otherwise null (the association may still exist).
     */
    public static Wallet getLinkedCounterpart(Wallet wallet) {
        String counterpartId = getLinkedCounterpartId(wallet);
        return counterpartId == null ? null : AppServices.get().getWallet(counterpartId);
    }

    /**
     * Resolves the public (transparent) side of this wallet's pairing, whichever wallet is passed.
     * Returns null if not linked or the public side is not currently open.
     */
    public static Wallet getPublicWallet(Wallet wallet) {
        return wallet.getScriptType() == ScriptType.MWEB ? getLinkedCounterpart(wallet) : (isLinked(wallet) ? wallet : null);
    }

    /**
     * Resolves the MWEB (private) side of this wallet's pairing, whichever wallet is passed.
     * Returns null if not linked or the MWEB side is not currently open.
     */
    public static Wallet getMwebWallet(Wallet wallet) {
        return wallet.getScriptType() == ScriptType.MWEB ? (isLinked(wallet) ? wallet : null) : getLinkedCounterpart(wallet);
    }

    /**
     * Open MWEB wallets that the given public wallet could be associated with, including its same-seed child.
     */
    public static List<Wallet> getCandidateMwebWallets(Wallet publicWallet) {
        List<Wallet> candidates = new ArrayList<>();
        for(Wallet openWallet : AppServices.get().getOpenWallets().keySet()) {
            if(openWallet.getScriptType() == ScriptType.MWEB) {
                candidates.add(openWallet);
            }
        }
        return candidates;
    }

    /**
     * Whether the given MWEB wallet is the same-seed child of the given public wallet.
     */
    public static boolean isSameSeed(Wallet publicWallet, Wallet mwebWallet) {
        return mwebWallet.getMasterWallet() == publicWallet || publicWallet.getMwebChildWallet() == mwebWallet;
    }

    public static void link(Wallet publicWallet, Wallet mwebWallet) {
        String publicId = getWalletId(publicWallet);
        String mwebId = getWalletId(mwebWallet);
        if(publicId != null && mwebId != null) {
            Config.get().setLinkedMwebWallet(publicId, mwebId);
        }
    }

    public static void unlink(Wallet wallet) {
        String walletId = getWalletId(wallet);
        if(walletId == null) {
            return;
        }
        if(wallet.getScriptType() == ScriptType.MWEB) {
            String publicId = Config.get().getPublicWalletIdForMwebWalletId(walletId);
            if(publicId != null) {
                Config.get().removeLinkedMwebWallet(publicId);
            }
        } else {
            Config.get().removeLinkedMwebWallet(walletId);
        }
    }
}
