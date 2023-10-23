package weka.finito;

import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.Map.Entry;

import java.lang.System;
import java.util.concurrent.TimeUnit;

import security.DGK.DGKPublicKey;
import security.misc.HomomorphicException;
import security.socialistmillionaire.alice;
import security.paillier.PaillierPublicKey;
import weka.classifiers.trees.j48.BinC45ModelSelection;
import weka.classifiers.trees.j48.C45PruneableClassifierTree;
import weka.classifiers.trees.j48.ClassifierTree;
import weka.core.Instances;

import weka.core.SerializationHelper;
import weka.finito.structs.BigIntegers;
import weka.finito.structs.level_order_site;
import weka.finito.structs.NodeInfo;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static weka.finito.utils.shared.*;


public final class server implements Runnable {

	private final SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
	private final SSLSocketFactory socket_factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
	private static final String os = System.getProperty("os.name").toLowerCase();
	private final String training_data;
	private final String [] level_site_ips;
	private int [] level_site_ports = null;
	private int port = -1;
	private PaillierPublicKey paillier_public;
	private DGKPublicKey dgk_public;
	private final int precision;
	private ClassifierTree ppdt = null;
	private final List<String> leaves = new ArrayList<>();
	private final List<level_order_site> all_level_sites = new ArrayList<>();

	private final int server_port;

	private int evaluations = 1;

    public static void main(String[] args) {
        int port = 0;
		int precision = 0;
		String training_data = null;
		boolean use_level_sites = false;

		// Get data for training.
		if (args.length == 1) {
			training_data = args[0];
		}
		else if (args.length == 2){
			training_data = args[0];
			use_level_sites = args[1].equalsIgnoreCase("--client");
		}
		else {
			System.out.println("Missing Training Data set as an argument parameter");
			System.exit(1);
		}

        try {
            port = Integer.parseInt(System.getenv("PORT_NUM"));
        } catch (NumberFormatException e) {
			System.out.println("No level site port provided");
            System.exit(1);
        }

		try {
			precision = Integer.parseInt(System.getenv("PRECISION"));
		} catch (NumberFormatException e) {
			System.out.println("Precision is not defined.");
			System.exit(1);
		}
        
        // Pass data to level sites.
        String level_domains_str = System.getenv("LEVEL_SITE_DOMAINS");
        if(level_domains_str == null || level_domains_str.isEmpty()) {
			System.out.println("No level site domains provided");
            System.exit(1);
        }
        String[] level_domains = level_domains_str.split(",");

		// Create and run the server.
        System.out.println("Server Initialized and started running");

		server server;
		// Pick either Level-Sites or no Level-site
		if (use_level_sites) {
			server = new server(training_data, level_domains, port, precision, port);
		}
		else {
			server = new server(training_data, precision, port);
		}
		server.run();
	}

	// For local host testing, (GitHub Actions CI, on PrivacyTest.java, for level-sites)
	public server(String training_data, String [] level_site_ips, int [] level_site_ports, int precision,
					   int server_port) {
		this.training_data = training_data;
		this.level_site_ips = level_site_ips;
		this.level_site_ports = level_site_ports;
		this.precision = precision;
		this.server_port = server_port;
	}

	// For Cloud environment, (Testing with Kubernetes/EKS, for level-sites)
	public server(String training_data, String [] level_site_domains, int port, int precision, int server_port) {
		this.training_data = training_data;
		this.level_site_ips = level_site_domains;
		this.port = port;
		this.precision = precision;
		this.server_port = server_port;
		// I will likely want more than 1 test with server-site!
		this.evaluations = 100;
	}

	// For testing, but having just a client and server, just do one evaluation for the sake of testing.
	public server(String training_data, int precision, int server_port) {
		this.training_data = training_data;
		this.level_site_ips = null;
		this.precision = precision;
		this.server_port = server_port;
	}

