/*
 * SessionManager.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2013 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */



package tigase.server.xmppsession;

//~--- non-JDK imports --------------------------------------------------------

import tigase.auth.mechanisms.SaslEXTERNAL;

import tigase.conf.Configurable;

import tigase.db.AuthorizationException;
import tigase.db.AuthRepository;
import tigase.db.NonAuthUserRepository;
import tigase.db.NonAuthUserRepositoryImpl;
import tigase.db.RepositoryFactory;
import tigase.db.TigaseDBException;
import tigase.db.UserRepository;

import tigase.disco.XMPPService;

import tigase.server.AbstractMessageReceiver;
import tigase.server.Command;
import tigase.server.Iq;
import tigase.server.Message;
import tigase.server.Packet;
import tigase.server.Permissions;
import tigase.server.ReceiverTimeoutHandler;
import tigase.server.script.CommandIfc;
import tigase.server.XMPPServer;

import tigase.stats.StatisticsList;

import tigase.sys.OnlineJidsReporter;
import tigase.sys.TigaseRuntime;

import tigase.util.ProcessingThreads;
import tigase.util.QueueItem;
import tigase.util.TigaseStringprepException;
import tigase.util.WorkerThread;

import tigase.vhosts.VHostItem;

import tigase.xml.Element;

import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.impl.PresenceCapabilitiesManager;
import tigase.xmpp.JID;
import tigase.xmpp.NoConnectionIdException;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPException;
import tigase.xmpp.XMPPImplIfc;
import tigase.xmpp.XMPPPacketFilterIfc;
import tigase.xmpp.XMPPPostprocessorIfc;
import tigase.xmpp.XMPPPreprocessorIfc;
import tigase.xmpp.XMPPPresenceUpdateProcessorIfc;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.XMPPSession;
import tigase.xmpp.XMPPStopListenerIfc;

import static tigase.server.xmppsession.SessionManagerConfig.*;

//~--- JDK imports ------------------------------------------------------------

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.script.Bindings;

