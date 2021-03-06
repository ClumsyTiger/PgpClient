package etf.openpgp.iu170057d_sm170081d.encryption;

import org.apache.commons.io.IOUtils;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPMarker;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

public class Encryption
{
    static
    {
        if( Security.getProvider( "BC" ) == null )
        {
            Security.addProvider( new BouncyCastleProvider() );
        }
    }

    public static enum EncryptionAlgorithm
    {
        ELGAMAL_3DES( PGPEncryptedData.TRIPLE_DES ),
        ELGAMAL_IDEA( PGPEncryptedData.IDEA ),
        NONE( PGPEncryptedData.NULL );

        public final int id;

        private EncryptionAlgorithm( int id )
        {
            this.id = id;
        }
    }

    public static class PgpMessage
    {
        public byte[] encryptedMessage = null;
        public byte[] decryptedMessage = null;
        public long senderSecretKeyId = 0;
        public long receiverPublicKeyId = 0;
        public String encryptionAlgorithm = "";
        public boolean isEncrypted = false;
        public boolean isSigned = false;
        public boolean isCompressed = false;
        public boolean isRadix64Encoded = false;
        public boolean isIntegrityVerified = false;
        public boolean isSignatureVerified = false;
    }

    // create a literal data packet from the given message
    private static byte[] createLiteralPacket(
            byte[] message ) throws IOException
    {
        if( message == null )
            return null;

        ByteArrayOutputStream messageStream = null;
        OutputStream literalDataStream = null;

        try
        {
            // create a message stream for the resulting packet
            messageStream = new ByteArrayOutputStream();

            // create a literal data packet generator and stream with the above message stream
            PGPLiteralDataGenerator literalDataGen = new PGPLiteralDataGenerator();
            literalDataStream = literalDataGen.open(
                    messageStream,
                    PGPLiteralData.BINARY,
                    "filename", // FIXME: this should be specified in the function parameters
                    new Date(),
                    new byte[50000]
            );

            // write the data packet to the message body and close the literal packet stream
            literalDataStream.write( message );
            literalDataStream.close();

            // overwrite the message buffer and close the message stream
            message = messageStream.toByteArray();
            messageStream.close();

            // return the message
            return message;
        }
        catch( IOException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not create a literal data packet.", ex );
        }
        finally
        {
            try
            {
                // close all open resources
                if( messageStream != null )
                    messageStream.close();
                if( literalDataStream != null )
                    literalDataStream.close();
            }
            catch( IOException ex )
            {
                Logger.getLogger( Encryption.class.getName() ).log( Level.SEVERE, "Could not close file after IOException occured during write.", ex );
            }
        }

        throw new IOException( "Could not create a literal data packet." );
    }

    // surround the message with a one pass signature packet and a signature packet
    // ! the given message should not already be a literal data packet (this function wraps the message in a literal data packet)
    private static byte[] createSignaturePackets(
            byte[] message,
            PGPSecretKey senderSecretKey,
            char[] senderPassphrase ) throws IOException
    {
        if( message == null || senderSecretKey == null || senderPassphrase == null )
            return null;

        ByteArrayOutputStream messageStream = null;

        try
        {
            // get the sender's private key using the given passphrase
            PGPPrivateKey senderPrivateKey = senderSecretKey.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder()
                            .setProvider( "BC" )
                            .build( senderPassphrase )
            );
            // get the sender's public key
            PGPPublicKey senderPublicKey = senderSecretKey.getPublicKey();
            // get the sender's public key id
            String senderPublicKeyId = ( String )senderPublicKey.getUserIDs().next();

            // make a signature generator
            PGPSignatureGenerator signatureGen = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(
                            senderSecretKey.getPublicKey().getAlgorithm(),
                            HashAlgorithmTags.SHA256
                    ).setProvider( "BC" )
            );
            signatureGen.init( PGPSignature.BINARY_DOCUMENT, senderPrivateKey );

            // make a generator for the signature's header subpackets
            PGPSignatureSubpacketGenerator signatureSubpacketGen = new PGPSignatureSubpacketGenerator();
            signatureSubpacketGen.setSignerUserID( /*isCritical=*/ false, senderPublicKeyId );
            signatureSubpacketGen.setSignatureCreationTime( /*isCritical=*/ false, new Date() );
            signatureSubpacketGen.setPreferredHashAlgorithms( /*isCritical=*/ false, new int[]
                    {
                        HashAlgorithmTags.SHA256
                    } );
            signatureSubpacketGen.setPreferredSymmetricAlgorithms( /*isCritical=*/ false, new int[]
                    {
                        PGPEncryptedData.IDEA, PGPEncryptedData.TRIPLE_DES
                    } );
            signatureSubpacketGen.setPreferredCompressionAlgorithms( /*isCritical=*/ false, new int[]
                    {
                        PGPCompressedData.ZIP
                    } );

