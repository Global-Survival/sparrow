package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.wallet.WalletModel;

public class SeedSigner extends SpecterDIY {
    @Override
    public String getName() {
        return "SeedSigner";
    }

    @Override
    public String getKeystoreImportDescription() {
        return "Import QR created on your SeedSigner by selecting Generate XPUB in the Signing Tools menu. Note that SeedSigner currently only supports P2WSH Multisig wallets.";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.SEEDSIGNER;
    }
}
