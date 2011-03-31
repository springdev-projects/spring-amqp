/*
 * Copyright (c) 2011 by the original author(s).
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.amqp.rabbit.log4j;

import java.util.Calendar;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.MDC;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * A Log4J appender that publishes logging events to an AMQP Exchange.
 * <p>
 * A fully-configured AmqpAppender, with every option set to their defaults, would look like this:
 * </p>
 * 
 * <pre>
 * <code>
 *   log4j.appender.amqp=org.springframework.amqp.log4j.AmqpAppender
 *   #-------------------------------
 *   ## Connection settings
 *   #-------------------------------
 *   log4j.appender.amqp.host=localhost
 *   log4j.appender.amqp.port=5672
 *   log4j.appender.amqp.username=guest
 *   log4j.appender.amqp.password=guest
 *   log4j.appender.amqp.virtualHost=/
 *   log4j.appender.amqp.connectionTimeout=0
 *   #-------------------------------
 *   ## Exchange name and type
 *   #-------------------------------
 *   log4j.appender.amqp.exchangeName=logs
 *   log4j.appender.amqp.exchangeType=topic
 *   #-------------------------------
 *   ## Log4J-format pattern to use to create a routing key.  
 *   ## The application id is available as %X{applicationId}.
 *   #-------------------------------
 *   log4j.appender.amqp.routingKeyPattern=%c.%p
 *   #-------------------------------
 *   ## Whether or not to declare this configured exchange
 *   #-------------------------------
 *   log4j.appender.amqp.declareExchange=false
 *   #-------------------------------
 *   ## Message properties
 *   #-------------------------------
 *   log4j.appender.amqp.contentType=text/plain
 *   log4j.appender.amqp.contentEncoding=null
 *   #-------------------------------
 *   ## Sender configuration
 *   #-------------------------------
 *   log4j.appender.amqp.senderPoolSize=2
 *   log4j.appender.amqp.maxSenderRetries=30
 *   log4j.appender.amqp.applicationId=null
 *   #-------------------------------
 *   ## Standard Log4J stuff
 *   #-------------------------------
 *   log4j.appender.amqp.layout=org.apache.log4j.PatternLayout
 *   log4j.appender.amqp.layout.ConversionPattern=%d %p %t [%c] - <%m>%n
 * </code>
 * </pre>
 * 
 * @author Jon Brisbin <jbrisbin@vmware.com>
 */
public class AmqpAppender extends AppenderSkeleton {

	/**
	 * Key name for the application id (if there is one set via the appender config) in the message properties.
	 */
	public static final String APPLICATION_ID = "applicationId";

	/**
	 * Key name for the logger category name in the message properties
	 */
	public static final String CATEGORY_NAME = "categoryName";

	/**
	 * Key name for the logger level name in the message properties
	 */
	public static final String CATEGORY_LEVEL = "level";

	/**
	 * Name of the exchange to publish log events to.
	 */
	private String exchangeName = "logs";
	/**
	 * Type of the exchange to publish log events to.
	 */
	private String exchangeType = "topic";
	/**
	 * Log4J pattern format to use to generate a routing key.
	 */
	private String routingKeyPattern = "%c.%p";
	/**
	 * Log4J Layout to use to generate routing key.
	 */
	private Layout routingKeyLayout = new PatternLayout(routingKeyPattern);
	/**
	 * Whether or not we've tried to declare this exchange yet.
	 */
	private AtomicBoolean exchangeDeclared = new AtomicBoolean(false);
	/**
	 * How long to wait for a connection to time out.
	 */
	private int connectionTimeout = 0;
	/**
	 * Configuration arbitrary application ID.
	 */
	private String applicationId = null;
	/**
	 * Where LoggingEvents are queued to send.
	 */
	private LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<Event>();
	/**
	 * The pool of senders.
	 */
	private ExecutorService senderPool = null;
	/**
	 * How many senders to use at once. Use more senders if you have lots of log output going through this appender.
	 */
	private int senderPoolSize = 2;
	/**
	 * How many times to retry sending a message if the broker is unavailable or there is some other error.
	 */
	private int maxSenderRetries = 30;
	/**
	 * Retries are delayed like: N ^ log(N), where N is the retry number.
	 */
	private Timer retryTimer = new Timer("log-event-retry-delay", true);
	/**
	 * RabbitMQ ConnectionFactory.
	 */
	private CachingConnectionFactory connectionFactory;
	/**
	 * RabbitMQ host to connect to.
	 */
	private String host = "localhost";
	/**
	 * RabbitMQ virtual host to connect to.
	 */
	private String virtualHost = "/";
	/**
	 * RabbitMQ port to connect to.
	 */
	private int port = 5672;
	/**
	 * RabbitMQ user to connect as.
	 */
	private String username = "guest";
	/**
	 * RabbitMQ password for this user.
	 */
	private String password = "guest";
	/**
	 * Default content-type of log messages.
	 */
	private String contentType = "text/plain";
	/**
	 * Default content-encoding of log messages.
	 */
	private String contentEncoding = null;
	/**
	 * Whether or not to try and declare the configured exchange when this appender starts.
	 */
	private boolean declareExchange = false;
	/**
	 * Used to synchronize access when creating the RabbitMQ ConnectionFactory.
	 */
	private final String mutex = "mutex";

