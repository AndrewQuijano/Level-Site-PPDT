package edu.fiu.adwise.weka.finito;

import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.lang.System;

import org.apache.commons.io.serialization.ValidatingObjectInputStream;
import edu.fiu.adwise.homomorphic_encryption.dgk.DGKKeyPairGenerator;
import edu.fiu.adwise.homomorphic_encryption.dgk.DGKPrivateKey;
import edu.fiu.adwise.homomorphic_encryption.dgk.DGKPublicKey;
import edu.fiu.adwise.homomorphic_encryption.misc.HomomorphicException;
import edu.fiu.adwise.homomorphic_encryption.paillier.PaillierCipher;
import edu.fiu.adwise.homomorphic_encryption.paillier.PaillierKeyPairGenerator;
import edu.fiu.adwise.homomorphic_encryption.paillier.PaillierPrivateKey;
import edu.fiu.adwise.homomorphic_encryption.paillier.PaillierPublicKey;
import edu.fiu.adwise.homomorphic_encryption.socialistmillionaire.bob_joye;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.fiu.adwise.weka.finito.structs.features;
import static edu.fiu.adwise.weka.finito.utils.shared.*;
import edu.fiu.adwise.weka.finito.utils.LabelEncoder;

/**
 * Client class for handling encrypted communication and classification tasks.
 */
public final class client implements Runnable {

	/** Logger for logging messages and errors. */
	private static final Logger logger = LogManager.getLogger(client.class);

	/** SSL server socket factory for secure communication. */
	private static final SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();

	/** SSL socket factory for secure communication. */
	private static final SSLSocketFactory socket_factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

	/** Path to the features file. */
	private final String features_file;

	/** Key size for encryption. */
	private final int key_size;

	/** Precision for floating-point operations. */
	private final int precision;

	/** IP addresses of level sites. */
	private final String[] level_site_ips;

	/** Ports of level sites. */
	private final int[] level_site_ports;

	/** Port for communication. */
	private final int port;

	/** Classification result. */
	private String classification = null;

	/** DGK key pair. */
	private KeyPair dgk;

	/** Paillier key pair. */
	private KeyPair paillier;

	/** Features object for encrypted data. */
	private features feature = null;

	/** Flag indicating if classification is complete. */
	private boolean classification_complete = false;

	/** DGK public key. */
	private DGKPublicKey dgk_public_key;

	/** Paillier public key. */
	private PaillierPublicKey paillier_public_key;

	/** DGK private key. */
	private DGKPrivateKey dgk_private_key;

	/** Paillier private key. */
	private PaillierPrivateKey paillier_private_key;

	/** Server IP address. */
	private final String server_ip;

	/** Client IP address. */
	private final String client_ip;

	/** Server port for communication. Level-sites will reach out to the client after level-site 0 */
	private final int server_port;

	/** Label encoder for classification labels. */
	private LabelEncoder label_encoder;

	/**
	 * Main method for Kubernetes deployment.
	 * Reads environment variables, initializes the client, and starts the evaluation process.
	 *
	 * @param args Command-line arguments.
	 *             args[0] - Path to the features file.
	 *             args[1] (optional) - "--server" to test with a single server-site.
	 */
    public static void main(String[] args) {
		setup_tls();

        // Declare variables needed.
        int key_size = -1;
        int precision = -1;
		int port = -1;
        String level_site_string;
		String server_ip;
		String client_ip;

        // Read in our environment variables.
        level_site_string = System.getenv("LEVEL_SITE_DOMAINS");
        if(level_site_string == null || level_site_string.isEmpty()) {
            logger.fatal("No level site domains provided.");
            System.exit(1);
        }
        String[] level_domains = level_site_string.split(",");

        try {
            port = Integer.parseInt(System.getenv("PORT_NUM"));
        } catch (NumberFormatException e) {
            logger.fatal("No port provided for the Level Sites.");
            System.exit(1);
        }

        try {
            precision = Integer.parseInt(System.getenv("PRECISION"));
        } catch (NumberFormatException e) {
            logger.fatal("No Precision value provided.");
            System.exit(1);
        }

        try {
            key_size = Integer.parseInt(System.getenv("PPDT_KEY_SIZE"));
        } catch (NumberFormatException e) {
            logger.fatal("No crypto key provided value provided.");
            System.exit(1);
        }

		server_ip = System.getenv("SERVER");
		if(server_ip == null || server_ip.isEmpty()) {
			logger.fatal("No server site domain provided.");
			System.exit(1);
		}

		client_ip = System.getenv("CLIENT_HOSTNAME");
		if(client_ip == null || client_ip.isEmpty()) {
			logger.fatal("No client hostname provided.");
			System.exit(1);
		}

		client test = null;
		if (args.length == 1) {
			// Test with level-sites
			test = new client(key_size, args[0], level_domains, port, precision, server_ip, port, client_ip);
		}
		else if (args.length == 2) {
			// Test with just a server-site directly
			if (args[1].equalsIgnoreCase("--server")) {
				test = new client(key_size, args[0], precision, server_ip, port, client_ip);
			}
			else {
				test = new client(key_size, args[0], level_domains, port, precision, server_ip, port, client_ip);
			}
		}
		else {
			logger.fatal("Missing Testing Data set as an argument parameter");
			System.exit(1);
		}
		test.run();
    }

