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
package org.cryptoworkshop.ximix.console.applet;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JApplet;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedDataParser;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PEMWriter;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.cryptoworkshop.ximix.client.BoardCreationOptions;
import org.cryptoworkshop.ximix.client.BoardDetail;
import org.cryptoworkshop.ximix.client.CommandService;
import org.cryptoworkshop.ximix.client.DownloadOperationListener;
import org.cryptoworkshop.ximix.client.DownloadOptions;
import org.cryptoworkshop.ximix.client.DownloadShuffleResultOptions;
import org.cryptoworkshop.ximix.client.KeyService;
import org.cryptoworkshop.ximix.client.MonitorService;
import org.cryptoworkshop.ximix.client.NetworkBoardListener;
import org.cryptoworkshop.ximix.client.RegistrarServiceException;
import org.cryptoworkshop.ximix.client.ShuffleOperationListener;
import org.cryptoworkshop.ximix.client.ShuffleOptions;
import org.cryptoworkshop.ximix.client.ShuffleStatus;
import org.cryptoworkshop.ximix.client.ShuffleTranscriptOptions;
import org.cryptoworkshop.ximix.client.ShuffleTranscriptsDownloadOperationListener;
import org.cryptoworkshop.ximix.client.UploadService;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrar;
import org.cryptoworkshop.ximix.client.connection.XimixRegistrarFactory;
import org.cryptoworkshop.ximix.client.verify.ECShuffledTranscriptVerifier;
import org.cryptoworkshop.ximix.client.verify.LinkIndexVerifier;
import org.cryptoworkshop.ximix.client.verify.SignedDataVerifier;
import org.cryptoworkshop.ximix.common.asn1.board.PointSequence;
import org.cryptoworkshop.ximix.common.asn1.message.SeedAndWitnessMessage;
import org.cryptoworkshop.ximix.common.util.EventNotifier;
import org.cryptoworkshop.ximix.common.util.Operation;
import org.cryptoworkshop.ximix.common.util.TranscriptType;
import org.cryptoworkshop.ximix.console.util.vote.BallotUnpacker;