            // set the hashed subpackets in the signature
            signatureGen.setHashedSubpackets( signatureSubpacketGen.generate() );

            // create a one-pass signature header (parameter header in front of the message used for calculating the message signature in one pass)
            PGPOnePassSignature signatureHeader = signatureGen.generateOnePassVersion( /*isNested=*/ false );
            // create a literal packet from the message body
            byte[] literalPacket = createLiteralPacket( message );
            // update the message digest by hashing the message body
            signatureGen.update( message );
            // create a signature by signing the message digest with the sender's private key
            PGPSignature signature = signatureGen.generate();

            messageStream = new ByteArrayOutputStream();
            // prepend the signature one-pass header
            signatureHeader.encode( messageStream );
            // write the literal data packet
            messageStream.write( literalPacket );
            // append the signature packet
            signature.encode( messageStream );

            // overwrite the message buffer and close the message stream
            message = messageStream.toByteArray();
            messageStream.close();

            return message;
        }
        catch( IOException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not append a signature packet to the message.", ex );
        }
        catch( PGPException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not create message signature.", ex );
        }
        finally
        {
            try
            {
                // close all open resources
                if( messageStream != null )
                    messageStream.close();
            }
            catch( IOException ex )
            {
                Logger.getLogger( Encryption.class.getName() ).log( Level.SEVERE, "Could not close file after IOException occured during write.", ex );
            }
        }

        throw new IOException( "Could not append a signature packet to the message." );
    }

    // create a compressed packet from the given message
    private static byte[] createCompressedPacket(
            byte[] message ) throws IOException
    {
        if( message == null )
            return null;

        ByteArrayOutputStream messageStream = null;
        OutputStream compressedDataStream = null;

        try
        {
            // create a compressed data packet stream
            messageStream = new ByteArrayOutputStream();
            PGPCompressedDataGenerator compressedDataGen = new PGPCompressedDataGenerator( PGPCompressedData.ZIP );
            compressedDataStream = compressedDataGen.open( messageStream );

            // write the compressed data packet to the message stream and close the compressed data stream
            compressedDataStream.write( message );
            compressedDataStream.close();

            // overwrite the message buffer and close the message stream
            message = messageStream.toByteArray();
            messageStream.close();

            return message;
        }
        catch( IOException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not create a compressed data packet.", ex );
        }
        finally
        {
            try
            {
                // close all open resources
                if( messageStream != null )
                    messageStream.close();
                if( compressedDataStream != null )
                    compressedDataStream.close();
            }
            catch( IOException ex )
            {
                Logger.getLogger( Encryption.class.getName() ).log( Level.SEVERE, "Could not close file after IOException occured during write.", ex );
            }
        }

        throw new IOException( "Could not create a compressed data packet." );
    }

    // turn the message into an encrypted packet
    private static byte[] createEncryptedPacket(
            byte[] message,
            PGPPublicKey receiverPublicKey,
            EncryptionAlgorithm encryptionAlgorithm,
            char[] senderPassphrase ) throws IOException
    {
        if( message == null || receiverPublicKey == null || senderPassphrase == null )
            return null;

        ByteArrayOutputStream messageStream = null;
        OutputStream encryptedDataStream = null;

        try
        {
            // create an encryption generator
            PGPEncryptedDataGenerator encryptedDataGen = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder( encryptionAlgorithm.id )
                            .setProvider( "BC" )
                            .setSecureRandom( new SecureRandom() )
                            .setWithIntegrityPacket( true )
            );
            encryptedDataGen.addMethod(
                    new JcePublicKeyKeyEncryptionMethodGenerator( receiverPublicKey )
                            .setProvider( "BC" )
            );

            // make an encrypted output stream using the encryption generator
            messageStream = new ByteArrayOutputStream();
            encryptedDataStream = encryptedDataGen.open( messageStream, new byte[50000] );

            // write the encrypted data packet to the message stream and close the encrypted data stream
            encryptedDataStream.write( message );
            encryptedDataStream.close();

            // overwrite the message buffer and close the message stream
            message = messageStream.toByteArray();
            messageStream.close();

            return message;
        }
        catch( IOException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not create an encrypted data packet.", ex );
        }
        catch( PGPException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not encrypt message.", ex );
        }
        finally
        {
            try
            {
                // close all open resources
                if( messageStream != null )
                    messageStream.close();
                if( encryptedDataStream != null )
                    encryptedDataStream.close();
            }
            catch( IOException ex )
            {
                Logger.getLogger( Encryption.class.getName() ).log( Level.SEVERE, "Could not close file after IOException occured during write.", ex );
            }
        }

        throw new IOException( "Could not create an encrypted data packet." );
    }

    // encode the message into radix64 format
    private static byte[] encodeAsRadix64(
            byte[] message ) throws IOException
    {
        if( message == null )
            return null;

        ByteArrayOutputStream messageStream = null;
        ArmoredOutputStream armoredStream = null;

        try
        {
            // make an armored output stream using the message stream
            messageStream = new ByteArrayOutputStream();
            armoredStream = new ArmoredOutputStream( messageStream );

            // write the radix64 data packet to the message stream and close the armored data stream
            armoredStream.write( message );
            armoredStream.close();

            // overwrite the message buffer and close the message stream
            message = messageStream.toByteArray();
            messageStream.close();

            return message;
        }
        catch( IOException ex )
        {
            Logger.getLogger( Encryption.class.getName() ).log( Level.INFO, "Could not create an radix64 encoded data packet.", ex );
        }
        finally
        {
            try
            {
                // close all open resources
                if( messageStream != null )
                    messageStream.close();
                if( armoredStream != null )
                    armoredStream.close();
            }
            catch( IOException ex )
            {
                Logger.getLogger( Encryption.class.getName() ).log( Level.SEVERE, "Could not close file after IOException occured during write.", ex );
            }
        }

        throw new IOException( "Could not encode message in radix64 format." );
    }

    public static byte[] createPgpMessage(
            byte[] message,
            PGPSecretKey senderDsaSecretKey,
            PGPPublicKey receiverElGamalPublicKey,
            EncryptionAlgorithm encryptionAlgorithm,
            char[] senderPassphrase,
            boolean addSignature,
            boolean addCompression,
            boolean addConversionToRadix64 ) throws IOException
    {
        // create a literal data packet from the message body
        // ! only if the message is not going to be signed
        if( !addSignature )
            message = createLiteralPacket( message );

        // if the message should be signed, append a signature packet
        if( addSignature )
            message = createSignaturePackets( message, senderDsaSecretKey, senderPassphrase );

        // if the message should be compressed, turn it into a compressed packet
        if( addCompression )
            message = createCompressedPacket( message );

        // if the message should be encrypted, turn it into an encrypted packet
        if( encryptionAlgorithm != EncryptionAlgorithm.NONE )
            message = createEncryptedPacket( message, receiverElGamalPublicKey, encryptionAlgorithm, senderPassphrase );

        // if the message should be converted into radix64 format, encode it into that format
        if( addConversionToRadix64 )
            message = encodeAsRadix64( message );

        return message;
    }

    private static String symmetricAlgorithmIntToString( int code )
    {
        switch( code )
        {
            case 0:
                return "None";
            case 1:
                return "IDEA";
            case 2:
                return "3DES";
            case 3:
                return "CAST5";
            case 4:
                return "BLOWFISH";
            case 5:
                return "SAFER";
            case 6:
                return "DES";
            case 7:
                return "AES128";
            case 8:
                return "AES192";
            case 9:
                return "AES256";
            case 10:
                return "TWOFISH";
            case 11:
                return "CAMELLIA128";
            case 12:
                return "CAMELLIA192";
            case 13:
                return "CAMELLIA256";
            default:
                return "Unknown algorithm code.";
        }
    }

    private static class PgpDecryptionState
    {
        PGPEncryptedDataList encryptedDataList = null;
        Object pgpObject = null;
        Object currentMessage = null;
        PGPObjectFactory pgpObjectFactory = null;
        PGPPublicKeyEncryptedData publicKeyEncryptedData = null;
        PGPOnePassSignature onePassSignature = null;
        PGPPublicKey signerPublicKey = null;
    }

    private static void checkIfEncrypted(
            InputStream inputStream,
            PgpMessage pgpMessage,
            PgpDecryptionState pgpDecryptionState ) throws IOException
    {
        PGPObjectFactory pgpObjectFactory = new PGPObjectFactory( inputStream, new BcKeyFingerprintCalculator() );
        pgpDecryptionState.pgpObject = pgpObjectFactory.nextObject();

        // Determine if the message is encrypted
        pgpMessage.isEncrypted = false;
        if( pgpDecryptionState.pgpObject instanceof PGPEncryptedDataList )
        {
            pgpDecryptionState.encryptedDataList = ( PGPEncryptedDataList )pgpDecryptionState.pgpObject;
            pgpMessage.isEncrypted = true;
        }
        else if( pgpDecryptionState.pgpObject instanceof PGPMarker )
        {
            pgpDecryptionState.pgpObject = pgpObjectFactory.nextObject();
            if( pgpDecryptionState.pgpObject instanceof PGPEncryptedDataList )
            {
                pgpDecryptionState.encryptedDataList = ( PGPEncryptedDataList )pgpDecryptionState.pgpObject;
                pgpMessage.isEncrypted = true;
            }
        }
    }

    private static InputStream removeRadix64Encoding( InputStream inputStream ) throws IOException
    {
        return PGPUtil.getDecoderStream( new BufferedInputStream( inputStream ) );
    }

    private static void decrypt(
            PgpMessage pgpMessage,
            PgpDecryptionState pds,
            char[] passphrase ) throws PGPException, IOException
    {
        if( !pgpMessage.isEncrypted )
        {
            return;
        }

        PGPPrivateKey secretKey = null;

        Iterator<PGPEncryptedData> it = pds.encryptedDataList.getEncryptedDataObjects();

        PGPSecretKeyRingCollection pgpSecretKeyRingCollection = PGPKeys.getSecretKeysCollection();
        while( secretKey == null && it.hasNext() )
        {
            pds.publicKeyEncryptedData = ( PGPPublicKeyEncryptedData )it.next();
            PGPSecretKey pgpSecKey = pgpSecretKeyRingCollection.getSecretKey( pds.publicKeyEncryptedData.getKeyID() );

            if( pgpSecKey != null )
            {
                Provider provider = Security.getProvider( "BC" );
                secretKey = pgpSecKey.extractPrivateKey(
                        new JcePBESecretKeyDecryptorBuilder(
                                new JcaPGPDigestCalculatorProviderBuilder()
                                        .setProvider( provider )
                                        .build() )
                                .setProvider( provider )
                                .build( passphrase ) );
            }
        }

        // Secret key not found in private key ring collection - not possible to decrypt
        if( secretKey == null )
        {
            throw new IllegalArgumentException( "Secret key for message not found." );
        }
        // Secret key found and message is decrypted
        else
        {
            System.out.println( "Decryption successful!" );
        }

        int symmetricAlogirthTag = pds.publicKeyEncryptedData.getSymmetricAlgorithm(
                new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider( "BC" )
                        .build( secretKey ) );
        pgpMessage.encryptionAlgorithm = symmetricAlgorithmIntToString( symmetricAlogirthTag );

        InputStream clear = pds.publicKeyEncryptedData.getDataStream(
                new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider( "BC" )
                        .build( secretKey ) );
        pds.pgpObjectFactory = new PGPObjectFactory( clear, null );
        pds.currentMessage = pds.pgpObjectFactory.nextObject();
    }

    private static void decompress(
            PgpMessage pgpMessage,
            PgpDecryptionState pds ) throws IOException, PGPException
    {
        pgpMessage.isCompressed = false;
        if( pds.currentMessage instanceof PGPCompressedData )
        {
            pgpMessage.isCompressed = true;
            PGPCompressedData compressedData = ( PGPCompressedData )pds.currentMessage;
            pds.pgpObjectFactory = new PGPObjectFactory( new BufferedInputStream( compressedData.getDataStream() ), null );
            pds.currentMessage = pds.pgpObjectFactory.nextObject();
        }
    }

    private static void checkIfSigned( PgpMessage pgpMessage, PgpDecryptionState pds ) throws PGPException, IOException
    {
        // Determined if the message is signed
        pgpMessage.isSigned = false;
        if( pds.currentMessage instanceof PGPOnePassSignatureList )
        {
            PGPOnePassSignatureList p1 = ( PGPOnePassSignatureList )pds.currentMessage;
            pds.onePassSignature = p1.get( 0 );
            long keyId = pds.onePassSignature.getKeyID();
            pgpMessage.isSigned = true;

            // Get signer public key
            pds.signerPublicKey = PGPKeys.getPublicKeysCollection().getPublicKey( keyId );

            pds.onePassSignature.init( new JcaPGPContentVerifierBuilderProvider().setProvider( "BC" ), pds.signerPublicKey );

            pds.currentMessage = pds.pgpObjectFactory.nextObject();
        }
    }

    private static void unpackLiteral(
            PgpMessage pgpMessage,
            PgpDecryptionState pds ) throws PGPException, IOException
    {
        if( pds.currentMessage instanceof PGPLiteralData )
        {
            pgpMessage.decryptedMessage = IOUtils.toByteArray( (( PGPLiteralData )pds.currentMessage).getInputStream() );

            verifyIntegrity( pgpMessage, pds );

            // Read signature
            if( pgpMessage.isSigned )
            {
                readSignature( pgpMessage, pds );
            }
        }
    }

    private static void verifyIntegrity(
            PgpMessage pgpMessage,
            PgpDecryptionState pds ) throws PGPException, IOException
    {
        if( pds.publicKeyEncryptedData != null )
        {
            if( pds.publicKeyEncryptedData.isIntegrityProtected() && pds.publicKeyEncryptedData.verify() )
            {
                pgpMessage.isIntegrityVerified = true;
            }
        }
    }

    private static void readSignature(
            PgpMessage pgpMessage,
            PgpDecryptionState pds ) throws PGPException, IOException
    {
        pds.onePassSignature.update( pgpMessage.decryptedMessage );
        PGPSignatureList p3 = ( PGPSignatureList )pds.pgpObjectFactory.nextObject();
        if (p3 == null && pgpMessage.isSigned)
        {
            pgpMessage.isSignatureVerified = true;
            return;
        }

        if( pds.onePassSignature.verify( p3.get( 0 ) ) )
        {
            String str = new String( ( byte[] )pds.signerPublicKey.getRawUserIDs().next(), StandardCharsets.UTF_8 );
            pgpMessage.senderSecretKeyId = pds.signerPublicKey.getKeyID();
            pgpMessage.isSignatureVerified = true;
        }
        else
        {
            throw new PGPException( "Signature verification failed!" );
        }
    }

    private static void getPublicKeyId(
            PgpMessage pgpMessage,
            PgpDecryptionState pds ) throws IOException, PGPException
    {
        if( !pgpMessage.isEncrypted )
        {
            return;
        }

        PGPPrivateKey secretKey = null;

        Iterator<PGPEncryptedData> it = pds.encryptedDataList.getEncryptedDataObjects();

        PGPSecretKeyRingCollection pgpSecretKeyRingCollection = PGPKeys.getSecretKeysCollection();
        while( secretKey == null && it.hasNext() )
        {
            pds.publicKeyEncryptedData = ( PGPPublicKeyEncryptedData )it.next();
            PGPSecretKey pgpSecKey = pgpSecretKeyRingCollection.getSecretKey( pds.publicKeyEncryptedData.getKeyID() );

            if( pgpSecKey != null )
            {
                pgpMessage.receiverPublicKeyId = pds.publicKeyEncryptedData.getKeyID();
                return;
            }
        }
    }

    public static void readPgpMessage( PgpMessage pgpMessage ) throws Exception
    {
        InputStream inputStream = new ByteArrayInputStream( pgpMessage.encryptedMessage );
        inputStream = removeRadix64Encoding( inputStream );

        PgpDecryptionState pds = new PgpDecryptionState();
        checkIfEncrypted( inputStream, pgpMessage, pds );

        // If the message is not encrpyted, decoode it to extract all the data
        // without a passphrase
        if( !pgpMessage.isEncrypted )
        {
            pgpMessage.decryptedMessage = pgpMessage.encryptedMessage;
            decryptPgpMessage( null, pgpMessage );
        }
        // If the message is encrypted, get the `To` information so that user
        // know which passphrase to enter
        else
        {
            getPublicKeyId( pgpMessage, pds );
        }
    }

    public static void decryptPgpMessage(
            char[] passphrase,
            PgpMessage pgpMessage ) throws IOException, PGPException
    {
        PgpDecryptionState pds = new PgpDecryptionState();

        InputStream inputStream = new ByteArrayInputStream( pgpMessage.encryptedMessage );
        inputStream = removeRadix64Encoding( inputStream );

        // check if message is radix64 encoded
        pgpMessage.isRadix64Encoded = inputStream instanceof ArmoredInputStream;

        // check if the message is encrypted
        checkIfEncrypted( inputStream, pgpMessage, pds );

        if( pgpMessage.isEncrypted )  // Message is encrypted, try to decrypt it
        {
            decrypt( pgpMessage, pds, passphrase );
        }
        else  // Message is not encrypted
        {
            pds.currentMessage = pds.pgpObject;
        }

        // If compressed, decompress
        decompress( pgpMessage, pds );

        // check if the message is signed
        checkIfSigned( pgpMessage, pds );

        // Unpack literal, optionally verify message integrity
        // and read and check signature
        unpackLiteral( pgpMessage, pds );
    }
}
