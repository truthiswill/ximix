/**
 * Copyright 2013 Crypto Workshop Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cryptoworkshop.ximix.test.tests;

import java.math.BigInteger;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;
import org.bouncycastle.crypto.ec.ECElGamalEncryptor;
import org.bouncycastle.crypto.ec.ECPair;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.math.ec.ECPoint;
import org.cryptoworkshop.ximix.client.BoardCreationOptions;
import org.cryptoworkshop.ximix.client.CommandService;
import org.cryptoworkshop.ximix.client.DownloadOperationListener;
import org.cryptoworkshop.ximix.client.DownloadOptions;
import org.cryptoworkshop.ximix.client.KeyGenerationOptions;
import org.cryptoworkshop.ximix.client.KeyGenerationService;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrar;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrarFactory;
import org.cryptoworkshop.ximix.common.asn1.board.PairSequence;
import org.cryptoworkshop.ximix.common.asn1.board.PointSequence;
import org.cryptoworkshop.ximix.common.crypto.Algorithm;
import org.cryptoworkshop.ximix.common.util.Operation;
import org.cryptoworkshop.ximix.node.XimixNode;
import org.cryptoworkshop.ximix.test.node.NodeTestUtil;
import org.cryptoworkshop.ximix.test.node.ResourceAnchor;
import org.cryptoworkshop.ximix.test.node.SquelchingThrowableHandler;
import org.cryptoworkshop.ximix.test.node.TestNotifier;
import org.cryptoworkshop.ximix.test.node.ValueObject;
import org.junit.Test;

import static org.cryptoworkshop.ximix.test.node.NodeTestUtil.getXimixNode;

/**
 * Tests involving peers and incorrect numbers of peers.
 */
public class PeersTest
{
    @Test
    public void testInsufficientPeers_5_Thresh_3_Fail_1_Dec()
        throws Exception
    {
         doTestInsufficientPeers(5, 3, 1);
    }

    @Test
    public void testInsufficientPeers_5_Thresh_3_Fail_2_Dec()
        throws Exception
    {
        doTestInsufficientPeers(5, 3, 2);
    }

    private String[] getPeerList(int peerCount)
    {
        String[] peers = new String[peerCount];

        for (int i = 0; i != peerCount; i++)
        {
            peers[i] = new String(new char[] { (char)('A' + i) });
        }

        return peers;
    }

