package x.mvmn.kafkagui.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import x.mvmn.kafkagui.gui.topictree.model.KafkaTopic;
import x.mvmn.kafkagui.gui.topictree.model.KafkaTopicPartition;
import x.mvmn.kafkagui.gui.util.SwingUtil;
import x.mvmn.kafkagui.lang.Tuple;

public class KafkaAdminGui extends JFrame {
	private static final long serialVersionUID = 3826007764248597964L;

	protected final DefaultMutableTreeNode topicsRootNode = new DefaultMutableTreeNode("Topics", true);
	protected final DefaultTreeModel treeModel = new DefaultTreeModel(topicsRootNode);
	protected final JTree topicsTree = new JTree(treeModel);
	protected final JPanel contentPanel = new JPanel(new BorderLayout());
	protected final DefaultTableModel msgTableModel = new DefaultTableModel(new String[] { "Offset", "Key", "Content" }, 0) {
		private static final long serialVersionUID = -4104977444040382766L;

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}
	};
	protected final JTable msgTable = new JTable(msgTableModel);
	protected final JPanel msgPanel = new JPanel(new GridBagLayout());
	protected final JPanel topicMessagesPanel = new JPanel(new GridBagLayout());
	protected final JTextField msgOffsetField = new JTextField();
	protected final JTextField msgKeyField = new JTextField();
	protected final JTextArea msgContent = new JTextArea();
	protected final JComboBox<String> msgViewEncoding = new JComboBox<>(
			new DefaultComboBoxModel<>(Charset.availableCharsets().keySet().toArray(new String[0])));
	protected final JCheckBox msgViewHex = new JCheckBox("Hex");

	protected final JComboBox<String> msgGetOption = new JComboBox<>(new String[] { "Latest", "Earliest" });
	protected final JTextField msgGetCount = new JTextField("10");
	protected final JTextField msgDetectedEndOffset = new JTextField("n/a");
	protected final JTextField msgDetectedBeginOffset = new JTextField("n/a");
	protected final JButton btnGetMessages = new JButton("Get messages");
	protected volatile AdminClient kafkaAdminClient;
	protected final Properties clientConfig;
	protected final List<ConsumerRecord<String, byte[]>> currentResults = new CopyOnWriteArrayList<>();

	protected final JComboBox<String> msgPostProcessor = new JComboBox<>(new String[] { "None", "JSON pretty-print" });

	public KafkaAdminGui(String configName, Properties clientConfig) {
		super(configName + " - MVMn Kafka Client GUI");
		this.clientConfig = clientConfig;
		this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		this.setLayout(new BorderLayout());
		JLabel label = new JLabel("Connecting...", SwingConstants.CENTER);
		label.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
		this.add(label, BorderLayout.CENTER);
		this.pack();
		SwingUtil.moveToScreenCenter(this);

		this.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				AdminClient ac = kafkaAdminClient;
				if (ac != null) {
					SwingUtil.performSafely(() -> ac.close());
				}
			}
		});

		this.setVisible(true);

		SwingUtil.performSafely(() -> {
			AdminClient ac = KafkaAdminClient.create(clientConfig);
			this.kafkaAdminClient = ac;
			// Perform list topics as a test
			Collection<KafkaTopic> topics = ac.listTopics(new ListTopicsOptions().listInternal(true)).listings().get().stream()
					.map(topic -> new KafkaTopic(topic.name(), topic.isInternal())).sorted().collect(Collectors.toList());

			Map<String, TopicDescription> topicDescriptions = ac
					.describeTopics(topics.stream().map(KafkaTopic::getName).collect(Collectors.toSet()),
							new DescribeTopicsOptions().includeAuthorizedOperations(true))
					.all().get();

			SwingUtilities.invokeLater(() -> {
				KafkaAdminGui.this.setVisible(false);
				for (KafkaTopic topic : topics) {
					TopicDescription description = topicDescriptions.get(topic.getName());
					DefaultMutableTreeNode topicNode = new DefaultMutableTreeNode(topic, true);
					topicsRootNode.add(topicNode);

					DefaultMutableTreeNode partitionsNode = new DefaultMutableTreeNode("Partitions", true);
					DefaultMutableTreeNode aclsNode = new DefaultMutableTreeNode("Authorized operations", true);

					topicNode.add(partitionsNode);
					topicNode.add(aclsNode);

					if (description != null) {
						description.partitions().stream()
								.map(p -> KafkaTopicPartition.builder().topic(topic.getName()).number(p.partition()).build())
								.forEach(partition -> partitionsNode.add(new DefaultMutableTreeNode(partition, false)));
						description.authorizedOperations().stream().map(op -> op.name())
								.forEach(opName -> aclsNode.add(new DefaultMutableTreeNode(opName, false)));
					}
				}
				topicsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
				topicsTree.expandRow(0);
				KafkaAdminGui.this.remove(label);
				JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, new JScrollPane(topicsTree), contentPanel);
				splitPane.setResizeWeight(0.2);

				msgViewEncoding.setSelectedItem(StandardCharsets.UTF_8.name());
				GridBagConstraints gbc = new GridBagConstraints();
				gbc.fill = GridBagConstraints.HORIZONTAL;
				gbc.weighty = 0.0;
				gbc.gridy = 0;
				gbc.gridx = 0;
				gbc.weightx = 0.0;
				msgOffsetField.setEditable(false);
				msgPanel.add(msgOffsetField, gbc);
				gbc.gridx = 1;
				gbc.weightx = 1.0;
				msgKeyField.setEditable(false);
				msgPanel.add(msgKeyField, gbc);
				gbc.gridy = 1;
				gbc.gridx = 0;
				gbc.weightx = 0.0;
				msgPanel.add(msgViewHex, gbc);
				gbc.gridx = 1;
				gbc.weightx = 1.0;
				msgPanel.add(msgViewEncoding, gbc);
				gbc.gridy = 2;
				gbc.gridx = 0;
				gbc.weightx = 1.0;
				gbc.weighty = 1.0;
				gbc.gridwidth = 2;
				gbc.fill = GridBagConstraints.BOTH;
				msgContent.setEditable(false);
				msgPanel.add(new JScrollPane(msgContent), gbc);

				gbc = new GridBagConstraints();
				gbc.fill = GridBagConstraints.HORIZONTAL;
				gbc.weighty = 0.0;
				gbc.gridy = 0;
				gbc.gridx = 0;
				gbc.weightx = 0.0;
				topicMessagesPanel.add(msgGetOption, gbc);

				gbc.gridy = 0;
				gbc.gridx = 1;
				gbc.weightx = 0.1;
				SwingUtil.minPrefWidth(msgGetCount, 64);
				topicMessagesPanel.add(msgGetCount, gbc);

				gbc.gridy = 0;
				gbc.gridx = 2;
				gbc.weightx = 1.0;
				topicMessagesPanel.add(btnGetMessages, gbc);

				gbc.gridy = 0;
				gbc.gridx = 3;
				gbc.weightx = 0.3;
				msgDetectedBeginOffset.setEditable(false);
				msgDetectedBeginOffset.setBorder(BorderFactory.createTitledBorder("Begin offset"));
				SwingUtil.minPrefWidth(msgDetectedBeginOffset, 128);
				topicMessagesPanel.add(msgDetectedBeginOffset, gbc);

				gbc.gridy = 0;
				gbc.gridx = 4;
				gbc.weightx = 0.3;
				msgDetectedEndOffset.setEditable(false);
				msgDetectedEndOffset.setBorder(BorderFactory.createTitledBorder("End offset"));
				SwingUtil.minPrefWidth(msgDetectedEndOffset, 128);
				topicMessagesPanel.add(msgDetectedEndOffset, gbc);

				gbc.gridy = 1;
				gbc.gridx = 0;
				gbc.weightx = 1.0;
				gbc.weighty = 1.0;
				gbc.gridwidth = 5;
				gbc.fill = GridBagConstraints.BOTH;
				topicMessagesPanel.add(new JScrollPane(msgTable), gbc);

				JSplitPane msgSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, topicMessagesPanel, msgPanel);
				msgSplitPane.setResizeWeight(0.5);
				contentPanel.add(msgSplitPane);

				KafkaAdminGui.this.setLayout(new BorderLayout());
				KafkaAdminGui.this.add(splitPane, BorderLayout.CENTER);
				KafkaAdminGui.this.pack();
				SwingUtil.minPrefWidth(KafkaAdminGui.this, 800);
				KafkaAdminGui.this.pack();
				SwingUtil.moveToScreenCenter(this);
				msgSplitPane.setDividerLocation(0.5);

				btnGetMessages.addActionListener(actEvt -> this.ifPartitionSelected(topicPartition -> {
					btnGetMessages.setEnabled(false);
					currentResults.clear();
					while (msgTableModel.getRowCount() > 0) {
						msgTableModel.removeRow(0);
					}
					msgTableModel.fireTableDataChanged();
					String countOfMsgsToRetrieve = msgGetCount.getText().replaceAll("[^0-9]+", "");
					int msgsToRetrieve;
					if (countOfMsgsToRetrieve.trim().isEmpty()) {
						msgsToRetrieve = 1;
					} else {
						msgsToRetrieve = Integer.parseInt(countOfMsgsToRetrieve.trim());
					}
					String charset = msgViewEncoding.getSelectedItem().toString();
					SwingUtil.performSafely(() -> {
						clientConfig.setProperty("key.deserializer", StringDeserializer.class.getCanonicalName());
						clientConfig.setProperty("value.deserializer", ByteArrayDeserializer.class.getCanonicalName());
						try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(clientConfig)) {
							TopicPartition tp = new TopicPartition(topicPartition.getA(), topicPartition.getB());
							Long endOffset = consumer.endOffsets(Arrays.asList(tp)).get(tp);
							long finishAt = endOffset != null ? endOffset.longValue() - 1 : 0;
							Long beginningOffset = consumer.beginningOffsets(Arrays.asList(tp)).get(tp);
							SwingUtilities.invokeLater(() -> {
								msgDetectedBeginOffset.setText(beginningOffset != null ? beginningOffset.toString() : "");
								msgDetectedEndOffset.setText(endOffset != null ? endOffset.toString() : "");
							});
							consumer.assign(Arrays.asList(tp));
							consumer.seek(tp, Math.max(endOffset - msgsToRetrieve, beginningOffset));
							boolean done = false;
							int attemptsLeft = 6;
							while (!done && attemptsLeft-- > 0) {
								List<ConsumerRecord<String, byte[]>> page = consumer.poll(Duration.ofSeconds(5)).records(tp);
								currentResults.addAll(page);
								for (ConsumerRecord<String, byte[]> message : page) {
									msgTableModel.addRow(new String[] { String.valueOf(message.offset()), message.key(),
											new String(message.value(), charset) });
								}
								SwingUtilities.invokeLater(msgTableModel::fireTableDataChanged);
								if (!page.isEmpty()) {
									long lastRecordOffset = page.get(page.size() - 1).offset();
									done = lastRecordOffset >= finishAt;
								}
							}
						} finally {
							SwingUtilities.invokeLater(() -> {
								btnGetMessages.setEnabled(true);
							});
						}
					});
				}));

				msgTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
				msgTable.setRowSelectionAllowed(true);
				msgTable.setColumnSelectionAllowed(false);
				msgTable.getSelectionModel().addListSelectionListener(evt -> {
					int idx = msgTable.getSelectedRow();
					if (idx >= 0 && idx < currentResults.size()) {
						ConsumerRecord<String, byte[]> record = currentResults.get(idx);
						msgOffsetField.setText(String.valueOf(record.offset()));
						msgKeyField.setText(record.key());
						try {
							msgContent.setText(new String(record.value(), msgViewEncoding.getSelectedItem().toString()));
						} catch (UnsupportedEncodingException e1) {
							// Should never happen because we choose an encoring from the list of supported encodings (charsets)
							e1.printStackTrace();
						}
					}
				});

				KafkaAdminGui.this.setVisible(true);
			});
		});
	}

	protected void ifPartitionSelected(Consumer<Tuple<String, Integer, Void, Void, Void>> action) {
		Object selectedObject = topicsTree.getLastSelectedPathComponent();
		if (selectedObject instanceof DefaultMutableTreeNode
				&& ((DefaultMutableTreeNode) selectedObject).getUserObject() instanceof KafkaTopicPartition) {
			KafkaTopicPartition partitionModel = (KafkaTopicPartition) ((DefaultMutableTreeNode) selectedObject).getUserObject();
			String topic = partitionModel.getTopic();
			Integer partition = partitionModel.getNumber();
			action.accept(Tuple.<String, Integer, Void, Void, Void> builder().a(topic).b(partition).build());
		} else {
			JOptionPane.showMessageDialog(this, "Please select a topic partition");
		}
	}
}