	/**
	 * Constructor for local host testing with GitHub Actions.
	 *
	 * @param key_size       Size of the cryptographic key.
	 * @param features_file  Path to the features file.
	 * @param level_site_ips Array of level-site IPs.
	 * @param level_site_ports Array of level-site ports.
	 * @param precision      Precision for calculations.
	 * @param server_ip      Server IP address.
	 * @param server_port    Server port.
	 * @param client_ip      Client IP address.
	 */
	public client(int key_size, String features_file, String [] level_site_ips, int [] level_site_ports,
				  int precision, String server_ip, int server_port, String client_ip) {
		this.key_size = key_size;
		this.features_file = features_file;
		this.level_site_ips = level_site_ips;
		this.level_site_ports = level_site_ports;
		this.precision = precision;
		this.port = -1;
		this.server_ip = server_ip;
		this.server_port = server_port;
		this.client_ip = client_ip;
	}

	/**
	 * Constructor for Kubernetes testing.
	 *
	 * @param key_size       Size of the cryptographic key.
	 * @param features_file  Path to the features file.
	 * @param level_site_ips Array of level-site IPs.
	 * @param port           Port for communication.
	 * @param precision      Precision for calculations.
	 * @param server_ip      Server IP address.
	 * @param server_port    Server port.
	 * @param client_ip      Client IP address.
	 */
	public client(int key_size, String features_file, String [] level_site_ips, int port,
				  int precision, String server_ip, int server_port, String client_ip) {
		this.key_size = key_size;
		this.features_file = features_file;
		this.level_site_ips = level_site_ips;
		this.level_site_ports = null;
		this.precision = precision;
		this.port = port;
		this.server_ip = server_ip;
		this.server_port = server_port;
		this.client_ip = client_ip;
	}

	/**
	 * Constructor for testing with a single server-site.
	 *
	 * @param key_size       Size of the cryptographic key.
	 * @param features_file  Path to the features file.
	 * @param precision      Precision for calculations.
	 * @param server_ip      Server IP address.
	 * @param server_port    Server port.
	 * @param client_ip      Client IP address.
	 */
	public client(int key_size, String features_file,
                  int precision, String server_ip, int server_port, String client_ip) {
		this.key_size = key_size;
		this.features_file = features_file;
        this.level_site_ips = null;
		this.level_site_ports = null;
		this.port = -1;
		this.precision = precision;
		this.server_ip = server_ip;
		this.server_port = server_port;
		this.client_ip = client_ip;
	}


	/**
	 * Retrieves the classification result after evaluation.
	 *
	 * @return The classification result as a String.
	 */
	public String getClassification() {
		return this.classification;
	}

	/**
	 * Generates cryptographic key pairs for DGK and Paillier encryption.
	 */
	public void generate_keys() {
		// Generate Key Pairs
		DGKKeyPairGenerator p = new DGKKeyPairGenerator();
		p.initialize(key_size, null);
		dgk = p.generateKeyPair();

		PaillierKeyPairGenerator pa = new PaillierKeyPairGenerator();
		pa.initialize(key_size, null);
		paillier = pa.generateKeyPair();

		dgk_public_key = (DGKPublicKey) dgk.getPublic();
		paillier_public_key = (PaillierPublicKey) paillier.getPublic();
		dgk_private_key = (DGKPrivateKey) dgk.getPrivate();
		paillier_private_key = (PaillierPrivateKey) paillier.getPrivate();
	}