    /**
     * Test a network failure where 5 nodes are used for encryption but one fails before decryption.
     * Decryption should be successful.
     *
     * @throws Exception
     */
    private void doTestInsufficientPeers(int peerCount, int threshold, int fail)
        throws Exception
    {
        SquelchingThrowableHandler handler = new SquelchingThrowableHandler();

        handler.squelchType(SocketException.class);


        //
        // Set up nodes.
        //

        XimixNode nodeOne = getXimixNode("/conf/mixnet.xml", "/conf/node1.xml", handler);
        NodeTestUtil.launch(nodeOne);

        XimixNode nodeTwo = getXimixNode("/conf/mixnet.xml", "/conf/node2.xml", handler);
        NodeTestUtil.launch(nodeTwo);

        XimixNode nodeThree = getXimixNode("/conf/mixnet.xml", "/conf/node3.xml", handler);
        NodeTestUtil.launch(nodeThree);

        XimixNode nodeFour = getXimixNode("/conf/mixnet.xml", "/conf/node4.xml", handler);
        NodeTestUtil.launch(nodeFour);

        XimixNode nodeFive = getXimixNode("/conf/mixnet.xml", "/conf/node5.xml", handler);
        NodeTestUtil.launch(nodeFive);


        SecureRandom random = new SecureRandom();

        XimixRegistrar adminRegistrar = XimixRegistrarFactory.createAdminServiceRegistrar(ResourceAnchor.load("/conf/mixnet.xml"), new TestNotifier());

        KeyGenerationService keyGenerationService = adminRegistrar.connect(KeyGenerationService.class);

        KeyGenerationOptions keyGenOptions = new KeyGenerationOptions.Builder(Algorithm.EC_ELGAMAL, "secp256r1")
            .withThreshold(threshold)
            .withNodes(getPeerList(peerCount))
            .build();

        byte[] encPubKey = keyGenerationService.generatePublicKey("ECKEY", keyGenOptions);

        keyGenerationService.shutdown();

        CommandService commandService = adminRegistrar.connect(CommandService.class);

        commandService.createBoard("FRED", new BoardCreationOptions.Builder("B").build());

        final ECPublicKeyParameters pubKey = (ECPublicKeyParameters)PublicKeyFactory.createKey(encPubKey);

        final ECElGamalEncryptor encryptor = new ECElGamalEncryptor();

        encryptor.init(pubKey);


        //
        // Set up plain text and upload encrypted pair.
        //

        int numberOfPoints = 1; // Adjust number of points to test here.


        final ECPoint[] plainText1 = new ECPoint[numberOfPoints];
        final ECPoint[] plainText2 = new ECPoint[numberOfPoints];


        //
        // Encrypt and submit.
        //
        for (int i = 0; i < plainText1.length; i++)
        {
            plainText1[i] = generatePoint(pubKey.getParameters(), random);
            plainText2[i] = generatePoint(pubKey.getParameters(), random);

            PairSequence encrypted = new PairSequence(new ECPair[]{encryptor.encrypt(plainText1[i]), encryptor.encrypt(plainText2[i])});

            commandService.uploadMessage("FRED", encrypted.getEncoded());
        }

        // we're going to shutdown some nodes - make sure we disconnect and
        // reconnect in case we're talking to the one we shutdown
        commandService.shutdown();

        //
        // Here we shut down on nodes, the remainder should still pass.
        //
        if (fail == 1)
        {
            TestCase.assertTrue("Node 5, failed to shutdown.",nodeFive.shutdown(10, TimeUnit.SECONDS));
        }
        else if (fail == 2)
        {
            TestCase.assertTrue("Node 5, failed to shutdown.",nodeFive.shutdown(30, TimeUnit.SECONDS));
            nodeFour.shutdown(30, TimeUnit.SECONDS);
        }
        else
        {
            TestCase.fail("unknown fail count");
        }

        final ECPoint[] resultText1 = new ECPoint[plainText1.length];
        final ECPoint[] resultText2 = new ECPoint[plainText2.length];
        final ValueObject<Boolean> downloadBoardCompleted = new ValueObject<Boolean>(false);
        final ValueObject<Boolean> downloadBoardFailed = new ValueObject<Boolean>(false);
        final CountDownLatch encryptLatch = new CountDownLatch(1);
        final AtomicReference<Thread> decryptThread = new AtomicReference<>();

        commandService = adminRegistrar.connect(CommandService.class);

        Operation<DownloadOperationListener> op = commandService.downloadBoardContents(
            "FRED",
            new DownloadOptions.Builder()
                .withKeyID("ECKEY")
                .withThreshold(threshold)
                .withNodes(getPeerList(peerCount)).build(),
            new DownloadOperationListener()
            {
                int counter = 0;

                @Override
                public void messageDownloaded(int index, byte[] message, List<byte[]> proofs)
                {
                    PointSequence decrypted = PointSequence.getInstance(pubKey.getParameters().getCurve(), message);
                    resultText1[counter] = decrypted.getECPoints()[0];
                    resultText2[counter++] = decrypted.getECPoints()[1];

                    TestUtil.checkThread(decryptThread);
                }

                @Override
                public void completed()
                {
                    downloadBoardCompleted.set(true);
                    encryptLatch.countDown();

                    TestUtil.checkThread(decryptThread);
                }

                @Override
                public void status(String statusObject)
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }

                @Override
                public void failed(String errorObject)
                {
                    downloadBoardFailed.set(true);
                    encryptLatch.countDown();
                    TestUtil.checkThread(decryptThread);
                }

            });


        TestCase.assertTrue(encryptLatch.await(20, TimeUnit.SECONDS));

        TestCase.assertNotSame("Failed and complete must be different.", downloadBoardFailed.get(), downloadBoardCompleted.get());
        TestCase.assertTrue("Complete method called in DownloadOperationListener", downloadBoardCompleted.get());
        TestCase.assertFalse("Not failed.", downloadBoardFailed.get());


        encryptLatch.await();

        //
        // Validate result points against plainText points.
        //

        for (int t = 0; t < plainText1.length; t++)
        {
            TestCase.assertTrue(plainText1[t].equals(resultText1[t]));
            TestCase.assertTrue(plainText2[t].equals(resultText2[t]));
        }


        NodeTestUtil.shutdownNodes();
        commandService.shutdown();
    }


    private static BigInteger getRandomInteger(BigInteger n, SecureRandom rand)
    {
        BigInteger r;
        int maxbits = n.bitLength();
        do
        {
            r = new BigInteger(maxbits, rand);
        }
        while (r.compareTo(n) >= 0);
        return r;
    }

    private static ECPoint generatePoint(ECDomainParameters params, SecureRandom rand)
    {
        return params.getG().multiply(getRandomInteger(params.getN(), rand));
    }
}
