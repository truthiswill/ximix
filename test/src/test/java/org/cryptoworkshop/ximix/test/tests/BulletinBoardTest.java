package org.cryptoworkshop.ximix.test.tests;

import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import org.bouncycastle.util.Arrays;
import org.cryptoworkshop.ximix.client.BoardCreationOptions;
import org.cryptoworkshop.ximix.client.CommandService;
import org.cryptoworkshop.ximix.client.DownloadOperationListener;
import org.cryptoworkshop.ximix.client.DownloadOptions;
import org.cryptoworkshop.ximix.client.UploadService;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrar;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrarFactory;
import org.cryptoworkshop.ximix.common.util.Operation;
import org.cryptoworkshop.ximix.node.XimixNode;
import org.cryptoworkshop.ximix.test.node.NodeTestUtil;
import org.cryptoworkshop.ximix.test.node.ResourceAnchor;
import org.cryptoworkshop.ximix.test.node.SquelchingThrowableHandler;
import org.cryptoworkshop.ximix.test.node.TestNotifier;
import org.junit.Test;

import static org.cryptoworkshop.ximix.test.node.NodeTestUtil.getXimixNode;

/**
 *
 */
public class BulletinBoardTest
{


   // @Test
    public void testWhereOneBoardDisappears()
        throws Exception
    {
        SquelchingThrowableHandler handler = new SquelchingThrowableHandler();

        final int testSize = 500;
        int msgMaxSize = 4096;
        int msgMinSize = 1024;

        SecureRandom rand = new SecureRandom();


        ArrayList<byte[]> sourceMessages = new ArrayList<byte[]>();


        for (int t = 0; t < testSize; t++)
        {
            byte[] msg = new byte[msgMinSize + rand.nextInt(msgMaxSize - msgMinSize)];
            rand.nextBytes(msg);
            sourceMessages.add(msg);
        }


        handler.setPrintOnly(true);
        //handler.squelchType(SocketException.class);


        //
        // Set up nodes.
        //

        final XimixNode nodeOne = getXimixNode("/conf/mixnet.xml", "/conf/node1.xml", handler);
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

        CommandService commandService = adminRegistrar.connect(CommandService.class);

        commandService.createBoard("TED", new BoardCreationOptions.Builder("A").build());

        UploadService client = adminRegistrar.connect(UploadService.class);

        for (int t = 0; t < testSize; t++)
        {
            client.uploadMessage("TED", sourceMessages.get(t));
        }


        final ArrayList<byte[]> msgDownloaded = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);


        Operation<DownloadOperationListener> op = commandService.downloadBoardContents(
            "TED",
            new DownloadOptions.Builder()
                .withKeyID(null)
                .withThreshold(4)
                .withNodes("A", "B", "C", "D", "E").build(),
            new DownloadOperationListener()
            {
                @Override
                public void messageDownloaded(int index, byte[] message, List<byte[]> proofs)
                {
                    msgDownloaded.add(message);


                    //
                    // Kill of node halfway through..
                    //
                    if (msgDownloaded.size() > testSize / 2)
                    {
                        try
                        {
                            nodeOne.shutdown(10, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    if (msgDownloaded.size() == testSize)
                    {
                        latch.countDown();
                    }
                }

                @Override
                public void completed()
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }

                @Override
                public void status(String statusObject)
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }

                @Override
                public void failed(String errorObject)
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }
            });

        NodeTestUtil.shutdownNodes();
        client.shutdown();
        commandService.shutdown();


        TestCase.assertTrue("Download did not complete in time.", latch.await(500, TimeUnit.SECONDS));

        TestCase.assertEquals("Source message count != downloaded message count.", sourceMessages.size(), msgDownloaded.size());

        for (int t = 0; t < sourceMessages.size() && t < msgDownloaded.size(); t++)
        {
            TestCase.assertTrue("Source message did not equal downloaded.", Arrays.areEqual(sourceMessages.get(t), msgDownloaded.get(t)));
        }




    }

    @Test
    public void testSimpleUploadDownload()
        throws Exception
    {
        SquelchingThrowableHandler handler = new SquelchingThrowableHandler();
        handler.squelchType(SocketException.class);

        final int testSize = 500;
        int msgMaxSize = 4096;
        int msgMinSize = 1024;

        SecureRandom rand = new SecureRandom();


        ArrayList<byte[]> sourceMessages = new ArrayList<byte[]>();


        for (int t = 0; t < testSize; t++)
        {
            byte[] msg = new byte[msgMinSize + rand.nextInt(msgMaxSize - msgMinSize)];
            rand.nextBytes(msg);
            sourceMessages.add(msg);
        }


        handler.setPrintOnly(true);
        //handler.squelchType(SocketException.class);


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

        CommandService commandService = adminRegistrar.connect(CommandService.class);

        commandService.createBoard("TED", new BoardCreationOptions.Builder("A").build());

        UploadService client = adminRegistrar.connect(UploadService.class);

        for (int t = 0; t < testSize; t++)
        {
            client.uploadMessage("TED", sourceMessages.get(t));
        }

        final ArrayList<byte[]> msgDownloaded = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);


        Operation<DownloadOperationListener> op = commandService.downloadBoardContents(
            "TED",
            new DownloadOptions.Builder()
                .withKeyID(null)
                .withThreshold(4)
                .withNodes("A", "B", "C", "D", "E").build(),
            new DownloadOperationListener()
            {
                @Override
                public void messageDownloaded(int index, byte[] message, List<byte[]> proofs)
                {
                    msgDownloaded.add(message);
                    if (msgDownloaded.size() == testSize)
                    {
                        latch.countDown();
                    }
                }

                @Override
                public void completed()
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }

                @Override
                public void status(String statusObject)
                {
                    //To change body of implemented methods use File | Settings | File Templates.
                }

                @Override
                public void failed(String errorObject)
                {
                    System.err.print(errorObject);
                }
            });

        TestCase.assertTrue("Download did not complete in time.", latch.await(500, TimeUnit.SECONDS));

        TestCase.assertEquals("Source message count != downloaded message count.", sourceMessages.size(), msgDownloaded.size());

        for (int t = 0; t < sourceMessages.size() && t < msgDownloaded.size(); t++)
        {
            TestCase.assertTrue("Source message did not equal downloaded.", Arrays.areEqual(sourceMessages.get(t), msgDownloaded.get(t)));
        }


        NodeTestUtil.shutdownNodes();
        client.shutdown();
        commandService.shutdown();

    }

}