	public AmqpAppender() {
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getVirtualHost() {
		return virtualHost;
	}

	public void setVirtualHost(String virtualHost) {
		this.virtualHost = virtualHost;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getExchangeName() {
		return exchangeName;
	}

	public void setExchangeName(String exchangeName) {
		this.exchangeName = exchangeName;
	}

	public String getExchangeType() {
		return exchangeType;
	}

	public void setExchangeType(String exchangeType) {
		this.exchangeType = exchangeType;
	}

	public String getRoutingKeyPattern() {
		return routingKeyPattern;
	}

	public void setRoutingKeyPattern(String routingKeyPattern) {
		this.routingKeyPattern = routingKeyPattern;
		this.routingKeyLayout = new PatternLayout(routingKeyPattern);
	}

	public boolean isDeclareExchange() {
		return declareExchange;
	}

	public void setDeclareExchange(boolean declareExchange) {
		this.declareExchange = declareExchange;
	}

	public int getConnectionTimeout() {
		return connectionTimeout;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getContentEncoding() {
		return contentEncoding;
	}

	public void setContentEncoding(String contentEncoding) {
		this.contentEncoding = contentEncoding;
	}

	public String getApplicationId() {
		return applicationId;
	}

	public void setApplicationId(String applicationId) {
		this.applicationId = applicationId;
	}

	public int getSenderPoolSize() {
		return senderPoolSize;
	}

	public void setSenderPoolSize(int senderPoolSize) {
		this.senderPoolSize = senderPoolSize;
	}

	public int getMaxSenderRetries() {
		return maxSenderRetries;
	}

	public void setMaxSenderRetries(int maxSenderRetries) {
		this.maxSenderRetries = maxSenderRetries;
	}

	/**
	 * Submit the required number of senders into the pool.
	 */
	protected void startSenders() {
		senderPool = Executors.newCachedThreadPool();
		for (int i = 0; i < senderPoolSize; i++) {
			senderPool.submit(new EventSender());
		}
	}

	/**
	 * Maybe declare the exchange.
	 */
	protected void maybeDeclareExchange() {
		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		if (declareExchange) {
			Exchange x;
			if ("topic".equals(exchangeType)) {
				x = new TopicExchange(exchangeName, true, false);
			} else if ("direct".equals(exchangeType)) {
				x = new DirectExchange(exchangeName, true, false);
			} else if ("fanout".equals(exchangeType)) {
				x = new FanoutExchange(exchangeName, true, false);
			} else if ("headers".equals(exchangeType)) {
				x = new HeadersExchange(exchangeType, true, false);
			} else {
				x = new TopicExchange(exchangeName);
			}
			// admin.deleteExchange(exchangeName);
			admin.declareExchange(x);
		}
	}

	@Override
	public void append(LoggingEvent event) {
		if (null == senderPool) {
			synchronized (mutex) {
				connectionFactory = new CachingConnectionFactory();
				connectionFactory.setHost(host);
				connectionFactory.setPort(port);
				connectionFactory.setUsername(username);
				connectionFactory.setPassword(password);
				connectionFactory.setVirtualHost(virtualHost);
				maybeDeclareExchange();
				exchangeDeclared.set(true);

				startSenders();
			}
		}
		events.add(new Event(event, event.getProperties()));
	}

	public void close() {
		if (null != senderPool) {
			senderPool.shutdownNow();
			senderPool = null;
		}
	}

	public boolean requiresLayout() {
		return true;
	}

	/**
	 * Helper class to actually send LoggingEvents asynchronously.
	 */
	protected class EventSender implements Runnable {
		public void run() {
			try {
				RabbitTemplate rabbitTmpl = new RabbitTemplate(connectionFactory);
				while (true) {
					final Event event = events.take();
					LoggingEvent logEvent = event.getEvent();

					String name = logEvent.getLogger().getName();
					Level level = logEvent.getLevel();

					MessageProperties amqpProps = new MessageProperties();
					amqpProps.setContentType(contentType);
					if (null != contentEncoding) {
						amqpProps.setContentEncoding(contentEncoding);
					}
					amqpProps.setHeader(CATEGORY_NAME, name);
					amqpProps.setHeader(CATEGORY_LEVEL, level.toString());

					// Set applicationId, if we're using one
					if (null != applicationId) {
						amqpProps.setAppId(applicationId);
						MDC.put(APPLICATION_ID, applicationId);
					}

					// Set timestamp
					Calendar tstamp = Calendar.getInstance();
					tstamp.setTimeInMillis(logEvent.getTimeStamp());
					amqpProps.setTimestamp(tstamp.getTime());

					// Copy properties in from MDC
					@SuppressWarnings("rawtypes")
					Map props = event.getProperties();
					for (Object key : event.getProperties().entrySet()) {
						amqpProps.setHeader(key.toString(), props.get(key));
					}
					LocationInfo locInfo = logEvent.getLocationInformation();
					if (!"?".equals(locInfo.getClassName())) {
						amqpProps.setHeader(
								"location",
								String.format("%s.%s()[%s]", locInfo.getClassName(), locInfo.getMethodName(),
										locInfo.getLineNumber()));
					}

					StringBuffer msgBody = new StringBuffer(String.format("%s%n", logEvent.getRenderedMessage()));
					if (null != logEvent.getThrowableInformation()) {
						ThrowableInformation tinfo = logEvent.getThrowableInformation();
						for (String line : tinfo.getThrowableStrRep()) {
							msgBody.append(String.format("%s%n", line));
						}
					}

					// Send a message
					String routingKey = routingKeyLayout.format(logEvent);
					try {
						rabbitTmpl
								.send(exchangeName, routingKey, new Message(msgBody.toString().getBytes(), amqpProps));
					} catch (AmqpException e) {
						int retries = event.incrementRetries();
						if (retries < maxSenderRetries) {
							// Schedule a retry based on the number of times I've tried to re-send this
							retryTimer.schedule(new TimerTask() {
								@Override
								public void run() {
									events.add(event);
								}
							}, (long) (Math.pow(retries, Math.log(retries)) * 1000));
						} else {
							errorHandler.error("Could not send log message " + logEvent.getRenderedMessage()
									+ " after " + maxSenderRetries + " retries", e, ErrorCode.WRITE_FAILURE, logEvent);
						}
					} finally {
						if (null != applicationId) {
							MDC.remove(APPLICATION_ID);
						}
					}
				}
			} catch (Throwable t) {
				throw new RuntimeException(t.getMessage(), t);
			}
		}
	}

	/**
	 * Small helper class to encapsulate a LoggingEvent, its MDC properties, and the number of retries.
	 */
	@SuppressWarnings("rawtypes")
	protected class Event {
		final LoggingEvent event;
		final Map properties;
		AtomicInteger retries = new AtomicInteger(0);

		public Event(LoggingEvent event, Map properties) {
			this.event = event;
			this.properties = properties;
		}

		public LoggingEvent getEvent() {
			return event;
		}

		public Map getProperties() {
			return properties;
		}

		public int incrementRetries() {
			return retries.incrementAndGet();
		}
	}

}