	private void run_server_site(int port) throws IOException, HomomorphicException, ClassNotFoundException {
		int count = 0;
		try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port)) {
			serverSocket.setEnabledProtocols(protocols);
			serverSocket.setEnabledCipherSuites(cipher_suites);

			System.out.println("Server will be waiting for direct evaluation from client");
			while (count < evaluations) {
				try (SSLSocket client_site = (SSLSocket) serverSocket.accept()) {
					evaluate_with_client_directly(client_site);
				}
				++count;
			}
		}
	}

	private void evaluate_with_client_directly(SSLSocket client_site)
			throws IOException, HomomorphicException, ClassNotFoundException {

		ObjectOutputStream to_client_site = new ObjectOutputStream(client_site.getOutputStream());
		ObjectInputStream from_client_site = new ObjectInputStream(client_site.getInputStream());

		Object client_input;
		Hashtable<String, BigIntegers> features = new Hashtable<>();

		// Get encrypted features
		client_input = from_client_site.readObject();
		if (client_input instanceof Hashtable) {
			for (Entry<?, ?> entry: ((Hashtable<?, ?>) client_input).entrySet()){
				if (entry.getKey() instanceof String && entry.getValue() instanceof BigIntegers) {
					features.put((String) entry.getKey(), (BigIntegers) entry.getValue());
				}
			}
		}
		alice Niu = new alice(client_site);
		Niu.setPaillierPublicKey(paillier_public);
		Niu.setDGKPublicKey(dgk_public);

		long start_time = System.nanoTime();
		int previous_index = 0;

		// Traverse DT until you hit a leaf, the client has to track the index...
		for (level_order_site level_site_data : all_level_sites) {
			level_site_data.set_current_index(previous_index);

			// Handle at a level...
			NodeInfo leaf = traverse_level(level_site_data, features, to_client_site, Niu);

			// You found a leaf! No more traversing needed!
			if (leaf != null) {
				// Tell the client the value
				to_client_site.writeInt(-1);
				to_client_site.writeObject(leaf.getVariableName());
				to_client_site.flush();
				long stop_time = System.nanoTime();
				double run_time = (double) (stop_time - start_time);
				run_time = run_time / 1000000;
				System.out.printf("Total Server-Site run-time took %f ms\n", run_time);
				break;
			}
			else {
				// Update the index for next merry-go-round
				previous_index = level_site_data.get_next_index();
			}
		}
	}

	private void client_communication() throws Exception {
		ServerSocket serverSocket = new ServerSocket(server_port);
		System.out.println("Server ready to get public keys from client");

		try (Socket client_site = serverSocket.accept()) {
			ObjectOutputStream to_client_site = new ObjectOutputStream(client_site.getOutputStream());
			ObjectInputStream from_client_site = new ObjectInputStream(client_site.getInputStream());

			// Receive a message from the client to get their keys
			Object o = from_client_site.readObject();
			this.paillier_public = (PaillierPublicKey) o;

			o = from_client_site.readObject();
			this.dgk_public = (DGKPublicKey) o;
			System.out.println("Server collected keys from client");

			// Train level-sites
			get_level_site_data(ppdt, all_level_sites);

			System.out.println("Server trained DT and created level-sites");

			// Now I know the leaves to send back to the client
			String [] leaf_array = leaves.toArray(new String[0]);
			to_client_site.writeObject(leaf_array);

			System.out.println("Server sent the leaves back to the client");
		}
		serverSocket.close();
	}

	private static boolean isUnix() {
		return (server.os.contains("nix") || server.os.contains("nux") || server.os.contains("aix"));
	}

	private static void printTree(ClassifierTree j48, String base_name)
			throws Exception {
		File output_dot_file = new File("output", base_name + ".dot");
		File output_image_file = new File("output", base_name + ".png");

		try (PrintWriter out = new PrintWriter(output_dot_file)) {
			out.println(j48.graph());
		}
		if (isUnix()) {
			String[] c = {"dot", "-Tpng", output_dot_file.toString(), "-o", output_image_file.toString()};
			try {
				Process p = Runtime.getRuntime().exec(c);
				if(!p.waitFor(5, TimeUnit.SECONDS)) {
					p.destroy();
				}
			}
			catch (IOException e) {
				System.out.println("Can't generate image, so skip for now...");
			}
		}
	}

	// Reference:
	// https://stackoverflow.com/questions/33556543/how-to-save-model-and-apply-it-on-a-test-dataset-on-java/33571811#33571811
	// Build J48 as it uses C45?
	// https://weka.sourceforge.io/doc.dev/weka/classifiers/trees/j48/C45ModelSelection.html
	private static ClassifierTree train_decision_tree(String arff_file)
			throws Exception {
		File training_file = new File(arff_file);
		String base_name = training_file.getName().split("\\.")[0];
		File output_model_file = new File("output", base_name + ".model");

		File dir = new File("output");
		if (!dir.exists()) {
			if(!dir.mkdirs()) {
				System.err.println("Error Creating output directory to store models and images!");
				System.exit(1);
			}
		}

		if (arff_file.endsWith(".model")) {
			ClassifierTree j48 = (ClassifierTree) SerializationHelper.read(arff_file);
			printTree(j48, base_name);
			return j48;
		}

		// If this is a .arff file
		Instances train = null;

		try (BufferedReader reader = new BufferedReader(new FileReader(arff_file))) {
			train = new Instances(reader);
		} catch (IOException e2) {
			e2.printStackTrace();
		}
		assert train != null;
		train.setClassIndex(train.numAttributes() - 1);

		// https://weka.sourceforge.io/doc.dev/weka/classifiers/trees/j48/C45ModelSelection.html
		// J48 -B -C 0.25 -M 2
		// -M 2 is minimum 2, DEFAULT
		// -B this tree ONLY works for binary split is true, so pick this model...
		// -C 0.25, default confidence
		BinC45ModelSelection j48_model = new BinC45ModelSelection(2, train, true, false);
		ClassifierTree j48 = new C45PruneableClassifierTree(j48_model, true, (float) 0.25, true, true, true);

	    j48.buildClassifier(train);
		SerializationHelper.write(output_model_file.toString(), j48);
		printTree(j48, base_name);
	    return j48;
	}

	// Given a Plain-text Decision Tree, split the data up for each level site.
	private void get_level_site_data(ClassifierTree root, List<level_order_site> all_level_sites)
			throws Exception {

		if (root == null) {
			return;
		}

		Queue<ClassifierTree> q = new LinkedList<>();
		q.add(root);
		int level = 0;

		while (!q.isEmpty()) {
			level_order_site Level_Order_S = new level_order_site(level, paillier_public, dgk_public);
			int n = q.size();

			while (n > 0) {

				ClassifierTree p = q.peek();
				q.remove();

				NodeInfo node_info = null;
				assert p != null;
				if (p.isLeaf()) {
					String variable = p.getLocalModel().dumpLabel(0, p.getTrainingData());
					leaves.add(variable);
					node_info = new NodeInfo(true, hash(variable), 0);
					Level_Order_S.append_data(node_info);
				}
				else {
					float threshold = 0;
					for (int i = 0; i < p.getSons().length; i++) {
						String leftSide = p.getLocalModel().leftSide(p.getTrainingData());
						String rightSide = p.getLocalModel().rightSide(i, p.getTrainingData());

						char[] rightSideChar = rightSide.toCharArray();
						int type = 0;

						char[] rightValue = new char[0];
						if (rightSideChar[1] == '=') {
							type = 1;
							rightValue = new char[rightSideChar.length - 3];
							System.arraycopy(rightSideChar, 3, rightValue, 0, rightSideChar.length - 3);
							String rightValueStr = new String(rightValue);
							if (rightValueStr.equals("other")) {
								type = 2;
								threshold = 1;
							}
						}
						else if (rightSideChar[1] == '!') {
							type = 4;
							if (rightSideChar[2] == '=') {
								rightValue = new char[rightSideChar.length - 4];
								System.arraycopy(rightSideChar, 4, rightValue, 0, rightSideChar.length - 4);
								String rightValueStr = new String(rightValue);
								if (rightValueStr.equals("other")) {
									threshold = 0;
								}
								if ((rightValueStr.equals("t"))||(rightValueStr.equals("f"))||(rightValueStr.equals("yes"))||(rightValueStr.equals("no"))) {
									type = 6;
								}
							}
						}
						else if (rightSideChar[1] == '>') {
							if (rightSideChar[2] == '=') {
								type = 2;
								rightValue = new char[rightSideChar.length - 4];
								System.arraycopy(rightSideChar, 4, rightValue, 0, rightSideChar.length - 4);
							}
							else {
								type = 3;
								rightValue = new char[rightSideChar.length - 3];
								System.arraycopy(rightSideChar, 3, rightValue, 0, rightSideChar.length - 3);
							}
						}
						else if (rightSideChar[1] == '<') {
							if (rightSideChar[2] == '=') {
								type = 4;
								rightValue = new char[rightSideChar.length - 4];
								System.arraycopy(rightSideChar, 4, rightValue, 0, rightSideChar.length - 4);
							}
							else {
								type = 5;
								rightValue = new char[rightSideChar.length - 3];
								System.arraycopy(rightSideChar, 3, rightValue, 0, rightSideChar.length - 3);
							}
						}

						String rightValueStr = new String(rightValue);

						if (!rightValueStr.equals("other")) {
							if (rightValueStr.equals("t") || rightValueStr.equals("yes")) {
								threshold = 1;
							}
							else if (rightValueStr.equals("f") || rightValueStr.equals("no")) {
								threshold = 0;
							}
							else {
								threshold = Float.parseFloat(rightValueStr);
							}
						}
						node_info = new NodeInfo(false, leftSide, type);
						node_info.encrypt(threshold, precision, paillier_public, dgk_public);
						q.add(p.getSons()[i]);
					}

					assert node_info != null;
					if (!node_info.is_leaf){
						NodeInfo additionalNode = null;
						if (node_info.comparisonType == 1) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 6);
						}
						else if (node_info.comparisonType == 2) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 5);
						}
						else if (node_info.comparisonType == 3) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 4);
						}
						else if (node_info.comparisonType == 4) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 3);
						}
						else if (node_info.comparisonType == 5) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 2);
						}
						else if (node_info.comparisonType == 6) {
							additionalNode = new NodeInfo(false, node_info.getVariableName(), 1);
						}
						assert additionalNode != null;
						additionalNode.encrypt(threshold, precision, paillier_public, dgk_public);
						Level_Order_S.append_data(additionalNode);
					}
					Level_Order_S.append_data(node_info);
				}// else
				n--;
			} // While n > 0 (nodes > 0)
			all_level_sites.add(Level_Order_S);
			++level;
		} // While a tree is not empty
	}

	// Run should
	// Train, ONLY if necessary
	// Evaluate, be prepared for either level-site or no level-site case, 1 time
	public void run() {

		try {
			// Train the DT if you have to.
			if (ppdt == null) {
				ppdt = train_decision_tree(this.training_data);
				// Get Public Keys from Client AND train level-sites
				client_communication();	
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}

		// If we are testing without level-sites do this...
		if (this.level_site_ips != null) {
			train_level_sites();
		} else {
			try {
				run_server_site(this.server_port);
			}
			catch (IOException | HomomorphicException | ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private void train_level_sites() {
		ObjectOutputStream to_level_site;
		ObjectInputStream from_level_site;
		int connection_port;

		// There should be at least 1 IP Address for each level site
        assert this.level_site_ips != null;
        if(this.level_site_ips.length < all_level_sites.size()) {
			String error = String.format("Please create more level-sites for the " +
					"decision tree trained from %s", training_data);
			throw new RuntimeException(error);
		}

		// Send the data to each level site, use data in-transit encryption
		for (int i = 0; i < all_level_sites.size(); i++) {
			level_order_site current_level_site = all_level_sites.get(i);

			if (port == -1) {
				connection_port = this.level_site_ports[i];
			}
			else {
				connection_port = this.port;
			}

			if (i + 1 != all_level_sites.size()) {
				current_level_site.set_next_level_site(level_site_ips[(i + 1) % level_site_ips.length]);
			}

			current_level_site.set_next_level_site_port(connection_port);

			try(SSLSocket level_site = (SSLSocket) socket_factory.createSocket(level_site_ips[i], connection_port)) {
				// Step: 3
				level_site.setEnabledProtocols(protocols);
				level_site.setEnabledCipherSuites(cipher_suites);

				// Step: 4 {optional}
				level_site.startHandshake();

				System.out.println("training level-site " + i + " on port:" + connection_port);
				to_level_site = new ObjectOutputStream(level_site.getOutputStream());
				from_level_site = new ObjectInputStream(level_site.getInputStream());
				to_level_site.writeObject(current_level_site);
				if(from_level_site.readBoolean()) {
					System.out.println("Training Successful on port:" + connection_port);
				}
				else {
					System.out.println("Training NOT Successful on port:" + connection_port);
				}
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
