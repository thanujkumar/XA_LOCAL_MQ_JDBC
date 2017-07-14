package standalone.mq;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;

import org.slf4j.LoggerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.ibm.mq.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.commonservices.trace.Trace;
import com.ibm.msg.client.wmq.WMQConstants;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.core.ConsoleAppender;

/*
 * https://stackoverflow.com/questions/38720153/spring-jms-ibm-mq-has-open-input-count-issue
 * https://stackoverflow.com/questions/27786449/jms-connections-exhausted-using-websphere-mq/43617832#43617832
 * https://www.ibm.com/developerworks/community/blogs/messaging/entry/simplify_your_wmq_jms_client_with_automatic_client_reconnection19?lang=en
 * https://www.ibm.com/support/knowledgecenter/en/SSFKSJ_9.0.0/com.ibm.mq.dev.doc/q031960_.htm
 * https://www.ibm.com/support/knowledgecenter/en/SSFKSJ_9.0.0/com.ibm.mq.dev.doc/q118320_.htm
 */
public class StandaloneIssueSolvedTxMain {
	static {
		 com.ibm.msg.client.services.Trace.setOn();
		 com.ibm.msg.client.services.Trace.setTraceLevel(Trace.INFO_TRACE_LEVEL);
		
		 Logger logger = (Logger) LoggerFactory.getLogger(Thread.currentThread().toString());
		 logger.setAdditive(false);
		 logger.setLevel(Level.DEBUG);
		 LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
		 
		 PatternLayout pl = new PatternLayout();
	     pl.setPattern("%d %5p %t [%c:%L] %m%n)");
	     pl.setContext(lc);
	     pl.start();
	     
		 ConsoleAppender<?> logConsoleAppender = new ConsoleAppender<>();
		 logConsoleAppender.setContext(lc);
		 logConsoleAppender.start();
	}

	public MQQueueConnectionFactory getMQQueueConnectionFactory() throws JMSException {
		MQQueueConnectionFactory mqQCF = new MQQueueConnectionFactory();
		mqQCF.setHostName("localhost");

		mqQCF.setTransportType(WMQConstants.WMQ_CM_CLIENT);
		// mqQCF.setCCSID(1208);
		// mqQCF.setChannel("");
		mqQCF.setPort(1414);
		mqQCF.setQueueManager("DEVMQ");
		return mqQCF;
	}


	public CachingConnectionFactory cachingConnectionFactory(
			UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter) {
		CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory();
		//cachingConnectionFactory.setTargetConnectionFactory(userCredentialsConnectionFactoryAdapter);
		cachingConnectionFactory.setSessionCacheSize(5);
		cachingConnectionFactory.setReconnectOnException(true);
		return cachingConnectionFactory;
	}
	
	static  TransactionTemplate txTmp;
	
	public static void main(String[] args) throws Exception{
		StandaloneIssueSolvedTxMain m = new StandaloneIssueSolvedTxMain();
		QueueConnectionFactory qcf = m.getMQQueueConnectionFactory();
		CachingConnectionFactory ccf = m.cachingConnectionFactory(null);
		//https://dzone.com/articles/spring-and-caching-jms
		ccf.setCacheConsumers(false); // if not set input goes on increasing
		ccf.setCacheProducers(false);
		ccf.setTargetConnectionFactory(qcf);
		
		JmsTransactionManager jmsTxMgr = new JmsTransactionManager();
		jmsTxMgr.setConnectionFactory(ccf);
		txTmp = new TransactionTemplate(jmsTxMgr);
		
		
		JmsTemplate jmsT = new JmsTemplate(ccf);
		jmsT.setReceiveTimeout(5000);
		jmsT.setSessionTransacted(true);
		jmsT.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);
		
		int times = 100;
		for (int i = 0 ; i < times ; i++ ) {
			   final int x = i;
				jmsT.send("TESTQUEUE1", (session) -> {
					    Message msg = session.createMessage();
					    msg.setIntProperty("THANUJ", x);
						return  msg;
					}
				);
		}
		
		for (int i = 0; i < times; i++) {
			new CustomThread(jmsT, i).start();
		}
		
		synchronized (jmsT) {
			jmsT.wait();
		}		
	}
	
	static class CustomThread extends Thread {
		JmsTemplate jmsTmp;
		int i;
		public CustomThread(JmsTemplate jtmp, int i) {
			jmsTmp = jtmp;
			this.i= i;
		}
		
		public void run () {
			txTmp.execute(new TransactionCallbackWithoutResult() {
				
				@Override
				protected void doInTransactionWithoutResult(TransactionStatus status) {
					System.out.println(Thread.currentThread() +"-"+jmsTmp.receiveSelected("TESTQUEUE1","THANUJ="+i));
					if (i % 2 == 0) {
						throw new RuntimeException(Thread.currentThread()+"-"+i);
					}
					
				}
			});

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
 
		}
	}
}