public class CommandApplet
    extends JApplet
{
    private static final int BATCH_SIZE = 10;
    private ExecutorService  threadPool = Executors.newCachedThreadPool();   // TODO: maybe configure?
    private static final int ncores = 3;

    private X509Certificate trustAnchor;
    private Semaphore processSemaphore = new Semaphore(ncores);
    private Semaphore maxProcessSemaphore = new Semaphore(ncores * 5);

    public void init()
    {
        if (Security.getProvider("BC") == null)
        {
            Security.addProvider(new BouncyCastleProvider());
        }

        final URL mixnetConf = getConfURL();
        final URL trustCa = getCaURL();

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));

        JPanel uploadPanel = new JPanel();

        uploadPanel.setBorder(BorderFactory.createTitledBorder("Upload Source Directory"));

        JButton uploadBrowseButton = new JButton("...");

        final JTextField uploadDirField = new JTextField(20);
        final XimixRegistrar adminRegistrar;

        try
        {
            PEMParser pemParser = new PEMParser(new InputStreamReader(trustCa.openStream()));

            trustAnchor = new JcaX509CertificateConverter().setProvider("BC").getCertificate((X509CertificateHolder)pemParser.readObject());

            adminRegistrar = XimixRegistrarFactory.createAdminServiceRegistrar(mixnetConf.openStream(), new EventNotifier()
            {
                @Override
                public void notify(Level level, Throwable throwable)
                {
                    System.err.print(level + " " + throwable.getMessage());
                    throwable.printStackTrace(System.err);
                }

                @Override
                public void notify(Level level, Object detail)
                {
                    System.err.println(level + " " + detail.toString());
                }

                @Override
                public void notify(Level level, Object detail, Throwable throwable)
                {
                    System.err.println(level + " " + detail.toString());
                    throwable.printStackTrace(System.err);
                }
            });
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Can't parse trust anchor.", e);
        }

        uploadBrowseButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                JFileChooser chooser = new JFileChooser();

                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

                int result = chooser.showDialog(CommandApplet.this, "Select");

                if (result == JFileChooser.APPROVE_OPTION)
                {
                    uploadDirField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });

        uploadPanel.add(uploadDirField);

        uploadPanel.add(uploadBrowseButton);

        JPanel downloadPanel = new JPanel();

        downloadPanel.setBorder(BorderFactory.createTitledBorder("Download Directory"));

        JButton downloadBrowseButton = new JButton("...");

        final JTextField downloadDirField = new JTextField(20);

        downloadBrowseButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                JFileChooser chooser = new JFileChooser();

                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

                int result = chooser.showDialog(CommandApplet.this, "Select");

                if (result == JFileChooser.APPROVE_OPTION)
                {
                    downloadDirField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });

        downloadPanel.add(downloadDirField);
        downloadPanel.add(downloadBrowseButton);

        JPanel  tablePanel = new JPanel();
        tablePanel.setLayout(new BoxLayout(tablePanel, BoxLayout.Y_AXIS));

        JPanel topTablePanel = new JPanel();
        topTablePanel.setLayout(new BoxLayout(topTablePanel, BoxLayout.X_AXIS));

        final JTextField shufflePlan = new JTextField(30);

        final EventNotifier eventNotifier = new EventNotifier()
        {
            @Override
            public void notify(Level level, Throwable throwable)
            {
                System.err.print(level + " " + throwable.getMessage());
                throwable.printStackTrace(System.err);
            }

            @Override
            public void notify(Level level, Object detail)
            {
                System.err.println(level + " " + detail.toString());
            }

            @Override
            public void notify(Level level, Object detail, Throwable throwable)
            {
                System.err.println(level + " " + detail.toString());
                throwable.printStackTrace(System.err);
            }
        };

        final JTable  boardTable = new JTable(new BoardTableModel());

        JButton candidateMapBrowseButton = new JButton("...");

        final JTextField configField = new JTextField(20);

        candidateMapBrowseButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                JFileChooser chooser = new JFileChooser();

                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

                int result = chooser.showDialog(CommandApplet.this, "Select");

                if (result == JFileChooser.APPROVE_OPTION)
                {
                    configField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });

        JButton uploadButton = new JButton("Do Upload");

        final URL finalMixnetConf = mixnetConf;
        uploadButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                String dirName = uploadDirField.getText().trim();

                if (dirName.length() > 0)
                {
                    Thread taskThread = new Thread(new FullUploadTask((BoardTableModel)boardTable.getModel(), dirName, finalMixnetConf, eventNotifier));

                    taskThread.setPriority(Thread.NORM_PRIORITY);

                    taskThread.start();
                }
                else
                {
                    JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                        "Please enter an upload source directory.",
                        "Missing Field Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

            }
        });

        topTablePanel.add(uploadButton);

        JPanel shufflePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        shufflePanel.add(new JLabel("Shuffle Plan:"));
        shufflePanel.add(shufflePlan);

        topTablePanel.add(shufflePanel);

        final JTextField keyID = new JTextField(15);
        JTextField threshold = new JTextField(3);

        keyID.setText("ECENCKEY");
        threshold.setText("4");

        JButton shuffleButton = new JButton("Shuffle and Download Selected");

        shuffleButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                String planStr = shufflePlan.getText().trim();
                String dirName = downloadDirField.getText().trim();
                String configName = configField.getText().trim();

                if (dirName.length() == 0)
                {
                    JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                        "Please enter a download directory.",
                        "Missing Field Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (configName.length() == 0)
                {
                    JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                        "Please enter a candidate configuration file name.",
                        "Missing Field Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (planStr.length() > 0)
                {
                    String[] plan = planStr.split(",");

                    for (int i = 0; i != plan.length; i++)
                    {
                        plan[i] = plan[i].trim();
                        if (plan[i].length() == 0)
                        {
                            JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                                "Empty node name found.",
                                "Syntax Error",
                                JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }

                    Thread taskThread = new Thread(new FullShuffleTask(new File(dirName), keyID.getText().trim(), (BoardTableModel)boardTable.getModel(), plan, finalMixnetConf, configField.getText().trim(), eventNotifier));

                    taskThread.setPriority(Thread.NORM_PRIORITY);

                    taskThread.start();
                }
                else
                {
                    JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                        "Please enter a shuffle plan.",
                        "Missing Field Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JPanel downloadControlPanel = new JPanel();
        downloadControlPanel.setLayout(new BoxLayout(downloadControlPanel, BoxLayout.Y_AXIS));

        JPanel downloadKeyPanel = new JPanel();
        downloadKeyPanel.setLayout(new BoxLayout(downloadKeyPanel, BoxLayout.X_AXIS));

        JButton exportButton = new JButton("Export Key");

        exportButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent event)
            {
                JFileChooser chooser = new JFileChooser();

                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

                int result = chooser.showDialog(CommandApplet.this, "Save");

                if (result == JFileChooser.APPROVE_OPTION)
                {
                    try
                    {
                        KeyService keyService = adminRegistrar.connect(KeyService.class);

                        byte[] encPubKey = keyService.fetchPublicKey(keyID.getText().trim());

                        PEMWriter pWrt = new PEMWriter(new FileWriter(chooser.getSelectedFile().getAbsolutePath()));

                        pWrt.writeObject(new MiscPEMGenerator(SubjectPublicKeyInfo.getInstance(encPubKey)));

                        pWrt.close();

                        keyService.shutdown();
                    }
                    catch (Exception e)
                    {
                        // TODO:
                        e.printStackTrace();
                    }

                }
            }
        });

        JPanel keyIDPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        keyIDPanel.add(new JLabel("Key ID: "));
        keyIDPanel.add(keyID);

        JPanel thresholdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        thresholdPanel.add(new JLabel("Threshold"));
        thresholdPanel.add(threshold);

        downloadKeyPanel.add(keyIDPanel);
        downloadKeyPanel.add(thresholdPanel);
        downloadKeyPanel.add(exportButton);

        JPanel candidateMapPanel = new JPanel();
        candidateMapPanel.add(new JLabel("Candidate Config: "));

        candidateMapPanel.add(configField);
        candidateMapPanel.add(candidateMapBrowseButton);

        JPanel downloadButtonPanel = new JPanel();
        downloadButtonPanel.setLayout(new BoxLayout(downloadButtonPanel, BoxLayout.X_AXIS));

        final JButton selectAllButton = new JButton("Select All");

        selectAllButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                BoardTableModel tableModel = (BoardTableModel)boardTable.getModel();

                if (selectAllButton.getText().startsWith("Sele"))
                {
                    selectAllButton.setText("Deselect All");
                    for (BoardEntry entry : tableModel.getEntries())
                    {
                        entry.setSelected(true);
                    }
                }
                else
                {
                    selectAllButton.setText("Select All");
                    for (BoardEntry entry : tableModel.getEntries())
                    {
                         entry.setSelected(false);
                    }
                }
            }
        });
        downloadButtonPanel.add(selectAllButton);
        downloadButtonPanel.add(shuffleButton);

        downloadControlPanel.add(downloadKeyPanel);
        downloadControlPanel.add(candidateMapPanel);
        downloadControlPanel.add(downloadButtonPanel);

        topTablePanel.add(downloadControlPanel);
        topTablePanel.add(Box.createHorizontalGlue());

        boardTable.getTableHeader().setPreferredSize(new Dimension(boardTable.getColumnModel().getTotalColumnWidth(), boardTable.getRowHeight(0) * 2));


        tablePanel.add(topTablePanel);
        tablePanel.add(new JScrollPane(boardTable));

        JPanel basePanel = new JPanel();

        basePanel.setLayout(new BoxLayout(basePanel, BoxLayout.Y_AXIS));

        topPanel.add(uploadPanel);
        topPanel.add(Box.createHorizontalGlue());
        topPanel.add(downloadPanel);

        basePanel.add(topPanel);
        basePanel.add(tablePanel);

        try
        {
            MonitorService monitor = adminRegistrar.connect(MonitorService.class);

            monitor.addBulletinBoardListener(new NetworkBoardListener()
            {
                @Override
                public void boardChanged(String boardName, BoardDetail boardDetail)
                {
                    BoardTableModel tableModel = (BoardTableModel)boardTable.getModel();

                    BoardEntry entry = tableModel.getEntry(boardName, boardDetail.getHost(), boardDetail.getBackupHost());

                    entry.setMessageCount(boardDetail.getMessageCount());
                }
            });
        }
        catch (RegistrarServiceException e)
        {
            // TODO:
            e.printStackTrace();
        }

        this.getContentPane().add(basePanel);
    }

    private URL getConfURL()
    {
        URL mixnetConf;
        try
        {
            mixnetConf = new URL(getParameter("mixnetConf"));
        }
        catch (MalformedURLException e)
        {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            mixnetConf = null; // TODO:
        }
        return mixnetConf;
    }

    private URL getCaURL()
    {
        URL mixnetConf;
        try
        {
            mixnetConf = new URL(getParameter("trustAnchor"));
        }
        catch (MalformedURLException e)
        {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            mixnetConf = null; // TODO:
        }
        return mixnetConf;
    }

    public void start()
    {

    }

    public void stop()
    {

    }

    private static class BoardTableModel
        extends AbstractTableModel
    {
        static String[] title = new String[] { "Select", "Board", "<html><body><center>Primary<br/>Node</center></body></html>", "<html><body><center>Backup<br/>Node</center></body></html>", "Size", "Status" };

        private final Map<String, BoardEntry> entries = Collections.<String, BoardEntry>synchronizedMap(new LinkedHashMap());

        BoardTableModel()
        {
        }

        @Override
        public int getRowCount()
        {
            return entries.size();
        }

        @Override
        public String getColumnName(int x)
        {
            return title[x];
        }

        @Override
        public int getColumnCount()
        {
            return title.length;
        }

        @Override
        public boolean isCellEditable(int row, int column)
        {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column)
        {
            if (row >= entries.size())
            {
                return null;
            }

            return new ArrayList<>(entries.values()).get(row).getValue(column);
        }

        public Class getColumnClass(int column)
        {
            if (column == 0)
            {
                return Boolean.class;
            }

            return String.class;
        }

        @Override
        public void setValueAt(Object o, int row, int column)
        {
            if (column == 0)
            {
                new ArrayList<>(entries.values()).get(row).isSelected = ((Boolean)o).booleanValue();
            }
        }

        public synchronized List<BoardEntry> getEntries()
        {
            return new ArrayList<>(entries.values());
        }

        public synchronized BoardEntry getEntry(String fileName, String primary, String secondary)
        {
            BoardEntry boardEntry = entries.get(fileName);

            if (boardEntry == null)
            {
                boardEntry = new BoardEntry(this, fileName, primary, secondary);
                entries.put(fileName, boardEntry);
                fireTableRowsInserted(entries.size() - 1, entries.size() - 1);
            }

            return boardEntry;
        }

        public synchronized List<BoardEntry> getSelectedEntries()
        {
            List<BoardEntry> selected = new ArrayList<>();

            for (BoardEntry entry : entries.values())
            {
                if (entry.isSelected)
                {
                    selected.add(entry);
                }
            }

            return selected;
        }
    }

    private static class BoardEntry
    {
        enum State
        {
            LOADING,
            SHUFFLING
        }

        private final DecimalFormat fmt = new DecimalFormat("##0.00");

        private final BoardTableModel parent;
        private final String name;
        private final String primary;
        private final String secondary;

        private volatile boolean isSelected = false;
        private volatile State state = State.LOADING;
        private volatile int totalMesages = 0;
        private volatile double pCentProgress = 0.0;
        private volatile String progressMessage;

        public BoardEntry(BoardTableModel parent, String name, String primary, String secondary)
        {
            this.parent = parent;
            this.name = name;
            this.primary = primary;
            this.secondary = secondary;
            this.progressMessage = "Loaded";
        }

        public synchronized Object getValue(int column)
        {
            switch (column)
            {
            case 0:
                return isSelected;
            case 1:
                return name;
            case 2:
                return primary;
            case 3:
                return secondary;
            case 4:
                return totalMesages;
            case 5:
                if (progressMessage != null)
                {
                    return progressMessage;
                }
                if (pCentProgress != 1.0)
                {
                    if (state == State.LOADING)
                    {
                        return "Loading (" + fmt.format(pCentProgress * 100) + " %)";
                    }
                    else
                    {
                        return "Processing (" + fmt.format(pCentProgress * 100) + " %)";
                    }
                }
                else
                {
                    return "Complete";
                }
            default:
                return "Not Set";
            }
        }

        public synchronized void markProgress(State state, int numMessages, double pCentDone)
        {
            this.progressMessage = null;
            this.state = state;
            this.totalMesages += numMessages;
            this.pCentProgress = pCentDone;
            this.parent.fireTableDataChanged();
        }

        public synchronized void setShuffleProgress(String progress)
        {
            this.progressMessage = progress;
            this.parent.fireTableDataChanged();
        }

        public synchronized void setMessageCount(int messageCount)
        {
            this.totalMesages = messageCount;
            this.parent.fireTableDataChanged();
        }

        public synchronized void setSelected(boolean selected)
        {
            this.isSelected = selected;
            this.parent.fireTableDataChanged();
        }

        public String getName()
        {
            return name;
        }
    }

    private class ProgressDialog
        extends JDialog
    {
        private final JProgressBar progressBar;
        private final int          mult;

        private final JButton dismiss;

        ProgressDialog(String title, int size)
        {
            super(SwingUtilities.windowForComponent(CommandApplet.this), ModalityType.APPLICATION_MODAL);

            this.setTitle(title);

            JPanel basePanel = new JPanel();
            basePanel.setLayout(new BoxLayout(basePanel, BoxLayout.Y_AXIS));

            basePanel.add(new JLabel(title));

            if (size > 200)
            {
                mult = 1;
            }
            else
            {
                mult = 2;
            }

            progressBar = new JProgressBar(0, size * mult);
            basePanel.add(progressBar);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

            dismiss = new JButton("Dismiss");

            dismiss.setEnabled(false);
            dismiss.addActionListener(new ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    ProgressDialog.this.setVisible(false);
                    ProgressDialog.this.dispose();
                }
            });

            buttonPanel.add(dismiss);

            basePanel.add(buttonPanel);

            this.getContentPane().add(basePanel);
            this.pack();
            this.setLocationRelativeTo(CommandApplet.this);
        }

        void adjustProgress(final int incr)
        {
            SwingUtilities.invokeLater(new Runnable()
            {
                @Override
                public void run()
                {
                    progressBar.setValue(progressBar.getValue() + incr * mult);

                    if (progressBar.getValue() == progressBar.getMaximum())
                    {
                        dismiss.setEnabled(true);
                    }
                }
            });
        }
    }

    private class FullUploadTask
        implements Runnable
    {
        private final BoardTableModel boardModel;
        private final String dirName;
        private final EventNotifier eventNotifier;
        private final URL mixnetConf;

        public FullUploadTask(BoardTableModel boardModel, String dirName, URL mixnetConf, EventNotifier eventNotifier)
        {
            this.boardModel = boardModel;
            this.dirName = dirName;
            this.mixnetConf = mixnetConf;
            this.eventNotifier = eventNotifier;
        }

        @Override
        public void run()
        {
            File f = new File(dirName);

            final File[] files = f.listFiles(new FilenameFilter()
            {
                @Override
                public boolean accept(File file, String s)
                {
                    return true;
                }
            });

            try
            {
                XimixRegistrar adminRegistrar = XimixRegistrarFactory.createAdminServiceRegistrar(mixnetConf.openStream(), eventNotifier);

                CommandService commandService = adminRegistrar.connect(CommandService.class);
                UploadService  uploadService = adminRegistrar.connect(UploadService.class);

                String[]       nodes = commandService.getNodeNames().toArray(new String[0]);
                CountDownLatch uploadLatch = new CountDownLatch(files.length);

                processSemaphore = new Semaphore(nodes.length);

                final ProgressDialog dialog = getProgressDialog("Upload Progress", files.length);

                for (int i = 0; i != files.length; i++)
                {
                    String primary = nodes[i % nodes.length];
                    String secondary = nodes[(i + 1) % nodes.length];

                    threadPool.submit(new UploadTask(commandService, uploadService, uploadLatch, primary, secondary, files[i], dialog, eventNotifier));
                }

                uploadLatch.await();

                processSemaphore = new Semaphore(ncores);     // TODO: use field in UI.

                commandService.shutdown();
            }
            catch (Exception e)
            {
                eventNotifier.notify(EventNotifier.Level.ERROR, "Cannot perform upload: " + e.getMessage(), e);
            }
        }

        private class UploadTask
            implements Runnable
        {

            private final CommandService commandService;
            private final UploadService uploadService;
            private final CountDownLatch uploadLatch;
            private final String primary;
            private final String secondary;
            private final File file;
            private final ProgressDialog dialog;
            private final EventNotifier eventNotifier;

            public UploadTask(CommandService commandService, UploadService uploadService, CountDownLatch uploadLatch, String primary, String secondary, File file, ProgressDialog dialog, EventNotifier eventNotifier)
            {
                this.commandService = commandService;
                this.uploadService = uploadService;
                this.uploadLatch = uploadLatch;
                this.primary = primary;
                this.secondary = secondary;
                this.file = file;
                this.dialog = dialog;
                this.eventNotifier = eventNotifier;
            }

            @Override
            public void run()
            {
                try
                {
                    processSemaphore.acquire();

                    String boardName = file.getName().substring(0, file.getName().indexOf('.'));

                    BoardEntry entry = boardModel.getEntry(boardName, primary, secondary);

                    if (!commandService.isBoardExisting(boardName))
                    {
                        commandService.createBoard(boardName, new BoardCreationOptions.Builder(primary).withBackUpHost(secondary).build());
                    }

                    ASN1InputStream aIn = new ASN1InputStream(new BufferedInputStream(new FileInputStream(file)));
                    long totalSize = file.length();
                    long accumlatedSize = 0;

                    ASN1Object obj;

                    List<byte[]> messages = new ArrayList<>();

                    while ((obj = aIn.readObject()) != null)
                    {
                        byte[] message = obj.getEncoded();

                        accumlatedSize += message.length;

                        messages.add(message);

                        if (messages.size() == BATCH_SIZE)
                        {
                            uploadService.uploadMessages(boardName, messages.toArray(new byte[BATCH_SIZE][]));
                            entry.markProgress(BoardEntry.State.LOADING, messages.size(), (double)accumlatedSize / totalSize);
                            messages.clear();
                        }
                    }

                    if (!messages.isEmpty())
                    {
                        uploadService.uploadMessages(boardName, messages.toArray(new byte[messages.size()][]));
                    }

                    processSemaphore.release();

                    entry.markProgress(BoardEntry.State.LOADING, messages.size(), 1.0);

                    aIn.close();
                }
                catch (Exception e)
                {
                    eventNotifier.notify(EventNotifier.Level.ERROR, "Unable to load file " + file.getName(), e);
                }
                finally
                {
                    dialog.adjustProgress(1);

                    uploadLatch.countDown();
                }
            }
        }
    }

    private ProgressDialog getProgressDialog(final String title, final int numTasks)
        throws InterruptedException, ExecutionException
    {
        FutureTask<ProgressDialog> dialogTask = new FutureTask<>(new Callable<ProgressDialog>()
        {
            @Override
            public ProgressDialog call()
                throws Exception
            {
                return new ProgressDialog(title, numTasks);
            }
        });

        SwingUtilities.invokeLater(dialogTask);

        final ProgressDialog dialog = dialogTask.get();

        SwingUtilities.invokeLater(new Runnable()
        {
            @Override
            public void run()
            {
                dialog.setVisible(true);
            }
        });
        return dialog;
    }

    private class FullShuffleTask
        implements Runnable
    {
        private final File destDir;
        private final String keyID;
        private final BoardTableModel boardModel;
        private final String[] shuffflePlan;
        private final EventNotifier eventNotifier;
        private final URL mixnetConf;
        private final String csvConfig;

        public FullShuffleTask(File destDir, String keyID, BoardTableModel boardModel, String[] shufflePlan, URL mixnetConf, String csvConfig, EventNotifier eventNotifier)
        {
            this.destDir = destDir;
            this.keyID = keyID;
            this.boardModel = boardModel;
            this.shuffflePlan = shufflePlan;
            this.mixnetConf = mixnetConf;
            this.csvConfig = csvConfig;
            this.eventNotifier = eventNotifier;
        }

        public void run()
        {
            try
            {
                XimixRegistrar adminRegistrar = XimixRegistrarFactory.createAdminServiceRegistrar(mixnetConf.openStream(), eventNotifier);

                KeyService keyService = adminRegistrar.connect(KeyService.class);

                ECPublicKeyParameters pubKey = (ECPublicKeyParameters)PublicKeyFactory.createKey(keyService.fetchPublicKey(keyID));

                List<BoardEntry> selected = boardModel.getSelectedEntries();

                if (selected.isEmpty())
                {
                    JOptionPane.showMessageDialog(SwingUtilities.windowForComponent(CommandApplet.this),
                        "Please select some boards to shuffle.",
                        "No Selection Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                CountDownLatch shuffleLatch = new CountDownLatch(selected.size());

                final ProgressDialog dialog = getProgressDialog("Shuffle and Download Progress", selected.size());

                List<CommandService> servicePool = new ArrayList<>();

                for (int i = 0; i != 3; i++)
                {
                    servicePool.add(adminRegistrar.connect(CommandService.class));
                }

                for (int i = 0; i != selected.size(); i++)
                {
                    BoardEntry entry = selected.get(i);

                    entry.setShuffleProgress("Pending");

                    threadPool.submit(new ShuffleAndDownloadTask(destDir, entry, servicePool.get(i % servicePool.size()), keyID, pubKey, shuffleLatch, shuffflePlan, csvConfig, dialog, eventNotifier));
                }

                shuffleLatch.await();

                for (CommandService service : servicePool)
                {
                    service.shutdown();
                }

                keyService.shutdown();
            }
            catch (Exception e)
            {
                eventNotifier.notify(EventNotifier.Level.ERROR, "Cannot perform upload: " + e.getMessage(), e);
            }
        }
    }

    private class ShuffleAndDownloadTask
            implements Runnable
    {
        private final File destDir;
        private final BoardEntry boardEntry;
        private final CommandService commandService;
        private final String keyID;
        private final ECPublicKeyParameters pubKey;
        private final CountDownLatch shuffleLatch;
        private final String[] shufflePlan;
        private final ProgressDialog dialog;
        private final EventNotifier eventNotifier;
        private final String conversionFile;

        public ShuffleAndDownloadTask(File destDir, BoardEntry boardEntry, CommandService commandService, String keyID, ECPublicKeyParameters pubKey, CountDownLatch shuffleLatch, String[] shufflePlan, String conversionFile, ProgressDialog dialog, EventNotifier eventNotifier)
        {
            this.destDir = destDir;
            this.boardEntry = boardEntry;
            this.commandService = commandService;
            this.keyID = keyID;
            this.pubKey = pubKey;
            this.shuffleLatch = shuffleLatch;
            this.shufflePlan = shufflePlan;
            this.conversionFile = conversionFile;
            this.dialog = dialog;
            this.eventNotifier = eventNotifier;
        }

        public void run()
        {
            try
            {
                processSemaphore.acquire();
                maxProcessSemaphore.acquire();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            try
            {
                final CountDownLatch shuffleOperationLatch = new CountDownLatch(1);
                final CountDownLatch seedLatch = new CountDownLatch(1);
                final AtomicBoolean  hasFailed = new AtomicBoolean(false);

                final Map<String, byte[]> seedCommitmentMap = new HashMap<>();

                ShuffleOperationListener shuffleListener = new ShuffleOperationListener()
                {
                    private boolean firstStepDone = false;
                    private String  startNode = "";

                    @Override
                    public void commit(Map<String, byte[]> seedCommitments)
                    {
                        seedCommitmentMap.putAll(seedCommitments);
                        seedLatch.countDown();
                    }

                    @Override
                    public void completed()
                    {
                        shuffleOperationLatch.countDown();
                        System.err.println("done");
                    }

                    @Override
                    public void status(ShuffleStatus statusObject)
                    {
                        String statusMessage = statusObject.getMessage();
                        boardEntry.setShuffleProgress(statusMessage);
                        if (statusMessage.startsWith("Starting"))
                        {
                            startNode = statusObject.getNodeName();
                        }
                        else if (!firstStepDone && !startNode.equals(statusObject.getNodeName()))
                        {
                            firstStepDone = true;
                            processSemaphore.release();
                        }
                        System.err.println("status: " + statusMessage);
                    }

                    @Override
                    public void failed(ShuffleStatus errorObject)
                    {
                        hasFailed.set(true);
                        shuffleOperationLatch.countDown();
                        System.err.println("failed: " + errorObject.getMessage());
                        if (errorObject.getCause() != null)
                        {
                            errorObject.getCause().printStackTrace(System.err);
                        }
                    }
                };

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 0.0);

                Operation<ShuffleOperationListener> shuffleOp = commandService.doShuffleAndMove(boardEntry.getName(), new ShuffleOptions.Builder("MultiColumnRowTransform").withKeyID(keyID).build(), shuffleListener, shufflePlan);

                shuffleOperationLatch.await();

                if (hasFailed.get())
                {
                    throw new IllegalStateException("Shuffle process failed!!!");
                }

                seedLatch.await();

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 0.25);

                final CountDownLatch transcriptCompleted = new CountDownLatch(1);
                final Map<Integer, File> generalTranscripts = new HashMap<>();

                ShuffleTranscriptsDownloadOperationListener transcriptListener = new ShuffleTranscriptsDownloadOperationListener()
                {
                    @Override
                    public void shuffleTranscriptArrived(long operationNumber, final int stepNumber, final InputStream transcript)
                    {
                        try
                        {
                            File transcriptFile            = new File(destDir, boardEntry.getName() + "." + stepNumber + ".gtr");
                            OutputStream generalTranscript = new BufferedOutputStream(new FileOutputStream(transcriptFile));
                            BufferedInputStream bIn        = new BufferedInputStream(transcript);

                            int ch;
                            while ((ch = bIn.read()) >= 0)
                            {
                                generalTranscript.write(ch);
                            }
                            generalTranscript.close();

                            generalTranscripts.put(stepNumber, transcriptFile);
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void completed()
                    {
                        transcriptCompleted.countDown();
                    }

                    @Override
                    public void status(String statusObject)
                    {
                        //To change body of implemented methods use File | Settings | File Templates.
                    }

                    @Override
                    public void failed(String errorObject)
                    {
                        System.err.println(errorObject);
                        transcriptCompleted.countDown();
                    }
                };

                commandService.downloadShuffleTranscripts(boardEntry.getName(), shuffleOp.getOperationNumber(), new ShuffleTranscriptOptions.Builder(TranscriptType.GENERAL).build(), transcriptListener, shufflePlan);

                transcriptCompleted.await();

                SignedDataVerifier signatureVerifier = new SignedDataVerifier(trustAnchor);

                Map<String, byte[][]> seedAndWitnessesMap = commandService.downloadShuffleSeedsAndWitnesses(boardEntry.getName(), shuffleOp.getOperationNumber(), shufflePlan);

                int boardSize = LinkIndexVerifier.getAndCheckBoardSize(generalTranscripts.values().toArray(new File[generalTranscripts.size()]));

                LinkIndexVerifier.Builder builder = new LinkIndexVerifier.Builder(boardSize);

                builder.setNetworkSeeds(seedCommitmentMap, seedAndWitnessesMap);

                for (Integer step : generalTranscripts.keySet())
                {
                    File transcriptFile = generalTranscripts.get(step);

                    if (signatureVerifier.signatureVerified(new CMSSignedDataParser(new JcaDigestCalculatorProviderBuilder().setProvider("BC").build(), new BufferedInputStream(new FileInputStream(transcriptFile)))))
                    {
                         builder.addTranscript(transcriptFile);
                    }
                    else
                    {
                        System.err.println("General commitment check signature failed");
                    }
                }

                LinkIndexVerifier linkVerifier = builder.build();

                byte[] challengeSeed = linkVerifier.getChallengeSeed();

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 0.50);

                final CountDownLatch witnessTranscriptCompleted = new CountDownLatch(1);
                final Map<Integer, File> witnessTranscripts = new HashMap<>();

                transcriptListener = new ShuffleTranscriptsDownloadOperationListener()
                {
                    @Override
                    public void shuffleTranscriptArrived(long operationNumber, final int stepNumber, final InputStream transcript)
                    {
                        try
                        {
                            File transcriptFile            = new File(destDir, boardEntry.getName() + "." + stepNumber + ".wtr");
                            OutputStream witnessTranscript = new BufferedOutputStream(new FileOutputStream(transcriptFile));
                            BufferedInputStream bIn        = new BufferedInputStream(transcript);

                            int ch;
                            while ((ch = bIn.read()) >= 0)
                            {
                                witnessTranscript.write(ch);
                            }
                            witnessTranscript.close();

                            witnessTranscripts.put(stepNumber, transcriptFile);
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void completed()
                    {
                        witnessTranscriptCompleted.countDown();
                    }

                    @Override
                    public void status(String statusObject)
                    {
                        //To change body of implemented methods use File | Settings | File Templates.
                    }

                    @Override
                    public void failed(String errorObject)
                    {
                        System.err.println(errorObject);
                        witnessTranscriptCompleted.countDown();
                    }
                };

                commandService.downloadShuffleTranscripts(boardEntry.getName(), shuffleOp.getOperationNumber(),  new ShuffleTranscriptOptions.Builder(TranscriptType.WITNESSES).withChallengeSeed(challengeSeed).withPairingEnabled(true).build(), transcriptListener, shufflePlan);

                witnessTranscriptCompleted.await();

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 0.70);

                for (Integer key : witnessTranscripts.keySet())
                {
                    File transcriptFile = witnessTranscripts.get(key);
                    File initialTranscript = generalTranscripts.get(key);
                    File nextTranscript = generalTranscripts.get(key + 1);

                    InputStream witnessTranscriptStream = new BufferedInputStream(new FileInputStream(transcriptFile));

                    ECShuffledTranscriptVerifier verifier = new ECShuffledTranscriptVerifier(pubKey, witnessTranscriptStream, initialTranscript, nextTranscript);

                    verifier.verify();

                    witnessTranscriptStream.close();
                }

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 0.75);

                final OutputStream downloadStream = new FileOutputStream(new File(destDir, boardEntry.getName() + ".out"));
                final OutputStream proofLogStream = new FileOutputStream(new File(destDir, boardEntry.getName() + ".plg"));

                Map<String, InputStream> streamSeedCommitments = new HashMap<>();
                for (String key : seedCommitmentMap.keySet())
                {
                    streamSeedCommitments.put(key, new ByteArrayInputStream(seedCommitmentMap.get(key)));
                }

                Map<String, InputStream> streamSeedsAndWitnesses = new HashMap<>();
                for (String key : seedAndWitnessesMap.keySet())
                {
                    byte[][] sAndW = seedAndWitnessesMap.get(key);
                    streamSeedsAndWitnesses.put(key, new ByteArrayInputStream(new SeedAndWitnessMessage(sAndW[0], sAndW[1]).getEncoded()));
                }

                Map<Integer, InputStream> streamWitnessTranscripts = new HashMap<>();
                for (Integer key : witnessTranscripts.keySet())
                {
                    streamWitnessTranscripts.put(key, new FileInputStream(witnessTranscripts.get(key)));
                }

                Map<Integer, InputStream> streamGeneralTranscripts = new HashMap<>();
                for (Integer key : generalTranscripts.keySet())
                {
                    streamGeneralTranscripts.put(key, new FileInputStream(generalTranscripts.get(key)));
                }

                final CountDownLatch shuffleOutputDownloadCompleted = new CountDownLatch(1);

                commandService.downloadShuffleResult(boardEntry.getName(), new DownloadShuffleResultOptions.Builder()
                                                                              .withKeyID("ECENCKEY")
                                                                              .withThreshold(4)
                                                                              .withPairingEnabled(true)
                                                                              .withNodes("A", "B", "C", "D", "E").build(), streamSeedCommitments, streamSeedsAndWitnesses, streamGeneralTranscripts, streamWitnessTranscripts, new DownloadOperationListener()
                {
                    int counter = 0;

                    @Override
                    public void messageDownloaded(int index, byte[] message, List<byte[]> proofs)
                    {
                        try
                        {
                            downloadStream.write(message);
                            for (byte[] proof : proofs)
                            {
                                proofLogStream.write(proof);
                            }
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();  // TODO:
                        }

                        counter++;
                    }

                    @Override
                    public void completed()
                    {
                        shuffleOutputDownloadCompleted.countDown();
                    }

                    @Override
                    public void status(String statusObject)
                    {
                        System.err.println("status: " + statusObject);
                    }

                    @Override
                    public void failed(String errorObject)
                    {
                        shuffleOutputDownloadCompleted.countDown();
                        System.err.println("failed shuffle download: " + errorObject);
                    }
                });

                // make sure you do this before closing the streams!
                shuffleOutputDownloadCompleted.await();

                for (Integer key : witnessTranscripts.keySet())
                {
                    streamWitnessTranscripts.get(key).close();
                }

                for (Integer key : generalTranscripts.keySet())
                {
                    streamGeneralTranscripts.get(key).close();
                }

                downloadStream.close();
                proofLogStream.close();

                // log seed commitments and seed and witnesses

                for (String node : seedCommitmentMap.keySet())
                {
                    FileOutputStream fOut = new FileOutputStream(new File(destDir, boardEntry.getName() + "." + node + ".sc"));

                    fOut.write(seedCommitmentMap.get(node));

                    fOut.close();
                }

                for (String node : seedAndWitnessesMap.keySet())
                {
                    FileOutputStream fOut = new FileOutputStream(new File(destDir, boardEntry.getName() + "." + node + ".svw"));

                    fOut.write(new SeedAndWitnessMessage(seedAndWitnessesMap.get(node)[0], seedAndWitnessesMap.get(node)[1]).getEncoded());

                    fOut.close();
                }

                boardEntry.markProgress(BoardEntry.State.SHUFFLING, 0, 1.0);

                //
                // Convert the votes into a CSV
                //
                BallotUnpacker unpacker = new BallotUnpacker(new File(conversionFile));

                ASN1InputStream aIn = new ASN1InputStream(new FileInputStream(new File(destDir, boardEntry.getName() + ".out")));

                String[] details = boardEntry.getName().split("_"); // The second part of the name tells us which type the race is

                BufferedWriter bfOut = new BufferedWriter(new FileWriter(new File(destDir, boardEntry.getName() + "." + unpacker.getSuffix(details[0], details[1], details[2]) + ".csv")));
                int ballotLength = unpacker.getBallotLength(details[0], details[1], details[2]);

                Object o;
                while ((o = aIn.readObject()) != null)
                {
                    PointSequence seq = PointSequence.getInstance(CustomNamedCurves.getByName("secp256r1").getCurve(), o);
                    ECPoint[]     points = seq.getECPoints();

                    List<Integer>  candidates = new ArrayList<>();
                    int maxCandidateID = 0;

                    for (int i = 0; i != points.length; i++)
                    {
                        int[] votes = unpacker.lookup(details[0], details[1], details[2], points[i]);
                        for  (int j = 0; j != votes.length; j++)
                        {
                            candidates.add(votes[j]);
                            if (votes[j] > maxCandidateID)
                            {
                                maxCandidateID = votes[j];
                            }
                        }
                    }

                    int[] preferences = new int[ballotLength];
                    int   preference = 1;
                    for (int i = 0; i != candidates.size(); i++)
                    {
                        preferences[candidates.get(i) - 1] = preference++;
                    }

                    for (int i = 0; i != preferences.length; i++)
                    {
                        if (i != 0)
                        {
                            bfOut.write(",");
                        }
                        if (preferences[i] != 0)
                        {
                            bfOut.write(Integer.toString(preferences[i]));
                        }
                    }

                    bfOut.newLine();
                }

                bfOut.close();

                // if we get to here download the board contents and empty it
                // TODO: add explicit board clear command.
                final CountDownLatch downloadLatch = new CountDownLatch(1);

                Operation<DownloadOperationListener> op = commandService.downloadBoardContents(boardEntry.getName(),
                                                                                               new DownloadOptions.Builder().build(), new DownloadOperationListener()
                {
                    int counter = 0;

                    @Override
                    public void messageDownloaded(int index, byte[] message, List<byte[]> proofs)
                    {
                         // ignore
                    }

                    @Override
                    public void completed()
                    {
                        downloadLatch.countDown();
                    }

                    @Override
                    public void status(String statusObject)
                    {
                        System.err.println("status: " + statusObject);
                    }

                    @Override
                    public void failed(String errorObject)
                    {
                        downloadLatch.countDown();
                        System.err.println("failed: " + errorObject);
                    }
                });

                downloadLatch.await();
            }
            catch (Exception e)
            {
                eventNotifier.notify(EventNotifier.Level.ERROR, "Cannot perform download: " + e.getMessage(), e);
            }
            finally
            {
                shuffleLatch.countDown();
                maxProcessSemaphore.release();
                dialog.adjustProgress(1);
            }
        }
    }
}