	/**
	 * Checks if cryptographic keys are needed by attempting to read them from files.
	 *
	 * @return True if keys are needed, false otherwise.
	 */
	private boolean need_keys() {
		try {
			dgk_public_key = DGKPublicKey.readKey("dgk.pub");
			paillier_public_key = PaillierPublicKey.readKey("paillier.pub");
			dgk_private_key = DGKPrivateKey.readKey("dgk");
			paillier_private_key = PaillierPrivateKey.readKey("paillier");
			dgk = new KeyPair(dgk_public_key, dgk_private_key);
			paillier = new KeyPair(paillier_public_key, paillier_private_key);
			try (ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream("label_encoder.bin"))) {
				label_encoder = (LabelEncoder) inputStream.readObject();
			}
			return false;
		}
		catch (IOException | ClassNotFoundException e) {
			return true;
		}
    }

	/**
	 * Sets up the client with the server-site by exchanging public keys and receiving the label encoder.
	 *
	 * @param paillier Paillier public key.
	 * @param dgk      DGK public key.
	 * @throws IOException            If an I/O error occurs.
	 * @throws ClassNotFoundException If the label encoder class is not found.
	 */
	private void setup_with_server_site(PaillierPublicKey paillier, DGKPublicKey dgk)
			throws IOException, ClassNotFoundException {
        logger.info("Connecting to {}:{} for set-up", server_ip, server_port);
		try (Socket server_site = createSocket(server_ip, server_port)) {
			ObjectOutputStream to_server_site = new ObjectOutputStream(server_site.getOutputStream());
			ValidatingObjectInputStream from_server_site = get_ois(server_site);

			// Receive a message from the client to get their keys
			to_server_site.writeObject(paillier);
			to_server_site.writeObject(dgk);
			to_server_site.flush();
			logger.info("Just sent keys over, if this is slow, do not worry, server is training level-sites now.");

			// Get Label Encoder of leaves from Server-site
			Object o = from_server_site.readObject();
			label_encoder = (LabelEncoder) o;
		}
		logger.info("Completed set-up with server");
	}

	/**
	 * Reads features from a file and initializes the feature object.
	 *
	 * @param path               Path to the features file.
	 * @param paillier_public_key Paillier public key.
	 * @param dgk_public_key      DGK public key.
	 * @param precision           Precision for calculations.
	 * @param encoder             Label encoder.
	 * @return A features object.
	 * @throws IOException            If an I/O error occurs.
	 * @throws HomomorphicException   If a homomorphic encryption error occurs.
	 */
	private features read_features(String path,
								   PaillierPublicKey paillier_public_key,
								   DGKPublicKey dgk_public_key, int precision, LabelEncoder encoder)
					throws IOException, HomomorphicException {

        return new features(path, precision, paillier_public_key, dgk_public_key, encoder);
	}

	/**
	 * Evaluates the features with the server-site.
	 *
	 * @param server_site Socket connection to the server-site.
	 * @throws IOException            If an I/O error occurs.
	 * @throws HomomorphicException   If a homomorphic encryption error occurs.
	 * @throws ClassNotFoundException If a class is not found during deserialization.
	 */
	private void evaluate_with_server_site(Socket server_site)
			throws IOException, HomomorphicException, ClassNotFoundException {
		// Communicate with each Level-Site
		Object o;
		bob_joye client;

		// Send the Public Keys using Alice and Bob
		client = new bob_joye(paillier, dgk);
		client.set_socket(server_site);

		// Send the encrypted data to Level-Site
		ObjectOutputStream oos = new ObjectOutputStream(server_site.getOutputStream());
		oos.writeObject(this.feature);
		oos.flush();

		// Yup, I need the while loop here because all level-sites are at server
		int comparison_type;
		while (true) {
			comparison_type = client.readInt();
			if (comparison_type == -1) {
				this.classification_complete = true;
				break;
			}
			else if (comparison_type == 0) {
				logger.debug("Comparing two Paillier Values");
				client.setDGKMode(false);
				client.Protocol2();
			}
			else if (comparison_type == 1) {
				logger.debug("Comparing two DGK Values");
				client.setDGKMode(true);
				client.Protocol2();
			}
			else if (comparison_type == 2) {
				logger.debug("Comparing two DGK Values, Encrypted Equals!");
				client.setDGKMode(true);
				client.encrypted_equals();
			}
		}

		o = client.readObject();
		if (o instanceof String) {
			classification = (String) o;
			BigInteger temp = PaillierCipher.decrypt(new BigInteger(classification), paillier_private_key);
			classification = new String(temp.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Evaluates the features with a level-site.
	 *
	 * @param level_site Socket connection to the level-site.
	 * @param level      The level of the site in the hierarchy.
	 * @throws IOException            If an I/O error occurs.
	 * @throws ClassNotFoundException If a class is not found during deserialization.
	 * @throws HomomorphicException   If a homomorphic encryption error occurs.
	 */
	private void evaluate_with_level_site(Socket level_site, int level)
			throws IOException, ClassNotFoundException, HomomorphicException {
		// Communicate with each Level-Site
		long start_time = System.nanoTime();
		Object o;
		bob_joye client;
		ObjectInputStream from_level_site = null;

		// Create I/O stream and send features, only need to send features once to level-site 0...
		// Level-site 0 will take care of passing it down
		if (level == 0) {
			ObjectOutputStream to_level_site = new ObjectOutputStream(level_site.getOutputStream());
			to_level_site.writeObject(this.feature);
			to_level_site.flush();
		}

		// Send the Public Keys using Alice and Bob
		client = new bob_joye(paillier, dgk);
		client.set_socket(level_site);
		if (level == 0) {
			from_level_site = get_ois(level_site);
		}

		// Get the comparison
		// I am not sure why I need this loop, but you will only need 1 comparison.
		int comparison_type;
		while (true) {
			if (level == 0) {
				comparison_type = from_level_site.readInt();
			}
			else {
				comparison_type = client.readInt();
			}

			if (comparison_type == -1) {
				this.classification_complete = true;
				break;
			}
			else if (comparison_type == 0) {
				client.setDGKMode(false);
				client.Protocol2();
			}
			else if (comparison_type == 1) {
				client.setDGKMode(true);
				client.Protocol2();
			}
			else if (comparison_type == 2) {
				client.setDGKMode(true);
				client.encrypted_equals();
			}
		}
		long stop_time = System.nanoTime();
		double run_time = (double) (stop_time - start_time);
		run_time = run_time / 1000000;
		logger.info(String.format("Total Client run-time took %f ms for the level-site %d\n", run_time, level));

		// Get boolean from level-site:
		// true - get leaf value
		// false - get encrypted AES index for next round
		classification_complete = client.readBoolean();
		if (classification_complete) {
			start_time = System.nanoTime();
			logger.info("Classification complete!");
			o = client.readObject();
			if (o instanceof String) {
				classification = (String) o;
				BigInteger temp = PaillierCipher.decrypt(new BigInteger(classification), paillier_private_key);
				classification = new String(temp.toByteArray(), StandardCharsets.UTF_8);
				stop_time = System.nanoTime();
				run_time = (double) (stop_time - start_time);
				run_time = run_time / 1000000;
				logger.info(String.format("Client took %f ms to decrypt a %d bit leaf\n", run_time, temp.bitLength()));
			}
		}
	}

	/**
	 * Runs the client process, including key generation, setup, and evaluation.
	 */
	public void run() {

		// Step: 1
		boolean talk_to_server_site = this.need_keys();

		try {
			// Don't regenerate keys if you are just using a different VALUES file
			if (talk_to_server_site) {
				logger.info("Need to generate keys...");
				generate_keys();
			}
			else {
				logger.info("I already read the keys from a file made from a previous run...");
			}

			// Client needs to give server-site public key (to give to level-sites)
			// Client needs to know all possible classes...
			if (talk_to_server_site) {
				// Don't send keys to server-site to ask for classes now it is assumed level-sites are up
				setup_with_server_site(paillier_public_key, dgk_public_key);
			}
			else {
				logger.info("Not contacting server-site. Seems you just want to test on the" +
						" same PPDT but different VALUES");
			}
			feature = read_features(features_file, paillier_public_key, dgk_public_key, precision, label_encoder);
            logger.debug("Client Feature Vector\n{}", feature);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}

		int connection_port;
		long start_time = System.nanoTime();

		// If you are just evaluating directly with the server-site
		if (level_site_ips == null) {
			try(Socket server_site = createSocket(server_ip, server_port)) {
				logger.info("Client connected to sever-site with PPDT");
				evaluate_with_server_site(server_site);
				long end_time = System.nanoTime();
                logger.info("[Server] The Classification is: {}", classification);
				double run_time = (double) (end_time - start_time);
				run_time = run_time/1000000;
				logger.info(String.format("It took %f ms to classify\n", run_time));
				save_keys_and_encoder();
			}
			catch (HomomorphicException | IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
			return;
		}

		// However, if you are evaluating with level-sites, you are running this code.
		try {
			if (port == -1) {
				assert level_site_ports != null;
				connection_port = level_site_ports[0];
				// Level-Site 0 is listening to 9000 locally, so use 10,000
				feature.set_client_port(server_port);
			}
			else {
				connection_port = port;
				feature.set_client_port(connection_port);
			}
			feature.set_client_ip(client_ip);

			int level = 0;
			try(ServerSocket level_site_listener = createServerSocket(feature.get_client_port())) {
				// For level-site 0, just connect and evaluate now.
				try(Socket level_site = createSocket(level_site_ips[level], connection_port)) {
					evaluate_with_level_site(level_site, level);
				}

				// For every other level, the level-site will reach out to you
				while(!classification_complete) {
					long start_wait = System.nanoTime();
					logger.info("[Client] Client is now waiting for next level-site");
					++level;
					Socket level_site = level_site_listener.accept();
					long end_wait = System.nanoTime();
					double wait_time = (double) (end_wait - start_wait);
					wait_time = wait_time/1000000;
					logger.info(String.format("[Client] Next Level-site just got features and just connected back for encrypted integer comparison in %f ms", wait_time));
					evaluate_with_level_site(level_site, level);
				}
			}

            long end_time = System.nanoTime();
            logger.info("[Level-Site] The Classification is: {}", classification);
			double run_time = (double) (end_time - start_time);
			run_time = run_time/1000000;
            logger.info(String.format("It took %f ms to classify\n", run_time));
			save_keys_and_encoder();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void save_keys_and_encoder() throws IOException {
		// At the end, write your keys...
		dgk_public_key.writeKey("dgk.pub");
		paillier_public_key.writeKey("paillier.pub");
		dgk_private_key.writeKey("dgk");
		paillier_private_key.writeKey("paillier");

		try (ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream("label_encoder.bin"))) {
			outputStream.writeObject(label_encoder);
		}
	}

	/**
	 * Creates a socket connection to the specified hostname and port.
	 * For some reason, the moment I move this to shared.java, it just fails.
	 *
	 * @param hostname The hostname or IP address to connect to.
	 * @param port The port number to connect to.
	 * @return A `Socket` object representing the connection.
	 * @throws RuntimeException If the socket cannot be created due to an I/O error.
	 */
	public static Socket createSocket(String hostname, int port) {
		Socket client_socket;
		try {
			client_socket = new Socket(hostname, port);
			// client_socket = (SSLSocket) socket_factory.createSocket(hostname, port);
			// client_socket.setEnabledProtocols(protocols);
		}
		catch (IOException e) {
			throw new RuntimeException("Cannot open port " + port, e);
		}
		return client_socket;
	}

	/**
	 * Creates a server socket that listens on the specified port.
	 *
	 * @param serverPort The port number on which the server socket will listen to.
	 * @return A `ServerSocket` object representing the server socket.
	 * @throws RuntimeException If the server socket cannot be created due to an I/O error.
	 */
	public static ServerSocket createServerSocket(int serverPort) {
		ServerSocket serverSocket;
		try {
			serverSocket = new ServerSocket(serverPort);
			// serverSocket = (SSLServerSocket) factory.createServerSocket(serverPort);
			// serverSocket.setEnabledProtocols(protocols);
		}
		catch (IOException e) {
			throw new RuntimeException("Cannot open port " + serverPort, e);
		}
		return serverSocket;
	}
}