/**
 * Class SessionManager
 *
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class SessionManager
				extends AbstractMessageReceiver
				implements Configurable, SessionManagerHandler, OnlineJidsReporter {
	/** Field description */
	protected static final String ADMIN_COMMAND_NODE = "http://jabber.org/protocol/admin";

	/**
	 * Variable <code>log</code> is a class logger.
	 */
	private static final Logger log = Logger.getLogger(SessionManager.class.getName());

	//~--- fields ---------------------------------------------------------------

	private AuthRepository                   auth_repository                 = null;
	private long                             authTimeouts                    = 0;
	private long                             closedConnections               = 0;
	private DefaultHandlerProc               defHandlerProc                  = null;
	private PacketDefaultHandler             defPacketHandler                = null;
	private String                           defPluginsThreadsPool = "default-threads-pool";
	private boolean                          forceDetailStaleConnectionCheck = true;
	private int                              maxIdx                          = 100;
	private int                              maxUserConnections              = 0;
	private int                              maxUserSessions                 = 0;
	private NonAuthUserRepository            naUserRepository                = null;
	private SessionCloseProc                 sessionCloseProc                = null;
	private SessionOpenProc                  sessionOpenProc                 = null;
	private SMResourceConnection             smResourceConnection            = null;
	private int                              tIdx                            = 0;
	private long                             totalUserConnections            = 0;
	private long                             totalUserSessions               = 0;
	private UserRepository                   user_repository                 = null;
	private Set<String>                      trusted = new ConcurrentSkipListSet<String>();
	private Map<String, XMPPStopListenerIfc> stopListeners = new ConcurrentHashMap<String,
			XMPPStopListenerIfc>(10);
	private boolean          skipPrivacy = false;
	private Set<XMPPImplIfc> allPlugins  = new ConcurrentSkipListSet<XMPPImplIfc>();

	/**
	 * A Map with bare user JID as a key and a user session object as a value.
	 */
	protected ConcurrentHashMap<BareJID, XMPPSession> sessionsByNodeId =
			new ConcurrentHashMap<BareJID, XMPPSession>(100000);
	private Map<String, ProcessingThreads<ProcessorWorkerThread>> workerThreads =
			new ConcurrentHashMap<String, ProcessingThreads<ProcessorWorkerThread>>(32);
	private StaleConnectionCloser         staleConnectionCloser =
			new StaleConnectionCloser();
	private Map<String, XMPPProcessorIfc> processors = new ConcurrentHashMap<String,
			XMPPProcessorIfc>(32);
	private Map<String, XMPPPresenceUpdateProcessorIfc> presenceProcessors =
			new ConcurrentHashMap<String, XMPPPresenceUpdateProcessorIfc>();
	private Map<String, XMPPPreprocessorIfc> preProcessors = new ConcurrentHashMap<String,
			XMPPPreprocessorIfc>(10);

	// private long[] defPrepTime = new long[maxIdx];
	// private long[] prepTime = new long[maxIdx];
	// private long[] defForwTime = new long[maxIdx];
	// private long[] walkTime = new long[maxIdx];
	// private long[] postTime = new long[maxIdx];
	private Map<String, long[]>               postTimes = new ConcurrentSkipListMap<String,
			long[]>();
	private Map<String, XMPPPostprocessorIfc> postProcessors =
			new ConcurrentHashMap<String, XMPPPostprocessorIfc>(10);
	private Map<String, Map<String, Object>> plugin_config = new ConcurrentHashMap<String,
			Map<String, Object>>(20);
	private Map<String, XMPPPacketFilterIfc> outFilters = new ConcurrentHashMap<String,
			XMPPPacketFilterIfc>(10);
	private ConnectionCheckCommandHandler connectionCheckCommandHandler =
			new ConnectionCheckCommandHandler();

	/** Field description */
	protected Queue<Packet> packetWriterQueue = new WriterQueue<Packet>();

	/**
	 * A Map with connectionID as a key and an object with all the user connection
	 * data as a value
	 */
	protected ConcurrentHashMap<JID, XMPPResourceConnection> connectionsByFrom =
			new ConcurrentHashMap<JID, XMPPResourceConnection>(100000);

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean addOutPacket(Packet packet) {

		// We actually have to set packetFrom address to the session manager ID
		// to make sure the connection manager for instance can report problems back
		// This cause other problems with packets processing which have to be
		// resolved anyway
		if (packet.getPacketFrom() == null) {
			packet.setPacketFrom(getComponentId());
		}

		return super.addOutPacket(packet);
	}

	/**
	 * Method description
	 *
	 *
	 * @param plug_id
	 * @param conc
	 *
	 *
	 *
	 *
	 * @return a value of <code>XMPPImplIfc</code>
	 * @throws ClassNotFoundException
	 * @throws IllegalAccessException
	 * @throws InstantiationException
	 */
	public XMPPImplIfc addPlugin(String plug_id, Integer conc)
					throws ClassNotFoundException, InstantiationException, IllegalAccessException {
		XMPPImplIfc      result = null;
		XMPPProcessorIfc proc   = null;
		String           version;

		if (plug_id.equals(sessionOpenProcId)) {
			sessionOpenProc = new SessionOpenProc();
			proc            = sessionOpenProc;
		}
		if (plug_id.equals(sessionCloseProcId)) {
			sessionCloseProc = new SessionCloseProc();
			proc             = sessionCloseProc;
		}
		if (plug_id.equals(defaultHandlerProcId)) {
			defHandlerProc = new DefaultHandlerProc();
			proc           = defHandlerProc;
		}
		if (proc == null) {
			proc = SessionManagerConfig.getProcessor(plug_id);
		}

		boolean loaded = false;

		if (proc != null) {
			int concurrency = ((conc != null)
					? conc
					: ((proc != null)
					? proc.concurrentQueuesNo()
					: 0));

			// If there is not default processors thread pool or the processor does
			// have thread pool specific settings create a separate thread pool
			// for the processor
			if ((workerThreads.get(defPluginsThreadsPool) == null) || (conc != null)) {

				// Added to make sure that there will be only one thread pool for plugin
				// so if one exits we will keep it and not create another one
				if (!workerThreads.containsKey(proc.id())) {
					ProcessorWorkerThread                    worker = new ProcessorWorkerThread();
					ProcessingThreads<ProcessorWorkerThread> pt =
							new ProcessingThreads<ProcessorWorkerThread>(worker, concurrency,
							maxInQueueSize, proc.id());

					workerThreads.put(proc.id(), pt);
					log.log(Level.CONFIG,
							"Created thread pool: {0}, queue: {1} for plugin id: {2}", new Object[] {
							concurrency,
							maxInQueueSize, proc.id() });
				}
			}
			processors.put(proc.id(), proc);
			log.log(Level.CONFIG, "Added processor: {0} for plugin id: {1}", new Object[] { proc
					.getClass().getSimpleName(),
					proc.id() });
			loaded  = true;
			result  = proc;
			version = result.getComponentInfo().getComponentVersion();
			System.out.println("Loading plugin: " + plug_id + "=" + concurrency + " ... " +
					(version.isEmpty()
					? ""
					: "\t, version: " + version));
		}

		XMPPPreprocessorIfc preproc = SessionManagerConfig.getPreprocessor(plug_id);

		if (preproc != null) {
			preProcessors.put(plug_id, preproc);
			log.log(Level.CONFIG, "Added preprocessor: {0} for plugin id: {1}", new Object[] {
					preproc.getClass().getSimpleName(),
					plug_id });
			loaded = true;
			result = preproc;
		}

		XMPPPostprocessorIfc postproc = SessionManagerConfig.getPostprocessor(plug_id);

		if (postproc != null) {
			postProcessors.put(plug_id, postproc);
			log.log(Level.CONFIG, "Added postprocessor: {0} for plugin id: {1}", new Object[] {
					postproc.getClass().getSimpleName(),
					plug_id });
			loaded = true;
			result = postproc;
		}

		XMPPStopListenerIfc stoplist = SessionManagerConfig.getStopListener(plug_id);

		if (stoplist != null) {
			stopListeners.put(plug_id, stoplist);
			log.log(Level.CONFIG, "Added stopped processor: {0} for plugin id: {1}",
					new Object[] { stoplist.getClass().getSimpleName(),
					plug_id });
			loaded = true;
			result = stoplist;
		}

		XMPPPacketFilterIfc filterproc = SessionManagerConfig.getPacketFilter(plug_id);

		if (filterproc != null) {
			outFilters.put(plug_id, filterproc);
			log.log(Level.CONFIG, "Added packet filter: {0} for plugin id: {1}", new Object[] {
					filterproc.getClass().getSimpleName(),
					plug_id });
			loaded = true;
			result = filterproc;
		}
		if (!loaded) {
			log.log(Level.WARNING, "No implementation found for plugin id: {0}", plug_id);
		}    // end of if (!loaded)
		if (result != null) {
			allPlugins.add(result);
			if (result instanceof PresenceCapabilitiesManager.PresenceCapabilitiesListener) {
				PresenceCapabilitiesManager.registerPresenceHandler((PresenceCapabilitiesManager
						.PresenceCapabilitiesListener) result);
			}
			if (result instanceof XMPPPresenceUpdateProcessorIfc) {
				presenceProcessors.put(result.id(), (XMPPPresenceUpdateProcessorIfc) result);
			}
		}

		return result;
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean containsJid(BareJID jid) {
		return sessionsByNodeId.containsKey(jid);
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param conn
	 */
	public void handleLocalPacket(Packet packet, XMPPResourceConnection conn) {

		// Do nothing here. Maybe we will attach some handlers later
	}

	/**
	 * Method description
	 *
	 *
	 * @param userId
	 * @param conn
	 */
	@Override
	public void handleLogin(BareJID userId, XMPPResourceConnection conn) {
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "handleLogin called for: {0}, conn_id: {1}", new Object[] {
					userId,
					conn });
		}
		registerNewSession(userId, conn);
	}

	/**
	 * Method description
	 *
	 *
	 * @param userId
	 * @param conn
	 */
	@Override
	public void handleLogout(BareJID userId, XMPPResourceConnection conn) {
		XMPPSession session = sessionsByNodeId.get(userId);

		if ((session != null) && (session.getActiveResourcesSize() <= 1)) {
			sessionsByNodeId.remove(userId);
		}    // end of if (session.getActiveResourcesSize() == 0)
		try {
			connectionsByFrom.remove(conn.getConnectionId());

			Packet cmd = Command.CLOSE.getPacket(getComponentId(), conn.getConnectionId(),
					StanzaType.set, conn.nextStanzaId());
			String error = (String) conn.getSessionData(XMPPResourceConnection.ERROR_KEY);

			if (error != null) {
				Element err_el = new Element(error);

				err_el.setXMLNS("urn:ietf:params:xml:ns:xmpp-streams");
				cmd.getElement().getChild("command").addChild(err_el);
			}
			fastAddOutPacket(cmd);
		} catch (NoConnectionIdException ex) {
			log.log(Level.WARNING, "Connection ID not set for session: {0}", conn);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param conn
	 */
	@Override
	public void handlePresenceSet(XMPPResourceConnection conn) {
		XMPPSession parentSession = conn.getParentSession();

		if (parentSession == null) {
			return;
		}

		Element presence = conn.getPresence();

		this.processPresenceUpdate(parentSession, presence);
	}

	/**
	 * Method description
	 *
	 *
	 * @param conn
	 */
	@Override
	public void handleResourceBind(XMPPResourceConnection conn) {
		if (!conn.isServerSession() && (!"USER_STATUS".equals(conn.getSessionId())) &&!conn
				.isTmpSession()) {
			try {
				Packet user_login_cmd = Command.USER_LOGIN.getPacket(getComponentId(), conn
						.getConnectionId(), StanzaType.set, conn.nextStanzaId(), Command.DataType
						.submit);

				Command.addFieldValue(user_login_cmd, "user-jid", conn.getjid().toString());
				addOutPacket(user_login_cmd);
			} catch (NoConnectionIdException ex) {

				// This actually should not happen... might be a bug:
				log.log(Level.WARNING, "This should not happen, check it out!, ", ex);
			}
		}
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean handlesLocalDomains() {
		return true;
	}

	/**
	 * Initialize a mapping of key/value pairs which can be used in scripts
	 * loaded by the server
	 *
	 * @param binds A mapping of key/value pairs, all of whose keys are Strings.
	 */
	@Override
	public void initBindings(Bindings binds) {
		super.initBindings(binds);
		binds.put(CommandIfc.AUTH_REPO, auth_repository);
		binds.put(CommandIfc.USER_CONN, connectionsByFrom);
		binds.put(CommandIfc.USER_REPO, user_repository);
		binds.put(CommandIfc.USER_SESS, sessionsByNodeId);
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>int</code>
	 */
	@Override
	public int processingInThreads() {
		return Runtime.getRuntime().availableProcessors() * 8;
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>int</code>
	 */
	@Override
	public int processingOutThreads() {
		return Runtime.getRuntime().availableProcessors() * 8;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 */
	@Override
	public void processPacket(final Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Received packet: {0}", packet.toStringSecure());
		}
		if (packet.isCommand() && processCommand(packet)) {
			packet.processedBy("SessionManager");

			// No more processing is needed for command packet
			return;
		}    // end of if (pc.isCommand())

		XMPPResourceConnection conn = getXMPPResourceConnection(packet);

		if ((conn == null) && (isBrokenPacket(packet)) || processAdminsOrDomains(packet)) {
			return;
		}
		processPacket(packet, conn);
	}

	/**
	 * Method description
	 *
	 *
	 * @param plug_id
	 */
	public void removePlugin(String plug_id) {
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Removing plugin {0}", plug_id);
		}

		XMPPImplIfc                              p  = null;
		ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.remove(plug_id);

		if (pt != null) {
			p = processors.remove(plug_id);
			pt.shutdown();
			if (p != null) {
				allPlugins.remove(p);
			}
		}
		if (preProcessors.get(plug_id) != null) {
			p = preProcessors.remove(plug_id);
			allPlugins.remove(p);
		}
		if (postProcessors.get(plug_id) != null) {
			p = postProcessors.remove(plug_id);
			allPlugins.remove(p);
		}
		if (stopListeners.get(plug_id) != null) {
			p = stopListeners.remove(plug_id);
			allPlugins.remove(p);
		}
		if (p != null) {
			if (p instanceof PresenceCapabilitiesManager.PresenceCapabilitiesListener) {
				PresenceCapabilitiesManager.unregisterPresenceHandler((PresenceCapabilitiesManager
						.PresenceCapabilitiesListener) p);
			}
			if (p instanceof XMPPPresenceUpdateProcessorIfc) {
				presenceProcessors.remove(plug_id);
			}
		}
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	public boolean skipPrivacy() {
		return skipPrivacy;
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>JID[]</code>
	 */
	@Override
	public JID[] getConnectionIdsForJid(BareJID jid) {
		if (skipPrivacy()) {
			XMPPSession session = sessionsByNodeId.get(jid);

			if (session != null) {
				return session.getConnectionIds();
			}
		}

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param params
	 *
	 *
	 *
	 * @return a value of <code>Map<String,Object></code>
	 */
	@Override
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		Map<String, Object> props = super.getDefaults(params);

		SessionManagerConfig.getDefaults(props, params);
		props.put(FORCE_DETAIL_STALE_CONNECTION_CHECK, true);
		props.put(STALE_CONNECTION_CLOSER_QUEUE_SIZE_KEY, StaleConnectionCloser
				.DEF_QUEUE_SIZE);

		return props;
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>String</code>
	 */
	@Override
	public String getDiscoCategoryType() {
		return "sm";
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>String</code>
	 */
	@Override
	public String getDiscoDescription() {
		return "Session manager";
	}

	/**
	 * Method description
	 *
	 *
	 * @param from
	 *
	 *
	 *
	 * @return a value of <code>List<Element></code>
	 */
	@Override
	public List<Element> getDiscoFeatures(JID from) {
		List<Element> features = new LinkedList<Element>();
		List<Element> tmp      = super.getDiscoFeatures(from);

		if (tmp != null) {
			features.addAll(tmp);
		}
		for (XMPPProcessorIfc proc_t : processors.values()) {
			Element[] discoFeatures = proc_t.supDiscoFeatures(null);

			if (discoFeatures != null) {
				features.addAll(Arrays.asList(discoFeatures));
			}    // end of if (discoFeatures != null)
		}

		return features;
	}

	/**
	 * Method description
	 *
	 *
	 * @param node
	 * @param jid
	 * @param from
	 *
	 *
	 *
	 * @return a value of <code>Element</code>
	 */
	@Override
	public Element getDiscoInfo(String node, JID jid, JID from) {
		if ((jid != null) && (getName().equals(jid.getLocalpart()) || isLocalDomain(jid
				.toString()))) {
			Element query = super.getDiscoInfo(node, jid, from);

			if (query == null) {
				query = new Element("query");
				query.setXMLNS(XMPPService.INFO_XMLNS);
			}
			if (node == null) {
				for (XMPPProcessorIfc proc_t : processors.values()) {
					Element[] discoFeatures = proc_t.supDiscoFeatures(null);

					if (discoFeatures != null) {
						query.addChildren(Arrays.asList(discoFeatures));
					}    // end of if (discoFeatures != null)
				}
			}
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Found disco info: {0}", ((query != null)
						? query.toString()
						: null));
			}

			return query;
		}
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Not found disco info for node: {0}, jid: {1}",
					new Object[] { node,
					jid });
		}

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>XMPPResourceConnection</code>
	 */
	public XMPPResourceConnection getResourceConnection(JID jid) {
		XMPPSession session = getSession(jid.getBareJID());

		if (session != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Session not null, searching session for jid: {0}", jid);
			}

			XMPPResourceConnection res = session.getResourceConnection(jid);

			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Found session: {0}, for jid: {1}", new Object[] { res,
						jid });
			}

			return res;
		}    // end of if (session != null)

		// Maybe this is a call for the server session?
		if (isLocalDomain(jid.toString(), false)) {
			return smResourceConnection;
		}

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param list
	 */
	@Override
	@SuppressWarnings("unchecked")
	public void getStatistics(StatisticsList list) {
		super.getStatistics(list);
		if (list.checkLevel(Level.FINEST)) {
			list.add(getName(), "Registered accounts", user_repository.getUsersCount(), Level
					.FINEST);
		}
		list.add(getName(), "Open user connections", connectionsByFrom.size(), Level.INFO);
		list.add(getName(), "Maximum user connections", maxUserConnections, Level.INFO);
		list.add(getName(), "Total user connections", totalUserConnections, Level.FINER);
		list.add(getName(), "Closed user connections", closedConnections, Level.FINER);
		list.add(getName(), "Open user sessions", sessionsByNodeId.size(), Level.INFO);
		list.add(getName(), "Maximum user sessions", maxUserSessions, Level.FINE);
		list.add(getName(), "Total user sessions", totalUserSessions, Level.FINER);
		list.add(getName(), "Active user connections", getActiveUserNumber(), Level.FINER);
		list.add(getName(), "Authentication timouts", authTimeouts, Level.INFO);
		if (list.checkLevel(Level.INFO)) {
			int  totalQueuesWait     = list.getValue(getName(), "Total queues wait", 0);
			long totalQueuesOverflow = list.getValue(getName(), "Total queues overflow", 0l);

			for (Map.Entry<String, ProcessingThreads<ProcessorWorkerThread>> procent :
					workerThreads.entrySet()) {
				ProcessingThreads<ProcessorWorkerThread> proc = procent.getValue();

				totalQueuesWait     += proc.getTotalQueueSize();
				totalQueuesOverflow += proc.getDroppedPackets();
				if (list.checkLevel(Level.INFO, proc.getTotalQueueSize() + proc
						.getDroppedPackets())) {
					list.add(getName(), "Processor: " + procent.getKey(), ", Queue: " + proc
							.getTotalQueueSize() + ", AvTime: " + proc.getAverageProcessingTime() +
							", Runs: " + proc.getTotalRuns() + ", Lost: " + proc.getDroppedPackets(),
							Level.INFO);
				}
			}
			list.add(getName(), "Total queues wait", totalQueuesWait, Level.INFO);
			list.add(getName(), "Total queues overflow", totalQueuesOverflow, Level.INFO);
		}
		if (list.checkLevel(Level.FINE)) {
			for (Map.Entry tmEntry : postTimes.entrySet()) {

				// This line is only temporary because JIndent cannot parse it inside the above
				// for statement
				Map.Entry<String, long[]> entry = tmEntry;

				list.add(getName(), "Average " + tmEntry.getKey() + " on last " + entry.getValue()
						.length + " runs [ms]", calcAverage(entry.getValue()), Level.FINE);
			}
		}
		for (XMPPImplIfc plugin : allPlugins) {
			plugin.getStatistics(list);
		}
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean hasCompleteJidsInfo() {
		return true;
	}

	/**
	 * Method description
	 *
	 *
	 * @param domain
	 * @param includeComponents
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	@Override
	public boolean isLocalDomain(String domain, boolean includeComponents) {
		if (includeComponents) {
			return isLocalDomainOrComponent(domain);
		} else {
			return isLocalDomain(domain);
		}
	}

	//~--- set methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param name
	 */
	@Override
	public void setName(String name) {
		super.setName(name);
		TigaseRuntime.getTigaseRuntime().addOnlineJidsReporter(this);
	}

	/**
	 * Method description
	 *
	 *
	 * @param props
	 */
	@Override
	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);
		if (props.get(SKIP_PRIVACY_PROP_KEY) != null) {
			skipPrivacy = (Boolean) props.get(SKIP_PRIVACY_PROP_KEY);
		}
		if (props.get(TRUSTED_PROP_KEY) != null) {
			String[] trusted_tmp = (String[]) props.get(TRUSTED_PROP_KEY);

			if (trusted_tmp != null) {
				for (String trust : trusted_tmp) {
					trusted.add(trust);
				}
			}
		}
		if (props.get(FORCE_DETAIL_STALE_CONNECTION_CHECK) != null) {
			forceDetailStaleConnectionCheck = (Boolean) props.get(
					FORCE_DETAIL_STALE_CONNECTION_CHECK);
			log.log(Level.CONFIG, "forced detailed stale connection checking is set to = {0}",
					forceDetailStaleConnectionCheck);
		}
		if (props.get(STALE_CONNECTION_CLOSER_QUEUE_SIZE_KEY) != null) {
			staleConnectionCloser.setMaxQueueSize((Integer) props.get(
					STALE_CONNECTION_CLOSER_QUEUE_SIZE_KEY));
			log.log(Level.CONFIG, "stale connection closer queue is set to = {0}",
					staleConnectionCloser.getMaxQueueSize());
		}
		if (!staleConnectionCloser.isScheduled()) {
			addTimerTask(staleConnectionCloser, staleConnectionCloser.getTimeout());
		}
		if (props.size() == 1) {

			// If props.size() == 1, it means this is a single property update
			// and this component does not support single property change for the rest
			// of it's settings
			return;
		}
		defPacketHandler = new PacketDefaultHandler();

		// Is there shared user repository instance? If so I want to use it:
		user_repository = (UserRepository) props.get(RepositoryFactory
				.SHARED_USER_REPO_PROP_KEY);
		if (user_repository != null) {
			log.log(Level.CONFIG, "Using shared repository instance: {0}", user_repository
					.getClass().getName());
		} else {
			Map<String, String> user_repo_params = new LinkedHashMap<String, String>(10);

			for (Map.Entry<String, Object> entry : props.entrySet()) {
				if (entry.getKey().startsWith(RepositoryFactory.USER_REPO_PARAMS_NODE)) {

					// Split the key to configuration nodes separated with '/'
					String[] nodes = entry.getKey().split("/");

					// The plugin ID part may contain many IDs separated with comma ','
					if (nodes.length > 1) {
						user_repo_params.put(nodes[1], entry.getValue().toString());
					}
				}
			}
			try {

				// String cls_name = (String) props.get(USER_REPO_CLASS_PROP_KEY);
				String res_uri = (String) props.get(RepositoryFactory.USER_REPO_URL_PROP_KEY);

				user_repository = RepositoryFactory.getUserRepository(null, res_uri,
						user_repo_params);
				log.log(Level.CONFIG, "Initialized {0} as user repository: {1}", new Object[] {
						null,
						res_uri });
			} catch (Exception e) {
				log.log(Level.SEVERE, "Can't initialize user repository: ", e);
			}    // end of try-catch
		}
		auth_repository = (AuthRepository) props.get(RepositoryFactory
				.SHARED_AUTH_REPO_PROP_KEY);
		if (auth_repository != null) {
			log.log(Level.CONFIG, "Using shared auth repository instance: {0}", auth_repository
					.getClass().getName());
		} else {
			Map<String, String> auth_repo_params = new LinkedHashMap<String, String>(10);

			for (Map.Entry<String, Object> entry : props.entrySet()) {
				if (entry.getKey().startsWith(RepositoryFactory.AUTH_REPO_PARAMS_NODE)) {

					// Split the key to configuration nodes separated with '/'
					String[] nodes = entry.getKey().split("/");

					// The plugin ID part may contain many IDs separated with comma ','
					if (nodes.length > 1) {
						auth_repo_params.put(nodes[1], entry.getValue().toString());
					}
				}
			}
			try {
				String res_uri = (String) props.get(RepositoryFactory.AUTH_REPO_URL_PROP_KEY);

				auth_repository = RepositoryFactory.getAuthRepository(null, res_uri,
						auth_repo_params);
				log.log(Level.CONFIG, "Initialized {0} as auth repository: {1}", new Object[] {
						null,
						res_uri });
			} catch (Exception e) {
				log.log(Level.SEVERE, "Can't initialize auth repository: ", e);
			}    // end of try-catch
		}
		naUserRepository = new NonAuthUserRepositoryImpl(user_repository, getDefHostName(),
				Boolean.parseBoolean((String) props.get(AUTO_CREATE_OFFLINE_USER_PROP_KEY)));
		synchronized (this) {
			LinkedHashMap<String, Integer> plugins_concurrency = new LinkedHashMap<String,
					Integer>(20);
			String[] plugins_conc = ((String) props.get(PLUGINS_CONCURRENCY_PROP_KEY)).split(
					",");

			log.log(Level.CONFIG, "Loading concurrency plugins list: {0}", Arrays.toString(
					plugins_conc));
			if ((plugins_conc != null) && (plugins_conc.length > 0)) {
				for (String plugc : plugins_conc) {
					log.log(Level.CONFIG, "Loading: {0}", plugc);
					if (!plugc.trim().isEmpty()) {
						String[] pc = plugc.split("=");

						try {
							int conc = Integer.parseInt(pc[1]);

							plugins_concurrency.put(pc[0], conc);
							log.log(Level.CONFIG, "Concurrency for plugin: {0} set to: {1}",
									new Object[] { pc[0],
									conc });
						} catch (Exception e) {
							log.log(Level.WARNING, "Plugin concurrency parsing error for: " + plugc +
									", ", e);
						}
					}
				}
			}

			Set<String> keys = new HashSet<String>(processors.keySet());

			try {
				if (!isInitializationComplete()) {
					String sm_threads_pool = (String) props.get(SM_THREADS_POOL_PROP_KEY);

					if (!sm_threads_pool.equals(SM_THREADS_POOL_PROP_VAL)) {
						String[] threads_pool_params = sm_threads_pool.split(":");
						int      def_pool_size       = 100;

						if (threads_pool_params.length > 1) {
							try {
								def_pool_size = Integer.parseInt(threads_pool_params[1]);
							} catch (Exception e) {
								log.log(Level.WARNING,
										"Incorrect threads pool size: {0}, setting default to 100",
										threads_pool_params[1]);
								def_pool_size = 100;
							}
						}

						ProcessorWorkerThread                    worker = new ProcessorWorkerThread();
						ProcessingThreads<ProcessorWorkerThread> pt =
								new ProcessingThreads<ProcessorWorkerThread>(worker, def_pool_size,
								maxInQueueSize, defPluginsThreadsPool);

						workerThreads.put(defPluginsThreadsPool, pt);
						log.log(Level.CONFIG, "Created a default thread pool: {0}", def_pool_size);
					}
				}

				String[] plugins = SessionManagerConfig.getActivePlugins(props);

				log.log(Level.CONFIG, "Loaded plugins list: {0}", Arrays.toString(plugins));

				// maxPluginsNo = plugins.length;
				// processors.clear();
				for (String plug_id : plugins) {
					keys.remove(plug_id);
					log.log(Level.CONFIG, "Loading and configuring plugin: {0}", plug_id);

					XMPPImplIfc plugin = addPlugin(plug_id, plugins_concurrency.get(plug_id));

					if (plugin != null) {
						Map<String, Object> plugin_settings = getPluginSettings(plug_id, props);

						if (plugin_settings.size() > 0) {
							if (log.isLoggable(Level.CONFIG)) {
								log.log(Level.CONFIG, "Plugin configuration: {0}", plugin_settings);
							}
							plugin_config.put(plug_id, plugin_settings);
						}
						try {
							plugin.init(plugin_settings);
						} catch (TigaseDBException ex) {
							log.log(Level.SEVERE, "Problem initializing plugin: " + plugin.id(), ex);
						}
					}
				}    // end of for (String comp_id: plugins)
			} catch (Exception e) {
				log.log(Level.SEVERE, "Problem with component initialization: " + getName(), e);
			}
			for (String key : keys) {
				removePlugin(key);
			}
		}
		if (!isInitializationComplete()) {
			smResourceConnection = new SMResourceConnection(null, user_repository,
					auth_repository, this);
			registerNewSession(getComponentId().getBareJID(), smResourceConnection);
		}
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param conn
	 * @param results
	 */
	protected void addOutPackets(Packet packet, XMPPResourceConnection conn,
			Queue<Packet> results) {
		for (XMPPPacketFilterIfc outfilter : outFilters.values()) {
			outfilter.filter(packet, conn, naUserRepository, results);
		}    // end of for (XMPPPostprocessorIfc postproc: postProcessors)
		addOutPackets(results);
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean addTrusted(JID jid) {
		return trusted.add(jid.getBareJID().toString());
	}

	/**
	 * Method description
	 *
	 *
	 * @param connectionId
	 * @param userId
	 * @param closeOnly
	 */
	protected void closeConnection(JID connectionId, String userId, boolean closeOnly) {
		if (log.isLoggable(Level.FINER)) {
			log.log(Level.FINER, "Stream closed from: {0}", connectionId);
		}

		// for test let's assume connection is not found
		XMPPResourceConnection connection = connectionsByFrom.remove(connectionId);

		if (connection != null) {

			// Make sure no other stuff happen on the connection while it is being
			// closed. The best example is handleLogin, it happens they are called
			// concurrently and this is where things go wrong....
			synchronized (connection) {
				connection.putSessionData(XMPPResourceConnection.CLOSING_KEY,
						XMPPResourceConnection.CLOSING_KEY);
				closeSession(connection, closeOnly);
			}
		} else {
			if (log.isLoggable(Level.FINE)) {
				log.log(Level.FINE, "Can not find resource connection for connectionId: {0}",
						connectionId);
			}
			if (userId != null) {

				// check using userId if we can find stale XMPPResourceConnection
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST,
							"Found trying to find stale XMPPResourceConnection by userId {0}...",
							userId);
				}

				JID         userJid         = JID.jidInstanceNS(userId);
				XMPPSession sessionByUserId = sessionsByNodeId.get(userJid.getBareJID());

				if (sessionByUserId != null) {
					connection = sessionByUserId.getResourceForConnectionId(connectionId);
					if (connection != null) {
						if (log.isLoggable(Level.FINEST)) {
							log.log(Level.FINEST,
									"Found stale XMPPResourceConnection {0} by userId {1}, removing...",
									new Object[] { connection,
									userId });
						}
						sessionByUserId.removeResourceConnection(connection);
					}
				}

				return;
			}

			// Maybe we should move this loop based check to separe thread for performance reason
			// Check if our Set<JID> of not found sessions contains each of available connections from each session
			if (!forceDetailStaleConnectionCheck) {
				return;
			}

			// Let's make sure there is no stale XMPPResourceConnection in some
			// XMPPSession
			// object which may cause problems and packets sent to nowhere.
			// This might an expensive operation though.... add item to queue
			// executed in other thread
			staleConnectionCloser.queueForClose(connectionId);

			// code below is original loop for finding stale XMPPResourceConnections
//    log.log(Level.INFO, "Trying to find and remove stale XMPPResourceConnection: {0}",
//        connectionId);
//
//    for (XMPPSession session : sessionsByNodeId.values()) {
//      connection = session.getResourceForConnectionId(connectionId);
//      if (connection != null) {
//        log.log(Level.WARNING, "Found stale XMPPResourceConnection: {0}, removing...",
//            connection);
//        session.removeResourceConnection(connection);
//
//        break;
//      }
//    }
		}    // end of if (conn != null) else
	}

	/**
	 * Method description
	 *
	 *
	 * @param conn
	 * @param closeOnly
	 */
	protected void closeSession(XMPPResourceConnection conn, boolean closeOnly) {
		if (!closeOnly) {
			Queue<Packet> results = new ArrayDeque<Packet>(50);

			for (XMPPStopListenerIfc stopProc : stopListeners.values()) {
				stopProc.stopped(conn, results, plugin_config.get(stopProc.id()));
			}    // end of for ()
			addOutPackets(null, conn, results);
		}
		try {
			if (conn.isAuthorized()) {
				JID userJid = conn.getJID();

				if (log.isLoggable(Level.FINE)) {
					log.log(Level.FINE, "Closing connection for: {0}", userJid);
				}

				XMPPSession session = conn.getParentSession();

				if (session != null) {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, "Found parent session for: {0}", userJid);
					}
					if (session.getActiveResourcesSize() <= 1) {

						// we should check if other this is the only connection on session
						// as in some cases connection can be already removed from active
						// resources but there might be other connection which is active
						if ((session.getActiveResourcesSize() > 0) &&!session.getActiveResources()
								.contains(conn)) {
							log.log(Level.FINE, "Session contains connection for other " +
									"resource than: {0}, not removing session", userJid);
							if (log.isLoggable(Level.FINER)) {
								StringBuilder sb = new StringBuilder(100);

								for (XMPPResourceConnection res_con : session.getActiveResources()) {
									sb.append(", res=").append(res_con.getResource());
								}
								log.log(Level.FINER, "Number of connections is {0} for the user: {1}{2}",
										new Object[] { session.getActiveResourcesSize(),
										userJid, sb.toString() });
							}

							return;
						}
						session = sessionsByNodeId.remove(userJid.getBareJID());
						if (session == null) {
							log.log(Level.INFO, "UPS can't remove, session not found in map: {0}",
									userJid);
						} else {
							if (log.isLoggable(Level.FINER)) {
								log.log(Level.FINER, "Number of user sessions: {0}", sessionsByNodeId
										.size());
							}
						}    // end of else
						auth_repository.logout(userJid.getBareJID());
					} else {
						if (log.isLoggable(Level.FINER)) {
							StringBuilder sb = new StringBuilder(100);

							for (XMPPResourceConnection res_con : session.getActiveResources()) {
								sb.append(", res=").append(res_con.getResource());
							}
							log.log(Level.FINER, "Number of connections is {0} for the user: {1}{2}",
									new Object[] { session.getActiveResourcesSize(),
									userJid, sb.toString() });
						}
					}    // end of else
				}      // end of if (session.getActiveResourcesSize() == 0)
			}
		} catch (NotAuthorizedException e) {
			log.log(Level.INFO, "Closed not authorized session: {0}", e);
		} catch (Exception e) {
			log.log(Level.WARNING, "Exception closing session... ", e);
		}
		++closedConnections;
		conn.streamClosed();
	}

	/**
	 * Method description
	 *
	 *
	 * @param conn_id
	 * @param domain
	 *
	 *
	 *
	 *
	 * @return a value of <code>XMPPResourceConnection</code>
	 * @throws TigaseStringprepException
	 */
	protected XMPPResourceConnection createUserSession(JID conn_id, String domain)
					throws TigaseStringprepException {
		XMPPResourceConnection connection = new XMPPResourceConnection(conn_id,
				user_repository, auth_repository, this);
		VHostItem vitem = null;

		if (domain != null) {
			vitem = getVHostItem(domain);
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Setting hostname {0} for connection: {1}, VHostItem: {2}",
						new Object[] { domain,
						conn_id, vitem });
			}
		}
		if (vitem == null) {

			// This shouldn't generally happen. Must mean misconfiguration.
			if (log.isLoggable(Level.INFO)) {
				log.log(Level.INFO,
						"Can't get VHostItem for domain: {0}, using default one instead: {1}",
						new Object[] { domain,
						getDefHostName() });
			}
			vitem = new VHostItem(getDefHostName().getDomain());
		}
		connection.setDomain(vitem.getUnmodifiableVHostItem());
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Domain set for connectionId {0}", conn_id);
		}
		connectionsByFrom.put(conn_id, connection);

		int currSize = connectionsByFrom.size();

		if (currSize > maxUserConnections) {
			maxUserConnections = currSize;
		}
		++totalUserConnections;

		return connection;
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean delTrusted(JID jid) {
		return trusted.remove(jid.getBareJID().toString());
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean fastAddOutPacket(Packet packet) {
		return addOutPacket(packet);
	}

	/**
	 * Method description
	 *
	 *
	 * @param conn_id
	 * @param domain
	 * @param user_id
	 * @param resource
	 * @param xmpp_sessionId
	 * @param tmpSession is a <code>boolean</code>
	 *
	 *
	 *
	 * @return a value of <code>XMPPResourceConnection</code>
	 */
	@SuppressWarnings("deprecation")
	protected XMPPResourceConnection loginUserSession(JID conn_id, String domain,
			BareJID user_id, String resource, String xmpp_sessionId, boolean tmpSession) {
		try {
			XMPPResourceConnection conn = createUserSession(conn_id, domain);

			conn.setTmpSession(tmpSession);
			conn.setSessionId(xmpp_sessionId);

			// user_repository.setData(user_id, "tokens", xmpp_sessionId, conn_id.toString());
			// Authorization auth = conn.loginToken(user_id, xmpp_sessionId, conn_id.toString());
			conn.authorizeJID(user_id, false);
			if (conn.isAuthorized()) {
				handleLogin(user_id, conn);
				if (resource != null) {
					conn.setResource(resource);
				}
			} else {
				connectionsByFrom.remove(conn_id);

				return null;
			}

			return conn;
		} catch (TigaseStringprepException | NotAuthorizedException ex) {
			log.log(Level.WARNING, "Problem logging user: " + user_id + "/" + resource, ex);
		}

		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean processAdminsOrDomains(Packet packet) {
		if ((packet.getStanzaFrom() == null) && (packet.getPacketFrom() != null)) {

			// The packet, probably did not went through the first state of processing
			// yet.
			return false;
		}

		JID to = packet.getStanzaTo();

		if ((to != null) && isLocalDomain(to.toString())) {
			if (packet.getElemName() == "message") {

				// Yes this packet is for admin....
				if (log.isLoggable(Level.FINER)) {
					log.log(Level.FINER, "Packet for admin: {0}", packet);
				}
				sendToAdmins(packet);
				packet.processedBy("admins-or-domains");

				return true;
			} else {
				if (log.isLoggable(Level.FINER)) {
					log.log(Level.FINER, "Packet for hostname, should be handled elsewhere: {0}",
							packet);
				}
			}
		}    // end of if (isInRoutings(to))

		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param pc
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean processCommand(Packet pc) {
		if ((pc.getStanzaTo() == null) ||!(getComponentId().equals(pc.getStanzaTo()) ||
				isLocalDomain(pc.getStanzaTo().toString()))) {
			return false;
		}

		Iq      iqc               = (Iq) pc;
		boolean processing_result = false;

		if (log.isLoggable(Level.FINER)) {
			log.log(Level.FINER, "{0} command from: {1}", new Object[] { iqc.getCommand()
					.toString(),
					iqc.getFrom() });
		}

		XMPPResourceConnection connection = connectionsByFrom.get(iqc.getFrom());

		switch (iqc.getCommand()) {
		case CLOSE : {
			log.log(Level.WARNING, "Unexpected packet: {0}", pc);
			processing_result = true;
		}

		break;

		case STREAM_OPENED : {

			// Response is sent from the thread when opening user session is
			// completed.
			// fastAddOutPacket(pc.okResult((String) null, 0));
			ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.get(sessionOpenProc
					.id());

			if (pt == null) {
				pt = workerThreads.get(defPluginsThreadsPool);
			}
			pt.addItem(sessionOpenProc, iqc, connection);
			processing_result = true;
		}

		break;

		case GETFEATURES : {
			if (iqc.getType() == StanzaType.get) {
				List<Element> features = getFeatures(connectionsByFrom.get(iqc.getFrom()));
				Packet        result   = iqc.commandResult(null);

				Command.setData(result, features);
				addOutPacket(result);
			}    // end of if (pc.getType() == StanzaType.get)
			processing_result = true;
		}

		break;

		case STREAM_CLOSED : {
			fastAddOutPacket(iqc.okResult((String) null, 0));

			ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.get(sessionCloseProc
					.id());

			if (pt == null) {
				pt = workerThreads.get(defPluginsThreadsPool);
			}
			pt.addItem(sessionCloseProc, iqc, connection);

			// closeConnection(pc.getFrom(), false);
			processing_result = true;
		}

		break;

		case STREAM_CLOSED_UPDATE : {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "{0} processing comment, connection: {1}", new Object[] {
						iqc.getCommand(), ((connection != null)
						? connection
						: " is null") });
			}

			// Note! We don't send response to this packet....
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "{0} adding to the processor: {1}", new Object[] { iqc
						.getCommand(), ((connection != null)
						? connection
						: " is null") });
			}
			if (connection == null) {

				// Hm, the user connection does not exist here but
				// the XMPPSession thinks it still does, a quick fix should
				// be enough.
				// TODO: investigate why this happens at all, an exception
				// during connection close processing????
				JID stanzaFrom = iqc.getStanzaFrom();

				if (stanzaFrom == null) {

					// This is wrong
					log.log(Level.WARNING, "Stream close update without an user JID: {0}", iqc);
				} else {
					XMPPSession xs = sessionsByNodeId.get(stanzaFrom.getBareJID());

					if (xs == null) {
						log.log(Level.INFO,
								"Stream close for the user session which does not exist: {0}", iqc);
					} else {
						XMPPResourceConnection xcr = xs.getResourceForConnectionId(iqc
								.getPacketFrom());

						if (xcr == null) {
							log.log(Level.INFO,
									"Stream close for the resource connection which does not exist", iqc);
						} else {
							xs.removeResourceConnection(xcr);
							if (log.isLoggable(Level.FINEST)) {
								log.log(Level.FINEST, "{0} removed resource connection: {1}",
										new Object[] { iqc.getCommand(),
										xcr });
							}
						}
					}
				}
			} else {
				ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.get(sessionCloseProc
						.id());

				if (pt == null) {
					pt = workerThreads.get(defPluginsThreadsPool);
				}
				pt.addItem(sessionCloseProc, iqc, connection);
			}

			// closeConnection(pc.getFrom(), false);
			processing_result = true;
		}

		break;

		case USER_STATUS :
			try {
				if (isTrusted(iqc.getStanzaFrom()) || isTrusted(iqc.getStanzaFrom()
						.getDomain())) {
					String  av        = Command.getFieldValue(pc, "available");
					boolean available = !((av != null) && av.equalsIgnoreCase("false"));

					if (available) {
						Packet  presence = null;
						Element p        = iqc.getElement().getChild("command").getChild("presence");

						if (p != null) {

							// + // use this hack to break XMLNS
							// + Element el = new Element("presence");
							// + el.setChildren(p.getChildren());
							Element elem = p.clone();

							elem.setXMLNS("jabber:client");
							presence = Packet.packetInstance(elem);
						}
						connection = connectionsByFrom.get(iqc.getStanzaFrom());
						if (connection == null) {
							JID user_jid = JID.jidInstance(Command.getFieldValue(iqc, "jid"));

							connection = loginUserSession(iqc.getStanzaFrom(), user_jid.getDomain(),
									user_jid.getBareJID(), user_jid.getResource(), "USER_STATUS", false);
							connection.putSessionData("jingle", "active");
							fastAddOutPacket(iqc.okResult((String) null, 0));
							if (presence == null) {
								presence = Packet.packetInstance(new Element("presence", new Element[] {
										new Element("priority", "-1"),
										new Element("c", new String[] { "node", "ver", "ext", "xmlns" },
												new String[] { "http://www.google.com/xmpp/client/caps",
										XMPPServer.getImplementationVersion(), "voice-v1",
												"http://jabber.org/protocol/caps" }) }, null, null));
							}
						} else {

							// addOutPacket(Authorization.CONFLICT.getResponseMessage(pc,
							// "The user resource already exists.", true));
							if (log.isLoggable(Level.FINEST)) {
								log.finest(
										"USER_STATUS set to true for user who is already available: " + iqc
										.toStringSecure());
							}
						}
						if (presence != null) {
							presence.setPacketFrom(iqc.getStanzaFrom());
							presence.setPacketTo(getComponentId());
							addOutPacket(presence);
						}
					} else {
						connection = connectionsByFrom.remove(iqc.getStanzaFrom());
						if (connection != null) {
							closeSession(connection, false);
							addOutPacket(iqc.okResult((String) null, 0));
						} else {
							addOutPacket(Authorization.ITEM_NOT_FOUND.getResponseMessage(iqc,
									"The user resource you want to remove does not exist.", true));
							log.info("Can not find resource connection for packet: " + iqc
									.toStringSecure());
						}
					}
				} else {
					try {
						addOutPacket(Authorization.FORBIDDEN.getResponseMessage(iqc,
								"Only trusted entity can do it.", true));
					} catch (PacketErrorTypeException e) {
						log.warning("Packet error type when not expected: " + iqc.toStringSecure());
					}
				}
			} catch (Exception e) {
				try {
					addOutPacket(Authorization.UNDEFINED_CONDITION.getResponseMessage(iqc,
							"Unexpected error occured during the request: " + e, true));
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				log.log(Level.WARNING, "USER_STATUS session creation error: ", e);
			}
			processing_result = true;

			break;

		case OTHER :
			if (getComponentId().equals(iqc.getStanzaTo()) && getComponentId().equals(iqc
					.getPacketFrom())) {

				// No such command available. This prevents from an infinite loop in
				// case there is no implementation to hadle such a command
				try {
					addOutPacket(Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(iqc,
							"There is no implementation for such command on the server.", true));
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				log.log(Level.WARNING,
						"There is no implementation for such command on the server: " + iqc);
				processing_result = true;
			}

			break;

		case CLIENT_AUTH :
			if (connection != null) {
				String[] jids = Command.getFieldValues(pc, "jids");

				connection.putSessionData(SaslEXTERNAL.SASL_EXTERNAL_ALLOWED, Boolean.TRUE);
				connection.putSessionData(SaslEXTERNAL.SESSION_AUTH_JIDS_KEY, jids);
				processing_result = true;
			}

			break;

		case STREAM_MOVED :
			if (connection != null) {
				String oldConnectionJidStr = Command.getFieldValue(pc, "old-conn-jid");
				JID    oldConnJid          = JID.jidInstanceNS(oldConnectionJidStr);

				try {

					// get old session and replace it's connection id to redirect packets
					// to new connection
					XMPPResourceConnection oldConn = connectionsByFrom.remove(oldConnJid);

					oldConn.setConnectionId(connection.getConnectionId());
					connectionsByFrom.remove(connection.getConnectionId());
					connectionsByFrom.put(oldConn.getConnectionId(), oldConn);

					// remove current connection from list of active connections as
					// this connection will be used with other already authenticated connection
					sessionsByNodeId.get(oldConn.getBareJID()).removeResourceConnection(connection);

					Packet cmd = Command.STREAM_MOVED.getPacket(getComponentId(), oldConnJid,
							StanzaType.set, "moved");

					Command.addFieldValue(cmd, "cmd", "stream-moved");
					Command.addFieldValue(cmd, "new-conn-jid", oldConn.getConnectionId()
							.toString());
					cmd.setPacketFrom(getComponentId());
					cmd.setPacketTo(oldConnJid);
					addOutPacket(cmd);
				} catch (XMPPException ex) {
					log.log(Level.SEVERE, "exception while replacing old connection id = " +
							oldConnJid + " with new connection id = " + pc.getPacketFrom().toString(),
							ex);
				}
			}
			processing_result = true;

			break;

		default :
			if (getComponentId().equals(iqc.getStanzaTo()) && getComponentId().equals(iqc
					.getPacketFrom())) {

				// No such command available. This prevents from an infinite loop in
				// case there is no implementation to hadle such a command
				try {
					addOutPacket(Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(iqc,
							"There is no implementation for such command on the server.", true));
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				log.log(Level.WARNING,
						"There is no implementation for such command on the server: " + iqc);
				processing_result = true;
			}

			break;
		}    // end of switch (pc.getCommand())

		return processing_result;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param conn
	 */
	protected void processPacket(Packet packet, XMPPResourceConnection conn) {
		long startTime = System.currentTimeMillis();
		int  idx       = tIdx;

		tIdx = (tIdx + 1) % maxIdx;

		// long defPrepTm = 0;
		// long prepTm = 0;
		// long defForwTm = 0;
		// long walkTm = 0;
		// long postTm = 0;
		// TODO: check if this is really necessary, seems to be even harmful in some
		// cases like when the error is generated as a response to a bad packet.
		packet.setPacketTo(getComponentId());
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "processing packet: {0}, connection: {1}", new Object[] {
					packet.toStringSecure(),
					conn });
		}

		Queue<Packet> results = new ArrayDeque<Packet>(2);
		boolean       stop    = false;

		if (!stop) {
			if (defPacketHandler.preprocess(packet, conn, naUserRepository, results)) {
				packet.processedBy("filter-foward");
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Packet preprocessed: {0}", packet.toStringSecure());
					if (results.size() > 0) {
						for (Packet p : results) {
							log.log(Level.FINEST, "Preprocess result: {0}", p.toStringSecure());
						}
					}
				}
				addOutPackets(packet, conn, results);

				return;
			}
		}

		// defPrepTm = System.currentTimeMillis() - startTime;
		// Preprocess..., all preprocessors get all messages to look at.
		// I am not sure if this is correct for now, let's try to do it this
		// way and maybe change it later.
		// If any of them returns true - it means processing should stop now.
		// That is needed for preprocessors like privacy lists which should
		// block certain packets.
		if (!stop) {
			for (XMPPPreprocessorIfc preproc : preProcessors.values()) {
				stop |= preproc.preProcess(packet, conn, naUserRepository, results, plugin_config
						.get(preproc.id()));
				if (stop && log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Packet blocked by: {0}, packet{1}", new Object[] {
							preproc.id(),
							packet });

					break;
				}
			}    // end of for (XMPPPreprocessorIfc preproc: preProcessors)
		}

		// prepTm = System.currentTimeMillis() - startTime;
		if (!stop) {
			if (defPacketHandler.forward(packet, conn, naUserRepository, results)) {
				packet.processedBy("filter-foward");
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Packet forwarded: {0}", packet);
				}
				addOutPackets(packet, conn, results);

				return;
			}
		}

		// defForwTm = System.currentTimeMillis() - startTime;
		if (!stop) {
			walk(packet, conn);
			try {
				if ((conn != null) && conn.getConnectionId().equals(packet.getPacketFrom())) {
					handleLocalPacket(packet, conn);
				}
			} catch (NoConnectionIdException ex) {

				// Ignore, this should not happen at this point, or even at all.
				log.log(Level.INFO,
						"Impossible happened, please report to developer packet: {0}, connection: {1}.",
						new Object[] { packet,
						conn });
			}
		}

		// walkTm = System.currentTimeMillis() - startTime;
		if (!stop) {
			for (XMPPPostprocessorIfc postproc : postProcessors.values()) {
				String plug_id      = postproc.id();
				long[] postProcTime = null;

				synchronized (postTimes) {
					postProcTime = postTimes.get(plug_id);
					if (postProcTime == null) {
						postProcTime = new long[maxIdx];
						postTimes.put(plug_id, postProcTime);
					}
				}

				long stTime = System.currentTimeMillis();

				postproc.postProcess(packet, conn, naUserRepository, results, plugin_config.get(
						postproc.id()));
				postProcTime[idx] = System.currentTimeMillis() - stTime;
			}    // end of for (XMPPPostprocessorIfc postproc: postProcessors)
		}      // end of if (!stop)

		// postTm = System.currentTimeMillis() - startTime;
		if (!stop &&!packet.wasProcessed() && ((packet.getStanzaTo() == null) ||
				(!isLocalDomain(packet.getStanzaTo().toString())))) {
			if (defPacketHandler.canHandle(packet, conn)) {
				ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.get(defHandlerProc
						.id());

				if (pt == null) {
					pt = workerThreads.get(defPluginsThreadsPool);
				}
				pt.addItem(defHandlerProc, packet, conn);
				packet.processedBy(defHandlerProc.id());
			}
		}
		setPermissions(conn, results);
		addOutPackets(packet, conn, results);
		if (packet.wasProcessed() || processAdminsOrDomains(packet)) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet processed by: {0}", packet.getProcessorsIds()
						.toString());
			}
		} else {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet not processed: {0}", packet.toStringSecure());
			}

			Packet error = null;

			if (stop || ((conn == null) && (packet.getStanzaFrom() != null) && (packet
					.getStanzaTo() != null) &&!packet.getStanzaTo().equals(getComponentId()) &&
					((packet.getElemName() == Iq.ELEM_NAME) || (packet.getElemName() == Message
					.ELEM_NAME)))) {
				try {
					error = Authorization.SERVICE_UNAVAILABLE.getResponseMessage(packet,
							"Service not available.", true);
				} catch (PacketErrorTypeException e) {
					log.log(Level.FINE, "Service not available. Packet is error type already: {0}",
							packet.toStringSecure());
				}
			} else {
				if ((packet.getStanzaFrom() != null) || (conn != null)) {
					try {
						error = Authorization.FEATURE_NOT_IMPLEMENTED.getResponseMessage(packet,
								"Feature not supported yet.", true);
					} catch (PacketErrorTypeException e) {
						log.log(Level.FINE,
								"Feature not supported yet. Packet is error type already: {0}", packet
								.toStringSecure());
					}
				}
			}
			if (error != null) {
				if (error.getStanzaTo() != null) {
					conn = getResourceConnection(error.getStanzaTo());
				}      // end of if (error.getElemTo() != null)
				try {
					if (conn != null) {
						error.setPacketTo(conn.getConnectionId());
					}    // end of if (conn != null)
					addOutPacket(error);
				} catch (NoConnectionIdException e) {

					// Hm, strange, SM own session?
					log.log(Level.WARNING, "Error packet to the SM''s own session: {0}", error);
				}
			}
		}    // end of else

		// defPrepTime[idx] = defPrepTm;
		// prepTime[idx] = prepTm;
		// defForwTime[idx] = defForwTm;
		// walkTime[idx] = walkTm;
		// postTime[idx] = postTm;
	}

	/**
	 * Method description
	 *
	 *
	 * @param session is a <code>XMPPSession</code>
	 * @param packet is a <code>Element</code>
	 */
	protected void processPresenceUpdate(XMPPSession session, Element packet) {
		try {
			if (presenceProcessors.isEmpty()) {
				return;
			}

			Packet presence = Packet.packetInstance(packet);

			for (XMPPResourceConnection conn : session.getActiveResources()) {
				for (XMPPPresenceUpdateProcessorIfc proc : presenceProcessors.values()) {
					try {
						proc.presenceUpdate(conn, presence, packetWriterQueue);
					} catch (NotAuthorizedException ex) {
						log.log(Level.SEVERE, "exception processing presence update for " +
								"session = {0} and packet = {1}", new Object[] { session,
								packet });
					}
				}
			}
		} catch (TigaseStringprepException ex) {

			// should not happen
			log.log(Level.SEVERE, "exception processing presence update for session = {0}" +
					" and packet = {1}", new Object[] { session,
					packet });
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param userId
	 * @param conn
	 */
	protected void registerNewSession(BareJID userId, XMPPResourceConnection conn) {
		synchronized (conn) {
			if (conn.getSessionData(XMPPResourceConnection.CLOSING_KEY) != null) {

				// The user just closed the connection, ignore....
				return;
			}

			XMPPSession session = sessionsByNodeId.get(userId);

			if (session == null) {
				session = new XMPPSession(userId.getLocalpart());
				sessionsByNodeId.put(userId, session);

				int currSize = sessionsByNodeId.size();

				if (currSize > maxUserSessions) {
					maxUserSessions = currSize;
				}
				++totalUserSessions;
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Created new XMPPSession for: {0}", userId);
				}
			} else {

				// Check all other connections whether they are still alive....
				List<XMPPResourceConnection> connections = session.getActiveResources();

				if (connections != null) {
					for (XMPPResourceConnection connection : connections) {
						if (connection != conn) {
							if (log.isLoggable(Level.FINEST)) {
								log.log(Level.FINEST, "Checking connection: {0}", connection);
							}
							try {
								Packet command = Command.CHECK_USER_CONNECTION.getPacket(
										getComponentId(), connection.getConnectionId(), StanzaType.get, UUID
										.randomUUID().toString());

								Command.addFieldValue(command, "user-jid", userId.toString());
								addOutPacketWithTimeout(command, connectionCheckCommandHandler, 30l,
										TimeUnit.SECONDS);
							} catch (NoConnectionIdException ex) {

								// This actually should not happen... might be a bug:
								log.log(Level.WARNING, "This should not happen, check it out!, ", ex);
							}
						}
					}
				}
			}
			try {
				session.addResourceConnection(conn);
				if ((!"USER_STATUS".equals(conn.getSessionId())) &&!conn.isServerSession() &&!conn
						.isTmpSession()) {
					try {
						Packet user_login_cmd = Command.USER_LOGIN.getPacket(getComponentId(), conn
								.getConnectionId(), StanzaType.set, conn.nextStanzaId(), Command.DataType
								.submit);

						Command.addFieldValue(user_login_cmd, "user-jid", userId.toString());
						addOutPacket(user_login_cmd);
					} catch (NoConnectionIdException ex) {

						// This actually should not happen... might be a bug:
						log.log(Level.WARNING, "This should not happen, check it out!, ", ex);
					}
				}
			} catch (TigaseStringprepException ex) {
				log.log(Level.INFO, "Stringprep problem for resource connection: {0}", conn);
				handleLogout(userId, conn);
			}
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 */
	protected void sendToAdmins(Packet packet) {
		for (BareJID admin : admins) {
			if (log.isLoggable(Level.FINER)) {
				log.log(Level.FINER, "Sending packet to admin: {0}", admin);
			}

			Packet admin_pac = packet.copyElementOnly();

			admin_pac.initVars(packet.getStanzaFrom(), JID.jidInstance(admin));
			processPacket(admin_pac);
		}
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param def
	 *
	 *
	 *
	 * @return a value of <code>Integer</code>
	 */
	@Override
	protected Integer getMaxQueueSize(int def) {
		return def * 10;
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>XMPPSession</code>
	 */
	protected XMPPSession getSession(BareJID jid) {
		return sessionsByNodeId.get(jid);
	}

	/**
	 * Method description
	 *
	 *
	 * @param connId
	 *
	 *
	 *
	 * @return a value of <code>XMPPResourceConnection</code>
	 */
	protected XMPPResourceConnection getXMPPResourceConnection(JID connId) {
		return connectionsByFrom.get(connId);
	}

	/**
	 * Method description
	 *
	 *
	 * @param p
	 *
	 *
	 *
	 * @return a value of <code>XMPPResourceConnection</code>
	 */
	protected XMPPResourceConnection getXMPPResourceConnection(Packet p) {
		XMPPResourceConnection conn = null;
		JID                    from = p.getPacketFrom();

		if (from != null) {
			conn = connectionsByFrom.get(from);
			if (conn != null) {
				return conn;
			}
		}

		// It might be a message _to_ some user on this server
		// so let's look for established session for this user...
		JID to = p.getStanzaTo();

		if (to != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Searching for resource connection for: " + to);
			}
			conn = getResourceConnection(to);
		} else {

			// Hm, not sure what should I do now....
			// Maybe I should treat it as message to admin....
			log.log(Level.INFO,
					"Message without TO attribute set, don''t know what to do wih this: {0}", p);
		}    // end of else

		return conn;
	}

	/**
	 * Method description
	 *
	 *
	 * @param p
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean isBrokenPacket(Packet p) {

		// TODO: check this out to make sure it does not lead to an infinite
		// processing loop These are most likely packets generated inside the SM to
		// other users who are offline, like presence updates.
		if (getComponentId().equals(p.getPacketFrom()) && (p.getPacketTo() == null)) {
			return false;
		}
		if (p.getFrom() == null) {

			// This is actually a broken packet and we can't even return an error
			// for it, so just log it and drop it.
			log.log(Level.FINE, "Broken packet: {0}", p.toStringSecure());

			return true;
		}
		if (!p.getFrom().equals(p.getStanzaFrom()) && (!p.isCommand() || (p.isCommand() && (p
				.getCommand() == Command.OTHER)))) {

			// Sometimes (Bosh) connection is gone and this is an error packet
			// sent back to the original sender. This original sender might be
			// not local....
			if ((p.getStanzaFrom() != null) &&!isLocalDomain(p.getStanzaFrom().getDomain())) {

				// ok just forward it there....
				p.setPacketFrom(null);
				p.setPacketTo(null);
				fastAddOutPacket(p);

				return true;
			}

			// It doesn't look good, there should really be a connection for
			// this packet....
			// returning error back...
			log.log(Level.FINE, "Broken packet: {0}", p.toStringSecure());

			// we do not want to send presence error packets here...
			if ((p.getElemName() == Iq.ELEM_NAME) || (p.getElemName() == Message.ELEM_NAME)) {
				try {
					Packet error = Authorization.SERVICE_UNAVAILABLE.getResponseMessage(p,
							"Service not available.", true);

					error.setPacketTo(p.getFrom());
					fastAddOutPacket(error);
				} catch (PacketErrorTypeException e) {
					log.log(Level.FINE, "Packet is error type already: {0}", p.toStringSecure());
				}
			}

			return true;
		}

		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean isTrusted(JID jid) {
		if (trusted.contains(jid.getBareJID().toString())) {
			return true;
		}

		return isAdmin(jid);
	}

	/**
	 * Method description
	 *
	 *
	 * @param jid
	 *
	 *
	 *
	 * @return a value of <code>boolean</code>
	 */
	protected boolean isTrusted(String jid) {
		if (trusted.contains(jid)) {
			return true;
		}

		return false;
	}

	//~--- methods --------------------------------------------------------------

	private long calcAverage(long[] timings) {
		long res = 0;

		for (long ppt : timings) {
			res += ppt;
		}

		long processingTime = res / timings.length;

		return processingTime;
	}

	private void walk(final Packet packet, final XMPPResourceConnection connection) {

		// final Element elem, final Queue<Packet> results) {
		for (XMPPProcessorIfc proc_t : processors.values()) {
			XMPPProcessorIfc processor = proc_t;
			Authorization    result    = processor.canHandle(packet, connection);

			if (result == Authorization.AUTHORIZED) {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "XMPPProcessorIfc: {0} ({1}" + ")" + "\n Request: " +
							"{2}, conn: {3}", new Object[] { processor.getClass().getSimpleName(),
							processor.id(), packet, connection });
				}

				ProcessingThreads<ProcessorWorkerThread> pt = workerThreads.get(processor.id());

				if (pt == null) {
					pt = workerThreads.get(defPluginsThreadsPool);
				}
				if (pt.addItem(processor, packet, connection)) {
					packet.processedBy(processor.id());
				} else {

					// proc_t.debugQueue();
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE,
								"Can not add packet: {0} to processor: {1} internal queue full.",
								new Object[] { packet.toStringSecure(),
								pt.getName() });
					}
				}
			} else {
				if (result != null) {

					// TODO: A plugin returned an error, the packet should be bounced back
					// with an appropriate error
				}
			}
		}    // end of for ()
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Calculates number of Active Users, i.e. users which session wasn't idle longer than 5 minutes
	 * @return number of Active Users
	 */
	private long getActiveUserNumber() {
		int count = 0;

		for (BareJID bareJID : sessionsByNodeId.keySet()) {
			if (!bareJID.toString().startsWith("sess-man")) {
				for (XMPPResourceConnection xMPPResourceConnection : sessionsByNodeId.get(bareJID)
						.getActiveResources()) {
					if (System.currentTimeMillis() - xMPPResourceConnection.getLastAccessed() < 5 *
							60 * 1000) {
						count++;
					}
				}
			}
		}

		return count;
	}

	private List<Element> getFeatures(XMPPResourceConnection session) {
		List<Element> results = new LinkedList<Element>();

		for (XMPPProcessorIfc proc_t : processors.values()) {
			Element[] features = proc_t.supStreamFeatures(session);

			if (features != null) {
				results.addAll(Arrays.asList(features));
			}    // end of if (features != null)
		}      // end of for ()

		return results;
	}

	private Map<String, Object> getPluginSettings(String plug_id, Map<String,
			Object> props) {
		Map<String, Object> plugin_settings = new ConcurrentHashMap<String, Object>(10);

		// First set all options common for all plugins and then set all options
		// specific to the
		// plugin to make sure specific options can overwrite common options
		for (Map.Entry<String, Object> entry : props.entrySet()) {
			if (entry.getKey().startsWith(PLUGINS_CONF_PROP_KEY)) {

				// Split the key to configuration nodes separated with '/'
				String[] nodes = entry.getKey().split("/");

				// Settings option for all plugins
				if (nodes.length == 2) {
					plugin_settings.put(nodes[1], entry.getValue());
					log.log(Level.CONFIG, "Adding a common plugins option: {0} = {1}",
							new Object[] { nodes[1],
							entry.getValue() });
				}
			}
		}

		// Now set plugin specific options
		for (Map.Entry<String, Object> entry : props.entrySet()) {
			if (entry.getKey().startsWith(PLUGINS_CONF_PROP_KEY)) {

				// Split the key to configuration nodes separated with '/'
				String[] nodes = entry.getKey().split("/");

				// The plugin ID part may contain many IDs separated with comma ','
				if (nodes.length > 2) {
					String[] ids = nodes[1].split(",");

					Arrays.sort(ids);
					if (Arrays.binarySearch(ids, plug_id) >= 0) {
						plugin_settings.put(nodes[2], entry.getValue());
						log.log(Level.CONFIG, "Adding a specific plugins option [{0}]: {1} = {2}",
								new Object[] { plug_id,
								nodes[1], entry.getValue() });
					}
				}
			}
		}
		plugin_settings.put("sm-jid", getComponentId());

		return plugin_settings;
	}

	//~--- set methods ----------------------------------------------------------

	private void setPermissions(XMPPResourceConnection conn, Queue<Packet> results) {
		Permissions perms = Permissions.NONE;

		if (conn != null) {
			perms = Permissions.LOCAL;
			if (conn.isAuthorized()) {
				perms = Permissions.AUTH;
				if (conn.isAnonymous()) {
					perms = Permissions.ANONYM;
				} else {
					try {
						JID id = conn.getJID();

						if (isTrusted(id)) {
							perms = Permissions.TRUSTED;
						}
						if (isAdmin(id)) {
							perms = Permissions.ADMIN;
						}
					} catch (NotAuthorizedException e) {
						perms = Permissions.NONE;
					}
				}
			}
		}
		for (Packet res : results) {
			res.setPermissions(perms);
		}
	}

	//~--- inner classes --------------------------------------------------------

	private class AuthenticationTimer
					extends tigase.util.TimerTask {
		private JID connId = null;

		//~--- constructors -------------------------------------------------------

		private AuthenticationTimer(JID connId) {
			this.connId = connId;
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 */
		@Override
		public void run() {
			XMPPResourceConnection conn = connectionsByFrom.get(connId);

			if (conn != null) {
				synchronized (conn) {
					if (!conn.isAuthorized()) {
						conn.putSessionData(XMPPResourceConnection.AUTHENTICATION_TIMEOUT_KEY,
								XMPPResourceConnection.AUTHENTICATION_TIMEOUT_KEY);
						connectionsByFrom.remove(connId);
						++authTimeouts;
						log.log(Level.INFO,
								"Authentication timeout expired, closing connection: {0}", connId);
						fastAddOutPacket(Command.CLOSE.getPacket(getComponentId(), connId, StanzaType
								.set, conn.nextStanzaId()));
					}
				}
			}
		}
	}


	private class ConnectionCheckCommandHandler
					implements ReceiverTimeoutHandler {
		/**
		 * Method description
		 *
		 *
		 * @param packet
		 * @param response
		 */
		@Override
		public void responseReceived(Packet packet, Packet response) {
			if (response.getType() == StanzaType.error) {
				if (log.isLoggable(Level.FINER)) {
					log.log(Level.FINER,
							"Connection checker error received, closing connection: {0}", packet
							.getTo());
				}

				// The connection is not longer active, closing the user session here.
				String userJid = Command.getFieldValue(packet, "user-jid");

				closeConnection(packet.getTo(), userJid, false);
			}
		}

		/**
		 * Method description
		 *
		 *
		 * @param packet
		 */
		@Override
		public void timeOutExpired(Packet packet) {
			if (log.isLoggable(Level.FINER)) {
				log.log(Level.FINER,
						"Connection checker timeout expired, closing connection: {0}", packet
						.getTo());
			}

			String userJid = Command.getFieldValue(packet, "user-jid");

			closeConnection(packet.getTo(), userJid, false);
		}
	}


	private class DefaultHandlerProc
					extends XMPPProcessor
					implements XMPPProcessorIfc {
		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>int</code>
		 */
		@Override
		public int concurrentQueuesNo() {
			return 4;
		}

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>String</code>
		 */
		@Override
		public String id() {
			return defaultHandlerProcId;
		}

		/**
		 * Method description
		 *
		 *
		 * @param packet
		 * @param session
		 * @param repo
		 * @param results
		 * @param settings
		 *
		 * @throws XMPPException
		 */
		@Override
		public void process(Packet packet, XMPPResourceConnection session,
				NonAuthUserRepository repo, Queue<Packet> results, Map<String, Object> settings)
				throws XMPPException {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Executing default packet handler for: {0}", packet);
			}
			defPacketHandler.process(packet, session, repo, results);
		}
	}


	private class ProcessorWorkerThread
					extends WorkerThread {
		private ArrayDeque<Packet> local_results = new ArrayDeque<Packet>(100);

		//~--- methods ------------------------------------------------------------

		// ~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param item
		 */
		@Override
		public void process(QueueItem item) {
			XMPPProcessorIfc processor = item.getProcessor();

			try {
				processor.process(item.getPacket(), item.getConn(), naUserRepository,
						local_results, plugin_config.get(processor.id()));
				if (item.getConn() != null) {
					setPermissions(item.getConn(), local_results);
				}
				addOutPackets(item.getPacket(), item.getConn(), local_results);
			} catch (PacketErrorTypeException e) {
				log.log(Level.INFO, "Already error packet, ignoring: {0}", item.getPacket()
						.toStringSecure());
			} catch (XMPPException e) {
				log.log(Level.WARNING, "Exception during packet processing: " + item.getPacket()
						.toStringSecure(), e);
			}
		}

		//~--- get methods --------------------------------------------------------

		// ~--- get methods --------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 *
		 * @return a value of <code>WorkerThread</code>
		 */
		@Override
		public WorkerThread getNewInstance() {
			ProcessorWorkerThread worker = new ProcessorWorkerThread();

			return worker;
		}
	}


	private class SessionCloseProc
					extends XMPPProcessor
					implements XMPPProcessorIfc {
		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>int</code>
		 */
		@Override
		public int concurrentQueuesNo() {
			return 4;
		}

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>String</code>
		 */
		@Override
		public String id() {
			return sessionCloseProcId;
		}

		/**
		 * Method description
		 *
		 *
		 * @param packet
		 * @param session
		 * @param repo
		 * @param results
		 * @param settings
		 *
		 * @throws XMPPException
		 */
		@Override
		public void process(Packet packet, XMPPResourceConnection session,
				NonAuthUserRepository repo, Queue<Packet> results, Map<String, Object> settings)
				throws XMPPException {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Executing connection close for: {0}", packet);
			}

			String userJid = Command.getFieldValue(packet, "user-jid");

			closeConnection(packet.getFrom(), userJid, false);
		}
	}


	private class SessionOpenProc
					extends XMPPProcessor
					implements XMPPProcessorIfc {
		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>int</code>
		 */
		@Override
		public int concurrentQueuesNo() {
			return 4;
		}

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>String</code>
		 */
		@Override
		public String id() {
			return sessionOpenProcId;
		}

		/**
		 * Method description
		 *
		 *
		 * @param packet
		 * @param session
		 * @param repo
		 * @param results
		 * @param settings
		 *
		 * @throws XMPPException
		 */
		@Override
		public void process(Packet packet, XMPPResourceConnection session,
				NonAuthUserRepository repo, Queue<Packet> results, Map<String, Object> settings)
				throws XMPPException {
			XMPPResourceConnection conn = session;

			// It might be existing opened stream after TLS/SASL authorization
			// If not, it means this is new stream
			if (conn == null) {
				if (log.isLoggable(Level.FINER)) {
					log.log(Level.FINER, "Adding resource connection for: {0}", packet.getFrom());
				}

				final String hostname = Command.getFieldValue(packet, "hostname");

				try {
					conn = createUserSession(packet.getFrom(), hostname);
				} catch (TigaseStringprepException ex) {
					log.log(Level.WARNING,
							"Incrrect hostname, did not pass stringprep processing: {0}", hostname);

					return;
				}
				addTimerTask(new AuthenticationTimer(packet.getFrom()), 2, TimeUnit.MINUTES);
			} else {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Stream opened for existing session, authorized: {0}",
							conn.isAuthorized());
				}
			}    // end of else
			conn.setSessionId(Command.getFieldValue(packet, "session-id"));
			conn.setDefLang(Command.getFieldValue(packet, "xml:lang"));
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Setting session-id {0} for connection: {1}",
						new Object[] { conn.getSessionId(),
						conn });
			}
			fastAddOutPacket(packet.okResult((String) null, 0));
		}
	}


	private class StaleConnectionCloser
					extends tigase.util.TimerTask {
		/** Field description */
		public static final int DEF_QUEUE_SIZE = 1000;

		/** Field description */
		public static final long DEF_TIMEOUT = 30 * 1000;

		//~--- fields -------------------------------------------------------------

		private int      maxQueueSize;
		private Set<JID> queueSet;
		private Thread   thread;
		private long     timeout;
		private Set<JID> workingSet;

		//~--- constructors -------------------------------------------------------

		/**
		 * Constructs ...
		 *
		 */
		public StaleConnectionCloser() {
			this(DEF_QUEUE_SIZE, DEF_TIMEOUT);
		}

		/**
		 * Constructs ...
		 *
		 *
		 * @param queueSize
		 * @param timeout
		 */
		public StaleConnectionCloser(int queueSize, long timeout) {
			this.timeout      = timeout;
			this.maxQueueSize = queueSize;
			workingSet        = new HashSet<JID>(queueSize);
			queueSet          = new HashSet<JID>(queueSize);
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 */
		public void closeConnections() {

			// nothing waiting to remove
			if (workingSet.isEmpty()) {
				return;
			}
			log.log(Level.INFO, "Trying to find and remove stale XMPPResourceConnections");

			LinkedList<XMPPResourceConnection> staleConnections =
					new LinkedList<XMPPResourceConnection>();

			for (XMPPSession session : sessionsByNodeId.values()) {
				List<XMPPResourceConnection> connections = session.getActiveResources();

				for (XMPPResourceConnection connection : connections) {
					try {
						JID connectionId = connection.getConnectionId();

						if (workingSet.contains(connectionId)) {

							// queue connection for removal
							staleConnections.offer(connection);

							// remove from working set
							workingSet.remove(connectionId);
						}
					} catch (NoConnectionIdException ex) {
						log.log(Level.FINEST, "found connection without proper connection id = {0}",
								connection.toString());
					}
				}

				// remove queued connections
				XMPPResourceConnection connection;

				while ((connection = staleConnections.poll()) != null) {
					log.log(Level.WARNING, "Found stale XMPPResourceConnection: {0}, removing...",
							connection);
					session.removeResourceConnection(connection);
				}

				// working set is empty so break iteration now
				if (workingSet.isEmpty()) {
					break;
				}
			}
		}

		/**
		 * Method description
		 *
		 *
		 * @param connectionId
		 *
		 *
		 *
		 * @return a value of <code>boolean</code>
		 */
		public boolean queueForClose(JID connectionId) {
			boolean result;

			synchronized (this) {
				if (queueSet.size() > maxQueueSize) {
					return false;
				}
				result = queueSet.add(connectionId);
			}
			if (!result && log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST,
						"connection with id {0} already queued for removing as stale" +
						" XMPPResourceConnection", connectionId);
			}

			return result;
		}

		/**
		 * Method description
		 *
		 */
		@Override
		public void run() {
			if ((thread != null) && thread.isAlive()) {
				return;
			}
			thread = new Thread() {
				@Override
				public void run() {
					process();
					thread = null;
				}
			};
			thread.start();
		}

		//~--- get methods --------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>int</code>
		 */
		public int getMaxQueueSize() {
			return maxQueueSize;
		}

		/**
		 * Method description
		 *
		 *
		 *
		 *
		 * @return a value of <code>long</code>
		 */
		public long getTimeout() {
			return timeout;
		}

		//~--- set methods --------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param queueSize
		 */
		public void setMaxQueueSize(int queueSize) {
			this.maxQueueSize = queueSize;
		}

		//~--- methods ------------------------------------------------------------

		private void process() {
			try {
				while (swapSets()) {
					closeConnections();
				}
			} catch (Throwable th) {
				log.log(Level.SEVERE, "exception closing stale connections", th);
			}
			addTimerTask(this, timeout);
		}

		private boolean swapSets() {
			synchronized (this) {
				Set<JID> tmp = workingSet;

				workingSet = queueSet;
				queueSet   = tmp;
				queueSet.clear();

				return !workingSet.isEmpty();
			}
		}
	}


	/**
	 * Class description
	 *
	 *
	 * @param <E>
	 *
	 * @version        5.2.0, 13/11/02
	 * @author         <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
	 */
	protected class WriterQueue<E extends Packet>
					extends AbstractQueue<E> {
		/**
		 * Method description
		 *
		 *
		 * @return a value of <code>Iterator<E></code>
		 */
		@Override
		public Iterator<E> iterator() {
			throw new UnsupportedOperationException(
					"Not supported yet.");    // To change body of generated methods, choose Tools | Templates.
		}

		/**
		 * Method description
		 *
		 *
		 * @param packet is a <code>E</code>
		 *
		 * @return a value of <code>boolean</code>
		 */
		@Override
		public boolean offer(E packet) {
			return SessionManager.this.addOutPacket(packet);
		}

		/**
		 * Method description
		 *
		 *
		 * @return a value of <code>E</code>
		 */
		@Override
		public E peek() {
			throw new UnsupportedOperationException(
					"Not supported yet.");    // To change body of generated methods, choose Tools | Templates.
		}

		/**
		 * Method description
		 *
		 *
		 * @return a value of <code>E</code>
		 */
		@Override
		public E poll() {
			throw new UnsupportedOperationException(
					"Not supported yet.");    // To change body of generated methods, choose Tools | Templates.
		}

		/**
		 * Method description
		 *
		 *
		 * @return a value of <code>int</code>
		 */
		@Override
		public int size() {
			throw new UnsupportedOperationException(
					"Not supported yet.");    // To change body of generated methods, choose Tools | Templates.
		}
	}
}


//~ Formatted in Tigase Code Convention on 14/01/07
