package name.neuhalfen.projects.crypto.bouncycastle.openpgp.decrypting;


import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.PGPUtilities;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.keyrings.KeyringConfig;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.validation.SignatureValidationStrategy;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.PGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.util.Iterator;

public final class DecryptionStreamFactory {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DecryptionStreamFactory.class);


    @Nonnull
    private final PGPContentVerifierBuilderProvider pgpContentVerifierBuilderProvider = new BcPGPContentVerifierBuilderProvider();

    @Nonnull
    private final KeyringConfig config;

    @Nonnull
    private final SignatureValidationStrategy signatureValidationStrategy;

    public static DecryptionStreamFactory create(final KeyringConfig config, final SignatureValidationStrategy signatureValidationStrategy) {
        if (config == null) {
            throw new IllegalArgumentException("keyring config must not be null");
        }
        if (signatureValidationStrategy == null) {
            throw new IllegalArgumentException("signatureValidationStrategy config must not be null");

        }
        return new DecryptionStreamFactory(config, signatureValidationStrategy);
    }


    private DecryptionStreamFactory(final KeyringConfig config, final SignatureValidationStrategy signatureValidationStrategy) {
        this.signatureValidationStrategy = signatureValidationStrategy;
        this.config = config;
    }

    public InputStream wrapWithDecryptAndVerify(InputStream in) throws IOException, NoSuchProviderException {
        LOGGER.trace("Trying to decrypt and verify PGP Encryption.");
        if (in == null) {
            throw new IllegalArgumentException("in config must not be null");
        }

        try {
            final PGPObjectFactory factory = new PGPObjectFactory(PGPUtil.getDecoderStream(in), config.getKeyFingerPrintCalculator());

            return nextDecryptedStream(factory, new SignatureValidatingInputStream.DecryptionState());

        } catch (PGPException e) {
            throw new IOException("Failure decrypting", e);
        }
    }

    /**
     * Handles PGP objects in decryption process by recursively calling itself.
     *
     * @param factory PGPObjectFactory to access the next objects, might be recreated within this method
     * @param state   Decryption state, e.g. used for signature validation
     * @throws PGPException            the pGP exception
     * @throws IOException             Signals that an I/O exception has occurred.
     * @throws NoSuchProviderException BC provider not registered
     */
    private InputStream nextDecryptedStream(PGPObjectFactory factory, SignatureValidatingInputStream.DecryptionState state) throws PGPException, IOException, NoSuchProviderException {

        Object pgpObj;

        while ((pgpObj = factory.nextObject()) != null) {

            if (pgpObj instanceof PGPEncryptedDataList) {
                LOGGER.trace("Found instance of PGPEncryptedDataList");
                final PGPEncryptedDataList enc = (PGPEncryptedDataList) pgpObj;
                final Iterator<?> it = enc.getEncryptedDataObjects();

                if (!it.hasNext()) {
                    throw new PGPException("Decryption failed - No encrypted data found!");
                }
                //
                // find the secret key
                //
                PGPPrivateKey sKey = null;
                PGPPublicKeyEncryptedData pbe = null;
                while (sKey == null && it.hasNext()) {
                    pbe = (PGPPublicKeyEncryptedData) it.next();
                    sKey = PGPUtilities.findSecretKey(config.getSecretKeyRings(), pbe.getKeyID(), config.decryptionSecretKeyPassphraseForSecretKeyId(pbe.getKeyID()));
                }
                if (sKey == null) {
                    // Wrong passphrases already trigger a throw in PGPUtilities.findSecretKey.
                    throw new PGPException(
                            "Decryption failed - No secret key was found in the key ring matching the public key used "
                                    + "to encrypt the file, aborting");
                }

                // decrypt the data

                final InputStream plainText = pbe.getDataStream(new BcPublicKeyDataDecryptorFactory(sKey));
                final PGPObjectFactory nextFactory = new PGPObjectFactory(plainText, new BcKeyFingerprintCalculator());
                return nextDecryptedStream(nextFactory, state);
            } else if (pgpObj instanceof PGPCompressedData) {
                LOGGER.trace("Found instance of PGPCompressedData");
                final PGPObjectFactory nextFactory = new PGPObjectFactory(((PGPCompressedData) pgpObj).getDataStream(), config.getKeyFingerPrintCalculator());
                return nextDecryptedStream(nextFactory, state);
            } else if (pgpObj instanceof PGPOnePassSignatureList) {
                LOGGER.trace("Found instance of PGPOnePassSignatureList");

                if (!signatureValidationStrategy.isRequireSignatureCheck()) {
                    LOGGER.info("Signature check disabled - ignoring contained signature");
                } else {
                    state.factory = factory;

                    // verify the signature
                    final PGPOnePassSignatureList onePassSignatures = (PGPOnePassSignatureList) pgpObj;
                    for (PGPOnePassSignature signature : onePassSignatures) {
                        final PGPPublicKey pubKey = config.getPublicKeyRings().getPublicKey(signature.getKeyID());
                        if (pubKey != null) {
                            LOGGER.trace("Found signature and the public key '{}' was found in the keyring.", Long.toHexString(signature.getKeyID()));
                            signature.init(pgpContentVerifierBuilderProvider, pubKey);
                            state.addSignature(signature);
                        } else {
                            LOGGER.trace("Found signature but public key '{}' was not found in the keyring.", Long.toHexString(signature.getKeyID()));
                        }
                    }
                    if (state.numSignatures() == 0) {
                        throw new PGPException("Signature checking is required but none of the public keys used to sign the data was found in the keyring'!");
                    }
                }
            } else if (pgpObj instanceof PGPLiteralData) {
                LOGGER.trace("Found instance of PGPLiteralData");

                if (signatureValidationStrategy.isRequireSignatureCheck()) {
                    if (state.numSignatures() == 0) {
                        throw new PGPException("Signature checking is required but message was not signed!");
                    } else {
                        return new SignatureValidatingInputStream(((PGPLiteralData) pgpObj).getInputStream(), state, signatureValidationStrategy);
                    }
                } else {
                    return ((PGPLiteralData) pgpObj).getInputStream();
                }
            } else {// keep on searching...
                LOGGER.trace("Skipping pgp Object of Type {}", pgpObj.getClass().getSimpleName());
            }
        }
        throw new PGPException("No data found");
    }
}